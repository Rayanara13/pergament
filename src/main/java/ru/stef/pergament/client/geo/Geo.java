package ru.stef.pergament.client.geo;

/**
 * Местность чанка для подписей: категория, доля воды и вершина — одно число long на чанк.
 * Категорию подсказывает биом, а решает вода: «речной» биом без воды — берег, а не река;
 * чанк, наполовину залитый водой, — озеро, даже если биом об этом не знает.
 */
public final class Geo {
    public static final byte NONE = 0, OCEAN = 1, LAKE = 2, RIVER = 3, MOUNTAINS = 4, VOLCANIC = 5;

    private Geo() {}

    /** Итоговая категория чанка по подсказке биома и доле водных колонок (0..1). */
    public static byte classify(byte biome, float water) {
        return switch (biome) {
            case OCEAN -> water >= 0.4f ? OCEAN : NONE;
            case LAKE -> water >= 0.4f ? LAKE : NONE;
            case RIVER -> water >= 0.15f ? RIVER : NONE;
            case MOUNTAINS, VOLCANIC -> water >= 0.6f ? LAKE : biome;    // горное озеро — озеро
            default -> water >= 0.6f ? LAKE : NONE;                        // озеро без своего биома (ванилла, моды)
        };
    }

    /** cat — 8 бит, вода — 8 бит (0..255), высота вершины — short со знаком, место вершины в чанке — lx<<4|lz. */
    public static long pack(byte cat, float water, int peakY, int peakLx, int peakLz) {
        long w = Math.max(0, Math.min(255, Math.round(water * 255)));
        return (cat & 0xFFL) | w << 8 | ((long) (short) peakY & 0xFFFFL) << 16 | (long) ((peakLx & 15) << 4 | (peakLz & 15)) << 32;
    }

    public static byte cat(long v) { return (byte) (v & 0xFF); }
    public static float water(long v) { return ((v >>> 8) & 0xFF) / 255f; }
    public static int peakY(long v) { return (short) ((v >>> 16) & 0xFFFF); }
    public static int peakLx(long v) { return (int) ((v >>> 36) & 15); }
    public static int peakLz(long v) { return (int) ((v >>> 32) & 15); }
}
