package ru.stef.pergament.client.geo;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Объекты местности и их имена. Из категорий чанков — области связности (8 соседей, без «мостов» через
 * неразведанное): озеро, река, море, хребет; вулкан — заметная вершина вулканических гор. Имя выдаётся объекту
 * один раз и держится за «якорь» — чанк объекта: при пересчёте область наследует имя записи, чей якорь в ней лежит;
 * слились две — остаётся старшая по счётчику (не по часам). Чистая логика — без Minecraft, под тесты.
 */
public final class Toponyms {
    public static final String LAKE = "lake", RIVER = "river", SEA = "sea", RANGE = "range", VOLCANO = "volcano";
    /** Меньше — не объект (лужа, ручей, холм). Озеро больше LAKE_TO_SEA чанков — море. */
    static final int MIN_LAKE = 3, MIN_RIVER = 4, MIN_RANGE = 12, MIN_SEA = 40, LAKE_TO_SEA = 80;
    /** Вулкан: вершина выше всех вулканических соседей в радиусе R и выше самого низкого из них на PROMINENCE. */
    static final int VOLCANO_R = 3, PROMINENCE = 20;

    /** Выданное имя: тип, ссылка на имя, запасная строка, якорь, порядковый номер. */
    public static final class Item {
        public String type;
        public String nameId;
        public String name;
        public long anchor;
        public long seq;
    }

    /** Реестр имён измерения (toponyms.json): счётчик и записи. */
    public static final class Registry {
        public long seq;
        public List<Item> items = new ArrayList<>();
    }

    /** Подпись на карте: имя, место (центр блока), размер объекта в блоках — для «влезает ли подпись». */
    public record Label(String type, String nameId, String fallback, int x, int z, int sizeBlocks, int chunks) {}

    private Toponyms() {}

    public static long key(int cx, int cz) { return (long) cx & 0xFFFFFFFFL | (long) cz << 32; }
    public static int kx(long k) { return (int) k; }
    public static int kz(long k) { return (int) (k >>> 32); }

    private static String group(byte cat) {
        return switch (cat) {
            case Geo.OCEAN -> SEA;
            case Geo.LAKE -> LAKE;
            case Geo.RIVER -> RIVER;
            case Geo.MOUNTAINS, Geo.VOLCANIC -> RANGE;
            default -> null;
        };
    }

    private static boolean compatible(String a, String b) {
        return a.equals(b) || (a.equals(LAKE) || a.equals(SEA)) && (b.equals(LAKE) || b.equals(SEA));
    }

