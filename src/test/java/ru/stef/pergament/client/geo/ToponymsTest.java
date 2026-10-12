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
