package ru.stef.pergament.client.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Обзор: 8 чанков по взгляду, 4 за спиной, ногами всегда. Yaw 0 — смотрим на юг (+z). */
class RevealTest {
    @Test
    void reachesFrontRadiusAlongLook() {
        assertTrue(Reveal.inside(0, 8, 0f, 8, 4), "8 чанков прямо по взгляду");
        assertFalse(Reveal.inside(0, 10, 0f, 8, 4), "дальше прогрузки — нет");
    }

    @Test
    void behindIsShorter() {
        assertTrue(Reveal.inside(0, -4, 0f, 8, 4), "4 чанка за спиной");
        assertFalse(Reveal.inside(0, -7, 0f, 8, 4), "7 за спиной — уже не видно");
    }

    @Test
    void turningHeadMovesTheCone() {
        assertTrue(Reveal.inside(-8, 0, 90f, 8, 4), "yaw 90 — смотрим на запад");
        assertFalse(Reveal.inside(8, 0, 90f, 8, 4), "восток за спиной");
    }

    @Test
    void underFeetAlwaysVisible() {
        assertTrue(Reveal.inside(0, 0, 0f, 0, 0));
        assertTrue(Reveal.inside(1, -1, 0f, 0, 0));
    }
}