    /**
     * Пересчитать объекты и подписи. Реестр дополняется новыми именами (вызывающий сохраняет его, если вернулось
     * changed). salt — профиль мира и измерение: одно и то же место в разных мирах получит разные имена.
     */
    public static Result compute(Long2LongMap cells, Registry reg, String salt) {
        boolean changed = false;
        Set<String> used = new HashSet<>();
        for (Item it : reg.items) used.add(it.nameId);
        List<Label> labels = new ArrayList<>();

        // ---- области связности
        Long2IntOpenHashMap comp = new Long2IntOpenHashMap();
        comp.defaultReturnValue(-1);
        List<LongArrayList> comps = new ArrayList<>();
        List<String> types = new ArrayList<>();
        LongArrayList stack = new LongArrayList();
        for (var e : cells.long2LongEntrySet()) {
            long k = e.getLongKey();
            String g = group(Geo.cat(e.getLongValue()));
            if (g == null || comp.get(k) >= 0) continue;
            int id = comps.size();
            LongArrayList members = new LongArrayList();
            comp.put(k, id);
            stack.add(k);
            while (!stack.isEmpty()) {
                long c = stack.removeLong(stack.size() - 1);
                members.add(c);
                int x = kx(c), z = kz(c);
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        long n = key(x + dx, z + dz);
                        if (comp.get(n) >= 0 || !cells.containsKey(n) || !g.equals(group(Geo.cat(cells.get(n))))) continue;
                        comp.put(n, id);
                        stack.add(n);
                    }
                }
            }
            comps.add(members);
            types.add(g.equals(LAKE) && members.size() > LAKE_TO_SEA ? SEA : g);
        }

        // ---- имена: старшая запись реестра, чей якорь в области
        Item[] owner = new Item[comps.size()];
        for (Item it : reg.items) {
            if (it.type.equals(VOLCANO)) continue;
            int c = comp.get(it.anchor);
            if (c < 0 || !compatible(it.type, types.get(c))) continue;
            if (owner[c] == null || it.seq < owner[c].seq) owner[c] = it;
        }
        for (int c = 0; c < comps.size(); c++) {
            LongArrayList m = comps.get(c);
            String type = types.get(c);
            int min = switch (type) {
                case LAKE -> MIN_LAKE;
                case RIVER -> MIN_RIVER;
                case RANGE -> MIN_RANGE;
                default -> MIN_SEA;
            };
            if (owner[c] == null && m.size() < min) continue;
            double sx = 0, sz = 0;
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < m.size(); i++) {
                long k = m.getLong(i);
                sx += kx(k);
                sz += kz(k);
                x0 = Math.min(x0, kx(k)); x1 = Math.max(x1, kx(k)); z0 = Math.min(z0, kz(k)); z1 = Math.max(z1, kz(k));
            }
            sx /= m.size();
            sz /= m.size();
            long center = m.getLong(0);                          // чанк области, ближайший к её центру (у подковы центр — снаружи)
            double best = Double.MAX_VALUE;
            for (int i = 0; i < m.size(); i++) {
                long k = m.getLong(i);
                double d = (kx(k) - sx) * (kx(k) - sx) + (kz(k) - sz) * (kz(k) - sz);
                if (d < best || d == best && k < center) { best = d; center = k; }
            }
            Item it = owner[c];
            if (it == null) {
                it = newItem(reg, type, center, salt, used);
                changed = true;
            }
            int size = Math.max(x1 - x0, z1 - z0) * 16 + 16;
            labels.add(new Label(type, it.nameId, it.name, kx(center) * 16 + 8, kz(center) * 16 + 8, size, m.size()));
        }

        // ---- вулканы
        for (var e : cells.long2LongEntrySet()) {
            long v = e.getLongValue();
            if (Geo.cat(v) != Geo.VOLCANIC) continue;
            long k = e.getLongKey();
            int x = kx(k), z = kz(k), y = Geo.peakY(v), low = y;
            boolean top = true;
            for (int dz = -VOLCANO_R; dz <= VOLCANO_R && top; dz++) {
                for (int dx = -VOLCANO_R; dx <= VOLCANO_R; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    long n = key(x + dx, z + dz);
                    if (!cells.containsKey(n)) continue;
                    long nv = cells.get(n);
                    int ny = Geo.peakY(nv);
                    low = Math.min(low, ny);
                    if (Geo.cat(nv) == Geo.VOLCANIC && (ny > y || ny == y && n < k)) { top = false; break; }
                }
            }
            if (!top || y - low < PROMINENCE) continue;
            Item it = null;
            for (Item r : reg.items) {
                if (!r.type.equals(VOLCANO)) continue;
                if (Math.max(Math.abs(kx(r.anchor) - x), Math.abs(kz(r.anchor) - z)) > VOLCANO_R) continue;
                if (it == null || r.seq < it.seq) it = r;
            }
            if (it == null) {
                it = newItem(reg, VOLCANO, k, salt, used);
                changed = true;
            }
            labels.add(new Label(VOLCANO, it.nameId, it.name, x * 16 + Geo.peakLx(v), z * 16 + Geo.peakLz(v), 16, 1));
        }
        return new Result(labels, changed);
    }

    public record Result(List<Label> labels, boolean changed) {}

    private static Item newItem(Registry reg, String type, long anchor, String salt, Set<String> used) {
        Item it = new Item();
        it.type = type;
        it.anchor = anchor;
        it.seq = ++reg.seq;
        long h = salt.hashCode() * 0x9E3779B97F4A7C15L ^ anchor * 0xC2B2AE3D27D4EB4FL ^ type.hashCode();
        h ^= h >>> 29;
        it.nameId = Names.pick(type, h, used);
        it.name = Names.display(it.nameId, "en", it.nameId);
        used.add(it.nameId);
        reg.items.add(it);
        return it;
    }
}
