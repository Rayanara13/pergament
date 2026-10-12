package ru.stef.pergament.client.geo;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static ru.stef.pergament.client.geo.Toponyms.key;

class ToponymsTest {
    private static void fill(Long2LongOpenHashMap m, byte cat, int x0, int z0, int x1, int z1, int peakY) {
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) m.put(key(x, z), Geo.pack(cat, cat == Geo.LAKE ? 1f : 0f, peakY, 0, 0));
    }

    @Test
    void waterDecidesNotBiome() {
        assertEquals(Geo.NONE, Geo.classify(Geo.RIVER, 0.05f));          // «речной» берег без воды
        assertEquals(Geo.RIVER, Geo.classify(Geo.RIVER, 0.3f));
        assertEquals(Geo.LAKE, Geo.classify(Geo.NONE, 0.8f));            // озеро без своего биома
        assertEquals(Geo.NONE, Geo.classify(Geo.NONE, 0.3f));
        assertEquals(Geo.LAKE, Geo.classify(Geo.MOUNTAINS, 0.9f));       // горное озеро
        assertEquals(Geo.VOLCANIC, Geo.classify(Geo.VOLCANIC, 0f));
    }

    @Test
    void packRoundTripWithNegativeHeight() {
        long v = Geo.pack(Geo.VOLCANIC, 0.5f, -37, 11, 4);
        assertEquals(Geo.VOLCANIC, Geo.cat(v));
        assertEquals(-37, Geo.peakY(v));
        assertEquals(11, Geo.peakLx(v));
        assertEquals(4, Geo.peakLz(v));
        assertEquals(0.5f, Geo.water(v), 0.01f);
        assertEquals(400, Geo.peakY(Geo.pack(Geo.MOUNTAINS, 0, 400, 0, 0)));
    }

    @Test
    void lakeGetsNameAndKeepsIt() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.LAKE, 0, 0, 2, 2, 60);
        var reg = new Toponyms.Registry();
        var r1 = Toponyms.compute(cells, reg, "w");
        assertTrue(r1.changed());
        assertEquals(1, r1.labels().size());
        String name = r1.labels().get(0).nameId();
        assertTrue(name.startsWith("lake:"));
        fill(cells, Geo.LAKE, 3, 0, 5, 2, 60);                          // разведал дальше — то же озеро
        var r2 = Toponyms.compute(cells, reg, "w");
        assertFalse(r2.changed());
        assertEquals(name, r2.labels().get(0).nameId());
    }

    @Test
    void mergedLakesKeepOlderName() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.LAKE, 0, 0, 2, 2, 60);
        var reg = new Toponyms.Registry();
        String older = Toponyms.compute(cells, reg, "w").labels().get(0).nameId();
        fill(cells, Geo.LAKE, 10, 0, 12, 2, 60);
        assertEquals(2, Toponyms.compute(cells, reg, "w").labels().size());
        fill(cells, Geo.LAKE, 3, 0, 9, 0, 60);                          // протока — одно озеро
        var r = Toponyms.compute(cells, reg, "w");
        assertEquals(1, r.labels().size());
        assertEquals(older, r.labels().get(0).nameId());
        assertEquals(2, reg.items.size());                              // младшее имя не потеряно — вдруг разойдутся
    }

    @Test
    void smallThingsHaveNoName() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.LAKE, 0, 0, 1, 0, 60);                          // 2 чанка — лужа
        fill(cells, Geo.RIVER, 10, 0, 12, 0, 60);                       // 3 чанка — ручей
        fill(cells, Geo.MOUNTAINS, 20, 0, 22, 2, 200);                  // 9 чанков — холм
        var r = Toponyms.compute(cells, new Toponyms.Registry(), "w");
        assertTrue(r.labels().isEmpty());
    }

    @Test
    void hugeLakeIsSea() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.LAKE, 0, 0, 9, 9, 60);
        var r = Toponyms.compute(cells, new Toponyms.Registry(), "w");
        assertEquals(Toponyms.SEA, r.labels().get(0).type());
    }

    @Test
    void volcanoIsProminentPeakOnly() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.VOLCANIC, 0, 0, 9, 9, 150);
        cells.put(key(5, 5), Geo.pack(Geo.VOLCANIC, 0, 200, 7, 9));     // конус
        var reg = new Toponyms.Registry();
        var r = Toponyms.compute(cells, reg, "w");
        var volcanoes = r.labels().stream().filter(l -> l.type().equals(Toponyms.VOLCANO)).toList();
        assertEquals(1, volcanoes.size());
        assertEquals(5 * 16 + 7, volcanoes.get(0).x());
        assertEquals(5 * 16 + 9, volcanoes.get(0).z());
        assertEquals(1, r.labels().stream().filter(l -> l.type().equals(Toponyms.RANGE)).count());
        cells.put(key(6, 5), Geo.pack(Geo.VOLCANIC, 0, 205, 0, 0));      // разведал — вершина чуть дальше
        var again = Toponyms.compute(cells, reg, "w").labels().stream().filter(l -> l.type().equals(Toponyms.VOLCANO)).toList();
        assertEquals(1, again.size());
        assertEquals(volcanoes.get(0).nameId(), again.get(0).nameId());
    }

    @Test
    void customNameAndTeamOrderWin() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.LAKE, 0, 0, 2, 2, 60);
        var reg = new Toponyms.Registry();
        Toponyms.compute(cells, reg, "w");
        var mine = reg.items.get(0);
        mine.custom = "Тапкино болото";
        var l = Toponyms.compute(cells, reg, "w").labels().get(0);
        assertEquals("Тапкино болото", l.custom());
        assertEquals(mine.id, l.itemId());
        // товарищ открыл это озеро раньше (его запись — с порядком команды): его имя побеждает моё, ещё не отданное
        var team = new Toponyms.Item();
        team.id = "team-1";
        team.type = Toponyms.LAKE;
        team.nameId = "lake:7";
        team.anchor = key(2, 2);
        team.seq = 99;
        team.teamSeq = 5;
        reg.items.add(team);
        assertEquals("team-1", Toponyms.compute(cells, reg, "w").labels().get(0).itemId());
    }

    @Test
    void forestDesertSwampByCanopySandWater() {
        assertEquals(Geo.FOREST, Geo.classify(Geo.NONE, 0f, 0.8f, 0f));
        assertEquals(Geo.DESERT, Geo.classify(Geo.NONE, 0f, 0f, 0.9f));
        assertEquals(Geo.NONE, Geo.classify(Geo.H_BEACH, 0.1f, 0f, 0.9f));     // пляж — не пустыня
        assertEquals(Geo.SWAMP, Geo.classify(Geo.H_SWAMP, 0.3f, 0.2f, 0f));
        assertEquals(Geo.LAKE, Geo.classify(Geo.H_SWAMP, 0.8f, 0f, 0f));
        long v = Geo.pack(Geo.FOREST, 0.1f, 80, 1, 2, 0.75f, 0.25f);
        assertEquals(0.75f, Geo.canopy(v), 0.01f);
        assertEquals(0.25f, Geo.sand(v), 0.01f);
        assertEquals(80, Geo.peakY(v));
    }

    @Test
    void islandOnlyWhenWaterAllAround() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.OCEAN, 0, 0, 9, 9, 40);
        fill(cells, Geo.NONE, 4, 4, 5, 5, 70);                           // суша посреди моря
        var r = Toponyms.compute(cells, new Toponyms.Registry(), "w");
        assertEquals(1, r.labels().stream().filter(l -> l.type().equals(Toponyms.ISLAND)).count());
        var open = new Long2LongOpenHashMap();
        fill(open, Geo.OCEAN, 0, 0, 9, 9, 40);
        fill(open, Geo.NONE, 8, 4, 9, 5, 70);                            // у края разведанного — может быть материк
        var r2 = Toponyms.compute(open, new Toponyms.Registry(), "w");
        assertEquals(0, r2.labels().stream().filter(l -> l.type().equals(Toponyms.ISLAND)).count());
    }

    @Test
    void peakIsHighProminentMountain() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.MOUNTAINS, 0, 0, 13, 13, 110);
        cells.put(key(6, 6), Geo.pack(Geo.MOUNTAINS, 0, 160, 3, 4));
        var r = Toponyms.compute(cells, new Toponyms.Registry(), "w");
        var peaks = r.labels().stream().filter(l -> l.type().equals(Toponyms.PEAK)).toList();
        assertEquals(1, peaks.size());
        assertEquals(6 * 16 + 3, peaks.get(0).x());
    }

    @Test
    void riverPathFollowsTheBend() {
        var cells = new Long2LongOpenHashMap();
        fill(cells, Geo.RIVER, 0, 0, 9, 0, 60);                          // «Г»: вдоль, потом вниз
        fill(cells, Geo.RIVER, 9, 1, 9, 8, 60);
        var river = Toponyms.compute(cells, new Toponyms.Registry(), "w").labels().get(0);
        int[] p = river.path();
        assertNotNull(p);
        assertTrue(p.length / 2 >= 15, "путь по изгибу, а не напрямую: " + p.length / 2);
        assertTrue(p[0] <= p[p.length - 2]);                            // слева направо
    }

    @Test
    void namesAreUniqueAndTranslated() {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 70; i++) used.add(Names.pick("river", 12345, used));
        assertEquals(70, used.size());                                  // список на 61 — дальше «II»
        assertTrue(used.contains("river:0:2") || used.stream().anyMatch(s -> s.endsWith(":2")));
        assertEquals("Лена", Names.display("river:0", "ru_ru", "?"));
        assertEquals("Lena", Names.display("river:0", "en_us", "?"));
        assertEquals("Lena II", Names.display("river:0:2", "en_us", "?"));
        assertEquals("fallback", Names.display("river:9999", "en_us", "fallback"));
    }
}
