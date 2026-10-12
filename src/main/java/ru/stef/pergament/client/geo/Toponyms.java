package ru.stef.pergament.client.geo;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Объекты местности и их имена. Из категорий чанков — области связности (8 соседей, без «мостов» через
 * неразведанное): озеро, река, море, хребет, лес, пустыня, болото; остров — суша, со всех сторон окружённая разведанной
 * водой; вулкан и вершина — заметные пики. Имя выдаётся объекту один раз и держится за «якорь» — чанк объекта:
 * при пересчёте область наследует имя записи, чей якорь в ней лежит; слились две — остаётся старшая (командный
 * порядок, затем свой счётчик — не часы). Чистая логика — без Minecraft, под тесты.
 */
public final class Toponyms {
    public static final String LAKE = "lake", RIVER = "river", SEA = "sea", RANGE = "range", VOLCANO = "volcano",
            FOREST = "forest", DESERT = "desert", SWAMP = "swamp", ISLAND = "island", PEAK = "peak";
    /** Меньше — не объект (лужа, ручей, холм, роща). Озеро больше LAKE_TO_SEA чанков — море. */
    static final int MIN_LAKE = 3, MIN_RIVER = 4, MIN_RANGE = 12, MIN_SEA = 40, LAKE_TO_SEA = 80,
            MIN_FOREST = 20, MIN_DESERT = 16, MIN_SWAMP = 6, MIN_ISLAND = 2, MAX_ISLAND = 3000;
    /** Вулкан: выше всех вулканических соседей в радиусе VOLCANO_R и выше самого низкого из них на PROMINENCE. */
    static final int VOLCANO_R = 3, PROMINENCE = 20;
    /** Вершина хребта: выше всех горных соседей в радиусе PEAK_R, над самым низким — PEAK_PROMINENCE, не ниже PEAK_MIN_Y. */
    static final int PEAK_R = 4, PEAK_PROMINENCE = 30, PEAK_MIN_Y = 120;

    /** Выданное имя: тип, ссылка на имя, запасная строка, якорь, порядковый номер. */
    public static final class Item {
        /** Постоянный id записи (по нему переименовывают и делятся с командой). */
        public String id;
        public String type;
        public String nameId;
        public String name;
        public long anchor;
        public long seq;
        /** Своё имя игрока (переименовал); null — выданное. */
        public String custom;
        /** Порядок в команде (сервер): кто открыл раньше. 0 — команде ещё не известно. */
        public long teamSeq;
    }

    /** Старшинство записи: командный порядок важнее своего (своё, ещё не отданное команде, — после всех командных). */
    static long rank(Item it) {
        return it.teamSeq > 0 ? it.teamSeq : (1L << 40) + it.seq;
    }

    /** Реестр имён измерения (toponyms.json): счётчик и записи. */
    public static final class Registry {
        public long seq;
        public List<Item> items = new ArrayList<>();
    }

    /**
     * Подпись на карте: запись, имя, место (центр блока), размер объекта в блоках — для «влезает ли подпись»;
     * path — русло реки (центры чанков, в блоках: x0,z0,x1,z1…) или null.
     */
    public record Label(String itemId, String type, String nameId, String fallback, String custom, int x, int z,
                        int sizeBlocks, int chunks, int[] path) {}

