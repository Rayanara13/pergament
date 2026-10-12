package ru.stef.pergament.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.stef.pergament.store.ChunkCodec;
import ru.stef.pergament.store.RegionData;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeamStoreTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    private static final String OW = "minecraft:overworld";

    private static byte[] chunk(int cx, int cz, int color) {
        RegionData r = new RegionData(cx >> 5, cz >> 5);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) r.set(((cx & 31) << 4) + x, ((cz & 31) << 4) + z, color, 64, 64, 0, RegionData.CLS_LAND);
        }
        return ChunkCodec.extract(r, cx, cz);
    }

    private static int colorAt(byte[] raw) {
        return (raw[0] & 0xFF) << 24 | (raw[1] & 0xFF) << 16 | (raw[2] & 0xFF) << 8 | raw[3] & 0xFF;
    }

    @Test
    void liveReplacesArchiveOnlyFillsEmpty(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        assertNotEquals(0, s.accept(OW, 3, -40, chunk(3, -40, 0xFF000001), false, A));   // архив в пустое — да
        assertEquals(0, s.accept(OW, 3, -40, chunk(3, -40, 0xFF000002), false, B));      // архив поверх — нет
        assertEquals(0xFF000001, colorAt(s.read(OW, 3, -40)));
        assertNotEquals(0, s.accept(OW, 3, -40, chunk(3, -40, 0xFF000003), true, B));    // свежий скан — заменяет
        assertEquals(0xFF000003, colorAt(s.read(OW, 3, -40)));
        assertEquals(2, s.seq());
        assertEquals(1, s.chunks());
    }

    @Test
    void emptyChunkRejected(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        assertEquals(0, s.accept(OW, 0, 0, new byte[ChunkCodec.RAW], true, A));
        assertNull(s.read(OW, 0, 0));
    }

    @Test
    void sinceGivesNewerIncludingOwnGroupedByRegion(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        s.accept(OW, 0, 0, chunk(0, 0, 1), true, A);          // seq 1, A
        s.accept(OW, 40, 0, chunk(40, 0, 2), true, B);        // seq 2, B, другой регион
        s.accept(OW, 1, 0, chunk(1, 0, 3), true, B);          // seq 3, B, регион 0
        s.accept(OW, 0, 0, chunk(0, 0, 4), true, B);          // seq 4: B переписал чанк A
        var all = s.since(0);
        assertEquals(3, all.size(), "своё тоже: второй компьютер того же игрока");
        assertEquals(0, all.get(0).cx() >> 5);               // сначала регион 0 целиком…
        assertEquals(0, all.get(1).cx() >> 5);
        assertEquals(40, all.get(2).cx());                   // …потом регион 1
        assertEquals(1, s.since(3).size(), "только новее watermark");
    }

    @Test
    void survivesRestart(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        s.accept(OW, 5, 5, chunk(5, 5, 0xFF00AA00), true, A);
        s.accept("minecraft:the_nether", -1, -1, chunk(-1, -1, 0xFF0000AA), false, B);
        s.flush();
        TeamStore t = TeamStore.open(dir);
        assertEquals(2, t.seq());
        assertEquals(2, t.chunks());
        assertEquals(0xFF00AA00, colorAt(t.read(OW, 5, 5)));
        assertEquals(0xFF0000AA, colorAt(t.read("minecraft:the_nether", -1, -1)));
        assertEquals(s.incarnation(), t.incarnation(), "воплощение переживает рестарт");
        assertNotEquals(0, t.accept(OW, 6, 5, chunk(6, 5, 1), true, A));
        assertEquals(3, t.seq());
    }

    @Test
    void manyRegionsEvictAndPersist(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        for (int k = 0; k < 30; k++) s.accept(OW, k * 32, 0, chunk(k * 32, 0, k + 1), true, A);   // 30 регионов > кэша
        s.flush();
        TeamStore t = TeamStore.open(dir);
        for (int k = 0; k < 30; k++) assertEquals(k + 1, colorAt(t.read(OW, k * 32, 0)));
    }

    @Test
    void brokenIndexStartsNewIncarnation(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        s.accept(OW, 1, 1, chunk(1, 1, 7), true, A);
        s.flush();
        java.nio.file.Files.write(dir.resolve("index.pgti"), new byte[]{1, 2, 3});
        TeamStore t = TeamStore.open(dir);
        assertNotEquals(s.incarnation(), t.incarnation(), "клиенты должны начать заново");
        assertEquals(0, t.chunks());
        assertEquals(0, t.seq());
    }

    private static ru.stef.pergament.net.TeamNet.TopoRec topo(String id, String custom, UUID fakeAuthor) {
        return new ru.stef.pergament.net.TeamNet.TopoRec(id, "lake", 5L, "lake:3", custom, 999, 999, fakeAuthor);
    }

    @Test
    void toponymsOrderedByServerAuthorFromConnectionAndSurviveRestart(@TempDir Path dir) throws Exception {
        TeamStore s = TeamStore.open(dir);
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var ra = s.acceptTopo(OW, topo(a, "", B), A);                  // клиент врёт про порядок, ревизию и автора
        assertEquals(1, ra.teamSeq());
        assertEquals(A, ra.author());
        var rb = s.acceptTopo(OW, topo(b, "", A), B);
        assertEquals(2, rb.teamSeq());
        var ren = s.acceptTopo(OW, topo(a, "Тапкино болото", B), B);   // переименовать может любой член команды
        assertEquals("Тапкино болото", ren.custom());
        assertEquals(1, ren.teamSeq());
        assertEquals(A, ren.author());                                  // автор открытия не меняется
        assertTrue(ren.rev() > rb.rev());
        assertSame(ren, s.acceptTopo(OW, topo(a, "Тапкино болото", B), B));   // без изменений — та же ревизия
        assertNull(s.acceptTopo("minecraft:the_nether", topo(a, "x", A), A)); // чужое измерение — нет
        s.flushTopos();
        TeamStore again = TeamStore.open(dir);
        assertEquals(2, again.topos().size());
        var next = again.acceptTopo(OW, topo(UUID.randomUUID().toString(), "", A), A);
        assertEquals(3, next.teamSeq());                                // счётчики пережили перезапуск
        assertTrue(next.rev() > ren.rev());
    }
}
