package ru.stef.pergament.client.geo;

/**
 * Местность чанка для подписей: категория, доли воды, кроны и песка, вершина — одно число long на чанк.
 * Категорию подсказывает биом, а решают признаки: «речной» биом без воды — берег, а не река; чанк, наполовину
 * залитый водой, — озеро, даже если биом об этом не знает; сплошная крона — лес; песок без растительности — пустыня.
 */
public final class Geo {
    /** Категории чанка (и подсказки биома с теми же номерами). */
    public static final byte NONE = 0, OCEAN = 1, LAKE = 2, RIVER = 3, MOUNTAINS = 4, VOLCANIC = 5;
    /** Только подсказки биома: заболоченное (TFC lowlands, salt marsh, ванильные болота) и пляж. */
    public static final byte H_SWAMP = 6, H_BEACH = 7;
    /** Только категории: лес, пустыня, болото. */
    public static final byte FOREST = 8, DESERT = 9, SWAMP = 10;

    private Geo() {}

    /** Итоговая категория чанка по подсказке биома и долям (0..1) воды, кроны и песка. */
    public static byte classify(byte biome, float water, float canopy, float sand) {
        return switch (biome) {
            case OCEAN -> water >= 0.4f ? OCEAN : NONE;
            case LAKE -> water >= 0.4f ? LAKE : NONE;
            case RIVER -> water >= 0.15f ? RIVER : NONE;
            case MOUNTAINS, VOLCANIC -> water >= 0.6f ? LAKE : biome;    // горное озеро — озеро
            case H_SWAMP -> water >= 0.6f ? LAKE : water >= 0.1f || canopy < 0.6f ? SWAMP : FOREST;
            default -> water >= 0.6f ? LAKE                              // озеро без своего биома (ванилла, моды)
                    : canopy >= 0.6f ? FOREST
                    : biome != H_BEACH && sand >= 0.6f && canopy < 0.1f && water < 0.2f ? DESERT
                    : NONE;
        };
    }

    /** Старый вызов (без кроны и песка). */
    public static byte classify(byte biome, float water) {
        return classify(biome, water, 0, 0);
    }

    /**
     * cat — 8 бит, вода — 8 (0..255), высота вершины — short со знаком, место вершины в чанке — lx<<4|lz (8 бит),
     * крона — 8, песок — 8.
     */
    public static long pack(byte cat, float water, int peakY, int peakLx, int peakLz, float canopy, float sand) {
        return (cat & 0xFFL) | frac(water) << 8 | ((long) (short) peakY & 0xFFFFL) << 16
                | (long) ((peakLx & 15) << 4 | (peakLz & 15)) << 32 | frac(canopy) << 40 | frac(sand) << 48;
    }

    public static long pack(byte cat, float water, int peakY, int peakLx, int peakLz) {
        return pack(cat, water, peakY, peakLx, peakLz, 0, 0);
    }

    private static long frac(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }

    public static byte cat(long v) { return (byte) (v & 0xFF); }
    public static float water(long v) { return ((v >>> 8) & 0xFF) / 255f; }
    public static int peakY(long v) { return (short) ((v >>> 16) & 0xFFFF); }
    public static int peakLx(long v) { return (int) ((v >>> 36) & 15); }
    public static int peakLz(long v) { return (int) ((v >>> 32) & 15); }
    public static float canopy(long v) { return ((v >>> 40) & 0xFF) / 255f; }
    public static float sand(long v) { return ((v >>> 48) & 0xFF) / 255f; }

    /** Вода — для островов: море, озеро (и огромное озеро-море). */
    public static boolean openWater(byte cat) {
        return cat == OCEAN || cat == LAKE;
    }
}