    public record Result(List<Label> labels, boolean changed) {}

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
            case Geo.FOREST -> FOREST;
            case Geo.DESERT -> DESERT;
            case Geo.SWAMP -> SWAMP;
            default -> null;
        };
    }

    private static int min(String type) {
        return switch (type) {
            case LAKE -> MIN_LAKE;
            case RIVER -> MIN_RIVER;
            case RANGE -> MIN_RANGE;
            case FOREST -> MIN_FOREST;
            case DESERT -> MIN_DESERT;
            case SWAMP -> MIN_SWAMP;
            case ISLAND -> MIN_ISLAND;
            default -> MIN_SEA;
        };
    }

    private static boolean compatible(String a, String b) {
        return a.equals(b) || (a.equals(LAKE) || a.equals(SEA)) && (b.equals(LAKE) || b.equals(SEA));
    }

    /** Области связности; comp — номер области у каждого чанка. */
    private record Areas(List<LongArrayList> members, List<String> types, Long2IntOpenHashMap comp) {}

    /**
     * Пересчитать объекты и подписи. Реестр дополняется новыми именами (вызывающий сохраняет его, если вернулось
     * changed). salt — профиль мира и измерение: одно и то же место в разных мирах получит разные имена.
     */
    public static Result compute(Long2LongMap cells, Registry reg, String salt) {
        boolean[] changed = {false};
        Set<String> used = new HashSet<>();
        for (Item it : reg.items) {
            used.add(it.nameId);
            if (it.id == null) {                                        // записи старого формата — дать id
                it.id = java.util.UUID.randomUUID().toString();
                changed[0] = true;
            }
        }
        List<Label> labels = new ArrayList<>();

        // ---- озёра, реки, моря, хребты, леса, пустыни, болота
        Areas a = areas(cells, k -> true, k -> group(Geo.cat(cells.get(k))));
        for (int c = 0; c < a.members.size(); c++) {
            String g = a.types.get(c);
            if (g.equals(LAKE) && a.members.get(c).size() > LAKE_TO_SEA) a.types.set(c, SEA);
        }
        name(a, reg, salt, used, labels, changed);

        // ---- острова: суша, у которой все соседи разведаны и все чужие соседи — открытая вода
        LongPredicate land = k -> !Geo.openWater(Geo.cat(cells.get(k)));
        Areas isl = areas(cells, land, k -> ISLAND);
        List<LongArrayList> keep = new ArrayList<>();
        List<String> keepTypes = new ArrayList<>();
        Long2IntOpenHashMap keepComp = new Long2IntOpenHashMap();
        keepComp.defaultReturnValue(-1);
        for (int c = 0; c < isl.members.size(); c++) {
            LongArrayList m = isl.members.get(c);
            if (m.size() > MAX_ISLAND || !enclosed(cells, m, isl.comp, c)) continue;
            for (int i = 0; i < m.size(); i++) keepComp.put(m.getLong(i), keep.size());
            keep.add(m);
            keepTypes.add(ISLAND);
        }
        name(new Areas(keep, keepTypes, keepComp), reg, salt, used, labels, changed);

        // ---- вулканы и вершины
        peaks(cells, reg, salt, used, labels, changed, VOLCANO, Geo.VOLCANIC, VOLCANO_R, PROMINENCE, Integer.MIN_VALUE);
        peaks(cells, reg, salt, used, labels, changed, PEAK, Geo.MOUNTAINS, PEAK_R, PEAK_PROMINENCE, PEAK_MIN_Y);
        return new Result(labels, changed[0]);
    }

    /** Области связности чанков, где type(k) не null и одинаков; inSet — какие чанки вообще рассматривать. */
    private static Areas areas(Long2LongMap cells, LongPredicate inSet, java.util.function.LongFunction<String> type) {
        Long2IntOpenHashMap comp = new Long2IntOpenHashMap();
        comp.defaultReturnValue(-1);
        List<LongArrayList> comps = new ArrayList<>();
        List<String> types = new ArrayList<>();
        LongArrayList stack = new LongArrayList();
        for (var e : cells.long2LongEntrySet()) {
            long k = e.getLongKey();
            if (comp.get(k) >= 0 || !inSet.test(k)) continue;
            String g = type.apply(k);
            if (g == null) continue;
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
                        if (comp.get(n) >= 0 || !cells.containsKey(n) || !inSet.test(n) || !g.equals(type.apply(n))) continue;
                        comp.put(n, id);
                        stack.add(n);
                    }
                }
            }
            comps.add(members);
            types.add(g);
        }
        return new Areas(comps, types, comp);
    }

    /** Остров: все соседи чанков области разведаны, а чужие — открытая вода. */
    private static boolean enclosed(Long2LongMap cells, LongArrayList m, Long2IntOpenHashMap comp, int c) {
        for (int i = 0; i < m.size(); i++) {
            long k = m.getLong(i);
            int x = kx(k), z = kz(k);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long n = key(x + dx, z + dz);
                    if (comp.get(n) == c) continue;
                    if (!cells.containsKey(n) || !Geo.openWater(Geo.cat(cells.get(n)))) return false;
                }
            }
        }
        return true;
    }

    /** Имена областям: старшая запись реестра, чей якорь в области; нет и область крупная — новая. Плюс подписи. */
    private static void name(Areas a, Registry reg, String salt, Set<String> used, List<Label> labels, boolean[] changed) {
        Item[] owner = new Item[a.members.size()];
        for (Item it : reg.items) {
            if (it.type.equals(VOLCANO) || it.type.equals(PEAK)) continue;
            int c = a.comp.get(it.anchor);
            if (c < 0 || !compatible(it.type, a.types.get(c))) continue;
            if (owner[c] == null || rank(it) < rank(owner[c])) owner[c] = it;
        }
        for (int c = 0; c < a.members.size(); c++) {
            LongArrayList m = a.members.get(c);
            String type = a.types.get(c);
            if (owner[c] == null && m.size() < min(type)) continue;
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
                changed[0] = true;
            }
            int size = Math.max(x1 - x0, z1 - z0) * 16 + 16;
            int[] path = type.equals(RIVER) ? RiverPath.of(m) : null;
            labels.add(new Label(it.id, type, it.nameId, it.name, it.custom, kx(center) * 16 + 8, kz(center) * 16 + 8,
                    size, m.size(), path));
        }
    }

    /** Заметные пики чанков категории cat: вулканы (VOLCANIC) или вершины хребтов (MOUNTAINS). */
    private static void peaks(Long2LongMap cells, Registry reg, String salt, Set<String> used, List<Label> labels,
                              boolean[] changed, String type, byte cat, int r, int prominence, int minY) {
        for (var e : cells.long2LongEntrySet()) {
            long v = e.getLongValue();
            if (Geo.cat(v) != cat) continue;
            long k = e.getLongKey();
            int x = kx(k), z = kz(k), y = Geo.peakY(v), low = y;
            if (y < minY) continue;
            boolean top = true;
            for (int dz = -r; dz <= r && top; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    long n = key(x + dx, z + dz);
                    if (!cells.containsKey(n)) continue;
                    long nv = cells.get(n);
                    int ny = Geo.peakY(nv);
                    low = Math.min(low, ny);
                    byte nc = Geo.cat(nv);
                    boolean rival = nc == cat || nc == Geo.MOUNTAINS || nc == Geo.VOLCANIC;
                    if (rival && (ny > y || ny == y && n < k)) { top = false; break; }
                }
            }
            if (!top || y - low < prominence) continue;
            Item it = null;
            for (Item rr : reg.items) {
                if (!rr.type.equals(type)) continue;
                if (Math.max(Math.abs(kx(rr.anchor) - x), Math.abs(kz(rr.anchor) - z)) > r) continue;
                if (it == null || rank(rr) < rank(it)) it = rr;
            }
            if (it == null) {
                it = newItem(reg, type, k, salt, used);
                changed[0] = true;
            }
            labels.add(new Label(it.id, type, it.nameId, it.name, it.custom, x * 16 + Geo.peakLx(v), z * 16 + Geo.peakLz(v),
                    16, 1, null));
        }
    }

    private static Item newItem(Registry reg, String type, long anchor, String salt, Set<String> used) {
        Item it = new Item();
        it.id = java.util.UUID.randomUUID().toString();
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
