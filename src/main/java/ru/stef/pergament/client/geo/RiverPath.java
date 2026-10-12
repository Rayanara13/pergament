package ru.stef.pergament.client.geo;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Русло реки для подписи «вдоль»: самый длинный из кратчайших путей по чанкам реки (два обхода в ширину: от любого
 * чанка — к самому дальнему, от него — к самому дальнему от него), сглаженный. У ветвистой реки это главный ствол,
 * у петлистой — её изгибы, а не прямая поперёк (как дала бы главная ось).
 */
public final class RiverPath {
    private RiverPath() {}

    /** Точки русла в блоках (центры чанков): x0, z0, x1, z1, … ; меньше двух точек — null. */
    public static int[] of(LongArrayList chunks) {
        if (chunks.size() < 2) return null;
        LongOpenHashSet set = new LongOpenHashSet(chunks);
        long a = far(set, chunks.getLong(0), null);
        Long2LongOpenHashMap prev = new Long2LongOpenHashMap();
        long b = far(set, a, prev);
        LongArrayList path = new LongArrayList();
        for (long k = b; ; k = prev.get(k)) {
            path.add(k);
            if (k == a) break;
        }
        if (path.size() < 2) return null;
        int n = path.size();
        double[] xs = new double[n], zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = Toponyms.kx(path.getLong(i)) * 16 + 8;
            zs[i] = Toponyms.kz(path.getLong(i)) * 16 + 8;
        }
        int[] out = new int[n * 2];
        for (int i = 0; i < n; i++) {                          // сглаживание: среднее по соседям — без ступенек 8-связности
            int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
            double sx = 0, sz = 0;
            for (int j = i0; j <= i1; j++) { sx += xs[j]; sz += zs[j]; }
            out[i * 2] = (int) Math.round(sx / (i1 - i0 + 1));
            out[i * 2 + 1] = (int) Math.round(sz / (i1 - i0 + 1));
        }
        if (out[0] > out[out.length - 2]) {                    // слева направо — чтобы текст не вверх ногами
            for (int i = 0, j = n - 1; i < j; i++, j--) {
                int tx = out[i * 2], tz = out[i * 2 + 1];
                out[i * 2] = out[j * 2];
                out[i * 2 + 1] = out[j * 2 + 1];
                out[j * 2] = tx;
                out[j * 2 + 1] = tz;
            }
        }
        return out;
    }

    /** Самый дальний (по шагам) чанк от start; prev — откуда пришли (для восстановления пути). */
    private static long far(LongOpenHashSet set, long start, Long2LongOpenHashMap prev) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(start);
        seen.add(start);
        long last = start;
        while (!q.isEmpty()) {
            long c = q.dequeueLong();
            last = c;
            int x = Toponyms.kx(c), z = Toponyms.kz(c);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    long n = Toponyms.key(x + dx, z + dz);
                    if (!set.contains(n) || !seen.add(n)) continue;
                    if (prev != null) prev.put(n, c);
                    q.enqueue(n);
                }
            }
        }
        return last;
    }
}
