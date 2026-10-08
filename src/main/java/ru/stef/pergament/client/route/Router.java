package ru.stef.pergament.client.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A* по сетке разведанного (1 клетка = 1 блок, 8 соседей). Чистая функция над снимком — фоновый поток,
 * без мира и GL. Неразведанное, стены, лава и пустота — непроходимы; вода — проходима, но дорого
 * (мост/вплавь); подъём стоит дороже ровного, уступ выше блока — сильно дороже (надо строить ступени).
 */
public final class Router {
    /** Клетка снимка: высота и флаги. */
    public static final byte BLOCKED = 0, LAND = 1, WATER = 2;

    public static final double WATER_COST = 6, CLIMB_COST = 3, STEP_COST = 25;

    /** Снимок прямоугольника мира: x0, z0 — угол, w×h клеток. */
    public record Grid(int x0, int z0, int w, int h, short[] height, byte[] kind) {
        int idx(int x, int z) { return (z - z0) * w + (x - x0); }
        boolean inside(int x, int z) { return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h; }
    }

    public record Result(List<int[]> path, double cost, int length, int waterBlocks, int climb) {}

    private Router() {}

    /** Маршрут от (ax, az) до (bx, bz) или null, если пути по разведанному нет. */
    public static Result route(Grid g, int ax, int az, int bx, int bz) {
        if (!g.inside(ax, az) || !g.inside(bx, bz)) return null;
        int start = g.idx(ax, az), goal = g.idx(bx, bz);
        if (g.kind[start] == BLOCKED || g.kind[goal] == BLOCKED) return null;
        int n = g.w * g.h;
        float[] cost = new float[n];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        int[] from = new int[n];
        boolean[] closed = new boolean[n];
        Heap open = new Heap(1 << 12);
        cost[start] = 0;
        from[start] = -1;
        open.push(start, heuristic(g, start, goal));
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dz = {0, 0, 1, -1, 1, -1, 1, -1};
        while (open.size > 0) {
            int cur = open.pop();
            if (cur == goal) return build(g, from, cost[goal], goal);
            if (closed[cur]) continue;
            closed[cur] = true;
            int cx = cur % g.w, cz = cur / g.w;
            for (int k = 0; k < 8; k++) {
                int nx = cx + dx[k], nz = cz + dz[k];
                if (nx < 0 || nz < 0 || nx >= g.w || nz >= g.h) continue;
                int nb = nz * g.w + nx;
                if (closed[nb] || g.kind[nb] == BLOCKED) continue;
                if (k >= 4 && (g.kind[cz * g.w + nx] == BLOCKED || g.kind[nz * g.w + cx] == BLOCKED)) continue; // не срезаем углы стен
                float c = cost[cur] + (float) step(g, cur, nb, k >= 4);
                if (c < cost[nb]) {
                    cost[nb] = c;
                    from[nb] = cur;
                    open.push(nb, c + heuristic(g, nb, goal));
                }
            }
        }
        return null;
    }

    static double step(Grid g, int a, int b, boolean diag) {
        double base = diag ? Math.sqrt(2) : 1;
        if (g.kind[b] == WATER) return base * WATER_COST;
        int dh = Math.abs(g.height[b] - g.height[a]);
        double c = base + dh * CLIMB_COST;
        if (dh > 1) c += (dh - 1) * STEP_COST;
        return c;
    }

    /** Октильное расстояние — допустимая оценка (никогда не завышает: дешевле ровной суши шага нет). */
    static float heuristic(Grid g, int a, int b) {
        int ax = a % g.w, az = a / g.w, bx = b % g.w, bz = b / g.w;
        int dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return (float) (Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz));
    }

    private static Result build(Grid g, int[] from, double total, int goal) {
        List<int[]> path = new ArrayList<>();
        int water = 0, climb = 0, prev = -1;
        for (int c = goal; c != -1; c = from[c]) {
            path.add(new int[]{g.x0 + c % g.w, g.z0 + c / g.w});
            if (g.kind[c] == WATER) water++;
            if (prev != -1) climb += Math.max(0, g.height[prev] - g.height[c]);   // идём назад: подъём — это спуск по ходу назад
            prev = c;
        }
        java.util.Collections.reverse(path);
        return new Result(path, total, path.size(), water, climb);
    }

    /** Бинарная куча по приоритету (повторы допустимы — отсекаются через closed). */
    private static final class Heap {
        int[] node;
        float[] pri;
        int size;

        Heap(int cap) {
            node = new int[cap];
            pri = new float[cap];
        }

        void push(int n, float p) {
            if (size == node.length) {
                node = Arrays.copyOf(node, size * 2);
                pri = Arrays.copyOf(pri, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (pri[parent] <= p) break;
                node[i] = node[parent];
                pri[i] = pri[parent];
                i = parent;
            }
            node[i] = n;
            pri[i] = p;
        }

        int pop() {
            int top = node[0];
            int n = node[--size];
            float p = pri[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int r = l + 1, m = r < size && pri[r] < pri[l] ? r : l;
                if (pri[m] >= p) break;
                node[i] = node[m];
                pri[i] = pri[m];
                i = m;
            }
            node[i] = n;
            pri[i] = p;
            return top;
        }
    }
}
