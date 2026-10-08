package ru.stef.pergament.client.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Находки: слияние одного материала рядом, разные материалы и далёкие — отдельно, сохранение. */
class FindsTest {
    static Finds.Find f(String mat, int x, int z, int count, int y0, int y1) {
        Finds.Find f = new Finds.Find();
        f.mat = mat;
        f.name = mat;
        f.x = x; f.z = z; f.y = (y0 + y1) / 2;
        f.minY = y0; f.maxY = y1;
        f.count = count;
        return f;
    }

    @Test
    void sameMaterialNearbyMergesWeighted(@TempDir Path dir) {
        Finds s = Finds.load(dir.resolve("finds.json"));
        Finds.Find a = f("gtceu:barite", 0, 0, 30, 40, 50);
        a.byIndicator = true;
        s.addOrMerge(a);
        Finds.Find b = f("gtceu:barite", 10, 0, 10, 35, 45);
        b.byHand = true;
        Finds.Find m = s.addOrMerge(b);
        assertEquals(1, s.all().size(), "рядом и тот же материал — одна жила");
        assertEquals(40, m.count);
        assertEquals(3, m.x, "центр сместился к более весомой находке: 10·10/40 ≈ 2.5 → 3");
        assertEquals(35, m.minY);
        assertEquals(50, m.maxY);
        assertTrue(m.byIndicator && m.byHand);
    }

    @Test
    void otherMaterialOrFarStaysSeparate(@TempDir Path dir) {
        Path file = dir.resolve("finds.json");
        Finds s = Finds.load(file);
        s.addOrMerge(f("gtceu:barite", 0, 0, 5, 40, 50));
        s.addOrMerge(f("gtceu:quartzite", 2, 2, 5, 40, 50));
        s.addOrMerge(f("gtceu:barite", 200, 0, 5, 40, 50));
        assertEquals(3, Finds.load(file).all().size());
    }

    @Test
    void sharedEventAppliesOnceAndSurvivesReload(@TempDir Path dir) {
        Finds s = Finds.load(dir.resolve("finds.json"));
        assertTrue(s.applyOnce("e1", f("gtceu:barite", 0, 0, 5, 40, 41)));
        assertFalse(s.applyOnce("e1", f("gtceu:barite", 0, 0, 5, 40, 41)), "эхо того же события");
        s.markSeen("own");
        Finds t = Finds.load(dir.resolve("finds.json"));
        assertFalse(t.applyOnce("e1", f("gtceu:barite", 0, 0, 5, 40, 41)), "и после перезагрузки");
        assertFalse(t.applyOnce("own", f("gtceu:barite", 0, 0, 5, 40, 41)), "своё событие не вливается от команды");
        assertEquals(5, t.all().get(0).count);
        assertNotNull(t.all().get(0).id, "у жилы постоянный id");
        assertEquals(s.all().get(0).id, t.all().get(0).id);
    }
}
