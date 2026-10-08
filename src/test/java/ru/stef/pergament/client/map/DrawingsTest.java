package ru.stef.pergament.client.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Рисунки: сохранение, ластик по близости к отрезку (не только к точкам), одиночная точка не штрих. */
class DrawingsTest {
    static Drawings.Stroke stroke(int... xz) {
        Drawings.Stroke s = new Drawings.Stroke();
        s.pts = new ArrayList<>();
        for (int k = 0; k < xz.length; k += 2) s.pts.add(new int[]{xz[k], xz[k + 1]});
        return s;
    }

    @Test
    void addSaveLoad(@TempDir Path dir) {
        Path f = dir.resolve("drawings.json");
        Drawings d = Drawings.load(f);
        d.add(stroke(0, 0, 10, 0, 10, 10));
        d.add(stroke(5, 5));                       // одна точка — не штрих
        Drawings r = Drawings.load(f);
        assertEquals(1, r.all().size());
        assertEquals(3, r.all().get(0).pts.size());
        assertArrayEquals(new int[]{10, 10}, r.all().get(0).pts.get(2));
    }

    @Test
    void eraserHitsTheSegmentBetweenPoints(@TempDir Path dir) {
        Drawings d = Drawings.load(dir.resolve("drawings.json"));
        d.add(stroke(0, 0, 100, 0));               // длинный отрезок, точек посередине нет
        d.add(stroke(0, 50, 100, 50));
        assertEquals(0, d.eraseNear(50, 20, 3), "далеко от обоих — ничего");
        assertEquals(1, d.eraseNear(50, 1, 3), "середина первого отрезка — стёрт");
        assertEquals(1, d.all().size());
        assertEquals(50, d.all().get(0).pts.get(0)[1]);
    }

    @Test
    void segmentDistance() {
        assertEquals(5, Drawings.segDist(5, 5, new int[]{0, 0}, new int[]{10, 0}), 1e-9);
        assertEquals(5, Drawings.segDist(-3, 4, new int[]{0, 0}, new int[]{10, 0}), 1e-9, "за концом — до конца");
    }
}
