package ru.stef.pergament.client.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Сетка шахтёра: границы клеток (и в минусе), 3×3, цикл отметок, сохранение, сброс при переносе точки. */
class MinerPlanTest {
    @Test
    void cellBordersAre33WideAroundOrigin() {
        assertEquals(0, MinerPlan.cell(1423, 1423 - 16), "−16 от центра — ещё своя клетка");
        assertEquals(0, MinerPlan.cell(1423, 1423 + 16), "+16 — ещё своя");
        assertEquals(1, MinerPlan.cell(1423, 1423 + 17), "+17 — соседняя");
        assertEquals(-1, MinerPlan.cell(1423, 1423 - 17), "−17 — соседняя слева");
        assertEquals(-1, MinerPlan.cell(-5, -5 - 17), "в отрицательных координатах так же (floorDiv)");
    }

    @Test
    void onlyNineCellsAreInReach() {
        MinerPlan p = new MinerPlan(Path.of("unused.json"));
        assertTrue(p.inReach(1, -1));
        assertFalse(p.inReach(2, 0));
    }

    @Test
    void cycleSaveLoadAndResetOnMove(@TempDir Path dir) {
        Path f = dir.resolve("miner.json");
        MinerPlan p = MinerPlan.load(f);
        assertFalse(p.hasOrigin());
        p.setOrigin(1423, 1308);
        p.cycle(0, 0);                      // в работе
        p.cycle(0, 0);                      // выкопано
        p.cycle(-1, 1);                     // в работе
        MinerPlan q = MinerPlan.load(f);
        assertEquals(1423, q.ox());
        assertEquals(MinerPlan.DONE, q.state(0, 0));
        assertEquals(MinerPlan.WORKING, q.state(-1, 1));
        q.cycle(0, 0);
        assertEquals(MinerPlan.NONE, MinerPlan.load(f).state(0, 0), "третий клик снимает отметку");
        q.setOrigin(100, 100);
        assertEquals(MinerPlan.NONE, MinerPlan.load(f).state(-1, 1), "новая точка — старые отметки не в счёт");
    }
}
