package ru.stef.pergament.client.route;

import ru.stef.pergament.client.T;

import net.minecraft.client.Minecraft;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.store.RegionData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Тропа через точки A → (через…) → B по разведанному. Точки ставит игрок, маршрут пересчитывается
 * сам в фоне по снимку регионов (снимок — на клиентском потоке, A* — в фоне). Одна на мир/измерение
 * сессии; её же рисует миникарта. Публичное — клиентский поток.
 */
public final class RoutePlan {
    public enum Status { EMPTY, PICKING, LOADING, BUILDING, READY, NO_PATH, TOO_FAR }

    public static final int PAD = 96, MAX_SIDE = 2048;
    private static final RoutePlan INSTANCE = new RoutePlan();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Pergament-Route");
        t.setDaemon(true);
        return t;
    });

    private final List<int[]> points = new ArrayList<>();
    private List<int[]> path = List.of();
    private Status status = Status.EMPTY;
    private String stats = "";
    private int generation;
    private boolean dirty;
    private String session;

    private RoutePlan() {}

    public static RoutePlan get() { return INSTANCE; }

    public Status status() { return status; }
    public List<int[]> points() { return points; }
    public List<int[]> path() { return path; }
    public String stats() { return stats; }

    public void add(int x, int z) {
        points.add(new int[]{x, z});
        changed();
    }

    public void undo() {
        if (!points.isEmpty()) points.remove(points.size() - 1);
        changed();
    }

    public void clear() {
        points.clear();
        changed();
    }

    private void changed() {
        generation++;
        path = List.of();
        stats = "";
        status = points.isEmpty() ? Status.EMPTY : points.size() == 1 ? Status.PICKING : Status.LOADING;
        dirty = points.size() >= 2;
    }

    /** Клиентский тик: сменился мир/измерение — тропа не отсюда; есть что считать — снимок и в фон. */
    public void tick() {
        LocalMap map = LocalMap.get();
        String here = map.profile() + "|" + map.shownDimension();
        if (!here.equals(session)) {
            session = here;
            points.clear();
            changed();
        }
        if (!dirty || map.profile() == null) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] p : points) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
        }
        minX -= PAD; minZ -= PAD; maxX += PAD; maxZ += PAD;
        if (maxX - minX > MAX_SIDE || maxZ - minZ > MAX_SIDE) {
            dirty = false;
            status = Status.TOO_FAR;
            return;
        }
        // все регионы прямоугольника — в памяти? нет — заказываем и ждём следующего тика
        boolean waiting = false;
        for (int rz = RegionData.regionOf(minZ); rz <= RegionData.regionOf(maxZ); rz++) {
            for (int rx = RegionData.regionOf(minX); rx <= RegionData.regionOf(maxX); rx++) {
                if (map.region(rx, rz, false) == null && map.isLoading(rx, rz)) waiting = true;
            }
        }
        if (waiting) {
            status = Status.LOADING;
            return;
        }
        dirty = false;
        Router.Grid grid = snapshot(map, minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
        List<int[]> pts = new ArrayList<>(points);
        int gen = generation;
        status = Status.BUILDING;
        POOL.execute(() -> {
            List<int[]> full = new ArrayList<>();
            int len = 0, water = 0, climb = 0;
            boolean ok = true;
            for (int k = 1; k < pts.size() && ok; k++) {
                Router.Result r = Router.route(grid, pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1]);
                if (r == null) {
                    ok = false;
                } else {
                    full.addAll(k == 1 ? r.path() : r.path().subList(1, r.path().size()));
                    len += r.length() - 1;
                    water += r.waterBlocks();
                    climb += r.climb();
                }
            }
            boolean found = ok;
            String st = T.t("route.stats", len) + (water > 0 ? T.t("route.water", water) : "") + (climb > 0 ? T.t("route.climb", climb) : "");
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) return;
                path = found ? full : List.of();
                stats = found ? st : "";
                status = found ? Status.READY : Status.NO_PATH;
            });
        });
    }

    /** Снимок разведанного: суша/вода/непроходимо и высоты. Неразведанное — непроходимо. */
    static Router.Grid snapshot(LocalMap map, int x0, int z0, int w, int h) {
        short[] height = new short[w * h];
        byte[] kind = new byte[w * h];
        for (int z = 0; z < h; z++) {
            int wz = z0 + z;
            for (int x = 0; x < w; x++) {
                int wx = x0 + x;
                RegionData r = map.region(RegionData.regionOf(wx), RegionData.regionOf(wz), false);
                if (r == null) continue;
                int i = ((wz & (RegionData.SIZE - 1)) << RegionData.SHIFT) | (wx & (RegionData.SIZE - 1));
                if (r.known[i] == 0 || r.cls[i] != RegionData.CLS_LAND) continue;
                int j = z * w + x;
                height[j] = r.ground[i];                             // идём по земле, не по кронам
                kind[j] = r.water[i] != 0 ? Router.WATER : Router.LAND;
            }
        }
        return new Router.Grid(x0, z0, w, h, height, kind);
    }
}
