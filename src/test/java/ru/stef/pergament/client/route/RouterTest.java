package ru.stef.pergament.client.route;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Маршрутизатор: прямая по ровному, обход неразведанного, мост против долгого крюка, нет пути, гора. */
class RouterTest {
    static Router.Grid flat(int w, int h) {
        short[] hg = new short[w * h];
        Arrays.fill(hg, (short) 64);
        byte[] k = new byte[w * h];
        Arrays.fill(k, Router.LAND);
        return new Router.Grid(-10, -10, w, h, hg, k);
    }

    @Test
    void straightOnFlatGround() {
        var g = flat(40, 40);
        var r = Router.route(g, -10, 0, 20, 0);
        assertNotNull(r);
        assertEquals(31, r.length(), "30 шагов по прямой = 31 клетка");
        assertEquals(0, r.waterBlocks());
    }

    @Test
    void unknownIsAWall() {
        var g = flat(40, 40);
        for (int z = 0; z < 39; z++) g.kind()[z * 40 + 20] = Router.BLOCKED;     // стена с проходом внизу
        var r = Router.route(g, -5, -5, 25, -5);
        assertNotNull(r);
        for (int[] p : r.path()) assertNotEquals(Router.BLOCKED, g.kind()[g.idx(p[0], p[1])], "по неразведанному не идём");
        assertTrue(r.path().stream().anyMatch(p -> p[1] == 29), "обошли стену через единственный проход");
    }

    @Test
    void shortBridgeBeatsLongDetour() {
        var g = flat(200, 60);
        for (int z = 0; z < 59; z++) for (int x = 98; x < 101; x++) g.kind()[z * 200 + x] = Router.WATER; // речка 3 блока
        var r = Router.route(g, 0 - 10 + 50, 0, 150 - 10, 0);
        assertNotNull(r);
        assertEquals(3, r.waterBlocks(), "через речку мостом, а не в обход к дальнему броду");
    }

    @Test
    void noPathWhenWalledOff() {
        var g = flat(30, 30);
        for (int z = 0; z < 30; z++) g.kind()[z * 30 + 15] = Router.BLOCKED;
        assertNull(Router.route(g, -8, 0, 15, 0));
    }

    @Test
    void goesAroundAHillRatherThanOverIt() {
        var g = flat(60, 60);
        for (int z = 0; z < 50; z++) for (int x = 25; x < 35; x++) g.height()[z * 60 + x] = 90;  // крутой холм
        var r = Router.route(g, 0, 0, 40, 0);
        assertNotNull(r);
        assertEquals(0, r.climb(), "крутой подъём на 26 блоков дороже обхода по ровному");
    }
}
