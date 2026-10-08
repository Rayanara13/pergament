package ru.stef.pergament.client.map;

import ru.stef.pergament.store.RegionData;

/**
 * Пергамент поверх настоящих цветов блоков (по умолчанию 80%): доля цвета блока
 * с лёгким обесцвечиванием, остальное — бумага. Одинаково для всех блоков — стиль не подменяет цвет
 * отдельных классов. Сверху: отмывка рельефа по соседям (свет с северо-запада), вода — синей тушью
 * по глубине с береговой линией, суша — горизонтали через 10 блоков (каждая 50-я жирнее),
 * лава — своим цветом без тени (светится), стены в разрезе (Незер) — своим цветом, но темнее,
 * пустота Энда — тёмной тушью. Не разведано — прозрачно (видна чистая бумага).
 *
 * Чистая функция над массивами: зовётся в рабочем потоке, GL не трогает.
 */
public final class PaperStyle {
    static final int PR = 236, PG = 220, PB = 178;   // бумага
    static final int IR = 58, IG = 40, IB = 20;      // тушь
    public static final double DEFAULT_KEEP = 0.8;  // доля настоящего цвета блока
    static final double DESAT = 0.2, DESAT_AT = 0.6; // обесцвечивание 0.2 при 60%, сочнее — меньше (при 80% — 0.1)

    private PaperStyle() {}

    public static int[] compose(RegionData d) {
        return compose(d, DEFAULT_KEEP);
    }

    /** Пиксели региона в ABGR (формат NativeImage), построчно по z; keep — доля настоящего цвета (конфиг). */
    public static int[] compose(RegionData d, double keep) {
        final double desat = DESAT * (1 - keep) / (1 - DESAT_AT);       // сочнее — меньше обесцвечивания
        final int s = RegionData.SIZE;
        int[] out = new int[s * s];
        int[] col = d.color;
        short[] hgt = d.ground;                           // горизонтали — по земле, не по кронам
        byte[] wat = d.water, cls = d.cls, known = d.known;
        for (int z = 0, i = 0; z < s; z++) {
            for (int x = 0; x < s; x++, i++) {
                if (known[i] == 0) continue;                      // не разведано — прозрачно
                double o0, o1, o2;
                int wd = wat[i] & 0xFF;
                if (cls[i] == RegionData.CLS_VOID) {
                    o0 = mix(PR, IR, .7); o1 = mix(PG, IG, .7); o2 = mix(PB, IB, .7);
                } else if (cls[i] == RegionData.CLS_WALL) {
                    int c = col[i];                               // стена в разрезе (Незер): свой цвет, темнее, без горизонталей
                    double w = 0.62;
                    o0 = mix(PR, c & 0xFF, keep) * w; o1 = mix(PG, (c >>> 8) & 0xFF, keep) * w; o2 = mix(PB, (c >>> 16) & 0xFF, keep) * w;
                } else if (cls[i] == RegionData.CLS_LAVA) {
                    int c = col[i];                               // настоящая лава (её текстура), светится — без тени
                    o0 = mix(PR, c & 0xFF, keep); o1 = mix(PG, (c >>> 8) & 0xFF, keep); o2 = mix(PB, (c >>> 16) & 0xFF, keep);
                } else if (wd > 0) {
                    int c = col[i];                               // цвет самой воды (текстура × тинт биома)
                    double deep = 1 - Math.min(wd * 0.035, 0.38); // глубже — темнее
                    o0 = mix(PR, c & 0xFF, keep) * deep;
                    o1 = mix(PG, (c >>> 8) & 0xFF, keep) * deep;
                    o2 = mix(PB, (c >>> 16) & 0xFF, keep) * deep;
                    boolean shore = (x > 0 && dry(d, i - 1)) || (x < s - 1 && dry(d, i + 1))
                            || (z > 0 && dry(d, i - s)) || (z < s - 1 && dry(d, i + s));
                    if (shore) { o0 = mix(o0, IR, .55); o1 = mix(o1, IG, .55); o2 = mix(o2, IB, .55); }
                } else {
                    int c = col[i];
                    int r = c & 0xFF, g = (c >>> 8) & 0xFF, b = (c >>> 16) & 0xFF;
                    double gray = (r + g + b) / 3.0;
                    double dr = mix(r, gray, desat), dg = mix(g, gray, desat), db = mix(b, gray, desat);
                    double sh = shade(d, x, z, i);
                    o0 = mix(PR, dr, keep) * sh; o1 = mix(PG, dg, keep) * sh; o2 = mix(PB, db, keep) * sh;
                    int q = Math.floorDiv(hgt[i], 10);
                    int right = x < s - 1 && dry(d, i + 1) ? i + 1 : -1;
                    int down = z < s - 1 && dry(d, i + s) ? i + s : -1;
                    int other = right >= 0 && Math.floorDiv(hgt[right], 10) != q ? right
                            : down >= 0 && Math.floorDiv(hgt[down], 10) != q ? down : -1;
                    if (other >= 0) {
                        boolean major = Math.floorDiv(hgt[i], 50) != Math.floorDiv(hgt[other], 50);
                        double k = major ? .5 : .26;
                        o0 = mix(o0, IR, k); o1 = mix(o1, IG, k); o2 = mix(o2, IB, k);
                    }
                }
                out[i] = 0xFF000000 | (clamp(o2) << 16) | (clamp(o1) << 8) | clamp(o0);
            }
        }
        return out;
    }

    /** Отмывка: выше северо-западного соседа — светлее, ниже — темнее. Неизвестный сосед — без тени. */
    static double shade(RegionData d, int x, int z, int i) {
        final int s = RegionData.SIZE;
        if (x == 0 || z == 0) return 1;
        int j = i - s - 1;
        if (d.known[j] == 0) return 1;
        int diff = d.height[i] - d.height[j];
        return 1 + Math.max(-0.16, Math.min(0.12, diff * 0.05));
    }

    private static boolean dry(RegionData d, int j) {
        return d.known[j] != 0 && d.water[j] == 0 && d.cls[j] == RegionData.CLS_LAND;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
