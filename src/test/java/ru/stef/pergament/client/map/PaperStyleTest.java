package ru.stef.pergament.client.map;

import org.junit.jupiter.api.Test;
import ru.stef.pergament.store.RegionData;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Пиннинг стиля: неразведанное прозрачно, серый остаётся серым (пол ангара из сланца),
 * вода синее суши и с берегом, горизонтали (и ниже нуля), лава, пустота Энда, отмывка.
 */
class PaperStyleTest {
    static final int S = RegionData.SIZE;
    static final int SLATE = 0xFF808A8E;          // ABGR: r=0x8E g=0x8A b=0x80 — серый сланец

    static int r(int abgr) { return abgr & 0xFF; }
    static int b(int abgr) { return (abgr >>> 16) & 0xFF; }
    static int lum(int abgr) { return r(abgr) + ((abgr >>> 8) & 0xFF) + b(abgr); }
    static int at(int x, int z) { return z * S + x; }

    /** Разведанный ровный участок 12×12 у угла региона. */
    static RegionData flat(int y, int color) {
        RegionData d = new RegionData(0, 0);
        for (int z = 0; z < 12; z++) for (int x = 0; x < 12; x++) d.set(x, z, color, y, 0, RegionData.CLS_LAND);
        return d;
    }

    @Test
    void unknownIsTransparent() {
        int[] out = PaperStyle.compose(flat(70, SLATE));
        assertEquals(0, out[at(20, 20)] >>> 24, "не разведано — бумага");
        assertNotEquals(0, out[at(5, 5)] >>> 24);
    }

    @Test
    void grayStaysGrayOnParchment() {
        int p = PaperStyle.compose(flat(70, SLATE))[at(5, 5)];
        assertTrue(r(p) - b(p) < 40, "тёплый сдвиг бумаги умеренный, не коричневый: r-b=" + (r(p) - b(p)));
    }

    @Test
    void waterIsBluerThanLandAndShoreIsInked() {
        RegionData d = flat(70, SLATE);
        int water = 0xFFE4763F;                    // ABGR: r=0x3F g=0x76 b=0xE4 — вода биома
        for (int z = 0; z < 12; z++) for (int x = 6; x < 12; x++) d.set(x, z, water, 62, 5, RegionData.CLS_LAND);
        int[] out = PaperStyle.compose(d);
        int land = out[at(2, 5)], shore = out[at(6, 5)], open = out[at(9, 5)];
        assertTrue(b(open) - r(open) > b(land) - r(land), "вода синее суши");
        assertTrue(lum(shore) < lum(open), "берег обведён тушью");
    }

    @Test
    void contourEvery10Blocks() {
        RegionData d = flat(68, SLATE);
        for (int z = 0; z < 12; z++) for (int x = 6; x < 12; x++) d.set(x, z, SLATE, 71, 0, RegionData.CLS_LAND);
        int[] out = PaperStyle.compose(d);
        assertTrue(lum(out[at(5, 5)]) < lum(out[at(3, 5)]), "на переходе через 70 — горизонталь");
    }

    @Test
    void contourIsCorrectBelowZero() {
        RegionData d = flat(-1, SLATE);
        for (int z = 0; z < 12; z++) for (int x = 6; x < 12; x++) d.set(x, z, SLATE, 1, 0, RegionData.CLS_LAND);
        int[] out = PaperStyle.compose(d);
        assertTrue(lum(out[at(5, 5)]) < lum(out[at(3, 5)]), "floorDiv: -1 и 1 в разных полосах");
    }

    @Test
    void sameBlockSameColorWhateverItIs() {
        RegionData d = flat(70, SLATE);
        int[] out = PaperStyle.compose(d);
        assertEquals(out[at(4, 4)], out[at(8, 8)], "ровный сланец — один цвет везде, никаких условных заливок");
    }

    @Test
    void deeperWaterIsDarker() {
        RegionData d = flat(70, SLATE);
        int water = 0xFFE4763F;
        d.set(3, 3, water, 62, 1, RegionData.CLS_LAND);
        d.set(8, 8, water, 50, 12, RegionData.CLS_LAND);
        int[] out = PaperStyle.compose(d);
        assertTrue(lum(out[at(8, 8)]) < lum(out[at(3, 3)]), "глубина темнее мели");
    }

    @Test
    void colorKeepMakesItRicher() {
        RegionData d = flat(70, 0xFF3C9B4A);       // трава: r=0x4A g=0x9B b=0x3C
        int c = PaperStyle.compose(d, 0.6)[at(5, 5)], v = PaperStyle.compose(d, 0.9)[at(5, 5)];
        int satC = ((c >>> 8) & 0xFF) - r(c), satV = ((v >>> 8) & 0xFF) - r(v);
        assertTrue(satV > satC, "больше настоящего цвета — зеленее");
    }

    @Test
    void netherWallsAreDarkerThanFloorOfSameBlock() {
        RegionData d = flat(64, 0xFF2A2A6E);       // незерак
        d.set(5, 5, 0xFF2A2A6E, 64, 0, RegionData.CLS_WALL);
        int[] out = PaperStyle.compose(d);
        assertTrue(lum(out[at(5, 5)]) < lum(out[at(8, 8)]) - 40, "стена в разрезе темнее пола");
    }

    @Test
    void lavaRedAndEndVoidDark() {
        RegionData d = flat(40, SLATE);
        d.set(3, 3, 0xFF0040FF, 40, 0, RegionData.CLS_LAVA);
        d.set(5, 5, 0xFF000000, -64, 0, RegionData.CLS_VOID);
        int[] out = PaperStyle.compose(d);
        assertTrue(r(out[at(3, 3)]) > b(out[at(3, 3)]) + 60, "лава красная");
        assertTrue(lum(out[at(5, 5)]) < lum(out[at(7, 7)]) - 150, "пустота тёмная, но не прозрачная");
        assertEquals(0xFF, out[at(5, 5)] >>> 24);
    }

    @Test
    void contoursFollowGroundNotCanopy() {
        RegionData d = flat(66, SLATE);
        for (int z = 0; z < 12; z++) for (int x = 0; x < 12; x++) d.set(x, z, SLATE, 66 + (x % 2) * 9, 66, 0, RegionData.CLS_LAND);
        int[] out = PaperStyle.compose(d);
        assertEquals(lum(out[at(3, 5)]), lum(out[at(5, 5)]), "кроны прыгают через 70, земля ровная — горизонталей нет");
    }

    @Test
    void reliefShadingFromNorthWest() {
        RegionData d = flat(70, SLATE);
        d.set(6, 6, SLATE, 72, 0, RegionData.CLS_LAND);     // бугорок: выше северо-западного соседа
        int[] out = PaperStyle.compose(d);
        assertTrue(lum(out[at(6, 6)]) > lum(out[at(4, 4)]), "склон к свету светлее");
    }
}
