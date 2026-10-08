package ru.stef.pergament.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ru.stef.pergament.Pergament;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Жилы, которые игрок подтвердил сам: собрал индикатор и под ним правда нашлась руда того же
 * материала, или добыл рудный блок руками. Рядом лежащие находки одного материала сливаются
 * в одну жилу (центр — взвешенный по числу блоков). {@code finds.json} рядом с регионами.
 */
public final class Finds {
    public static final int MERGE_RADIUS = 24;

    public static final class Find {
        public String mat = "";          // id материала (gtceu:barite)
        public String name = "";         // как в игре
        public int rgb;                  // настоящий цвет материала
        public int x, y, z;              // центр
        public int minY, maxY;
        public int count;                // рудных блоков, которые видели/добыли
        public int spread;               // насколько далеко от центра разбросаны блоки
        public boolean byIndicator, byHand, byProspector;
        public long time = System.currentTimeMillis();
        /** Постоянный id жилы (для отдачи команде архивом: повтор не раздует счётчики). */
        public String id;

        public Find copy() {
            Find c = new Find();
            c.mat = mat; c.name = name; c.rgb = rgb;
            c.x = x; c.y = y; c.z = z; c.minY = minY; c.maxY = maxY;
            c.count = count; c.spread = spread;
            c.byIndicator = byIndicator; c.byHand = byHand; c.byProspector = byProspector;
            c.time = time;
            return c;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private final List<Find> list = new ArrayList<>();
    /** Id событий находок, уже влитых сюда (свои и от команды): каждое — ровно один раз. */
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    private Finds(Path file) {
        this.file = file;
    }

    public List<Find> all() {
        return list;
    }

    /** Событие находки с id: влить, если ещё не вливали. true — влито. */
    public boolean applyOnce(String eventId, Find f) {
        if (eventId == null || !seen.add(eventId)) return false;
        addOrMerge(f);                                   // сохранит и список, и seen
        return true;
    }

    /** Своё событие: запомнить id, чтобы эхо от команды не влилось второй раз. */
    public void markSeen(String eventId) {
        if (seen.add(eventId)) save();
    }

    /** Добавить находку; если рядом уже есть жила того же материала — влить в неё. Возвращает итоговую. */
    public Find addOrMerge(Find f) {
        for (Find g : list) {
            if (g.mat.equals(f.mat) && Math.hypot(g.x - f.x, g.z - f.z) <= MERGE_RADIUS) {
                int n = Math.max(1, g.count) + Math.max(1, f.count);
                int nx = Math.round((g.x * (float) Math.max(1, g.count) + f.x * (float) Math.max(1, f.count)) / n);
                int nz = Math.round((g.z * (float) Math.max(1, g.count) + f.z * (float) Math.max(1, f.count)) / n);
                g.spread = (int) Math.round(Math.max(Math.hypot(g.x - nx, g.z - nz) + g.spread, Math.hypot(f.x - nx, f.z - nz) + f.spread));
                g.x = nx;
                g.z = nz;
                g.y = Math.round((g.y * (float) Math.max(1, g.count) + f.y * (float) Math.max(1, f.count)) / n);
                g.minY = Math.min(g.minY, f.minY);
                g.maxY = Math.max(g.maxY, f.maxY);
                g.count = g.count + f.count;
                g.byIndicator |= f.byIndicator;
                g.byHand |= f.byHand;
                g.byProspector |= f.byProspector;
                g.time = f.time;
                save();
                return g;
            }
        }
        if (f.id == null) f.id = java.util.UUID.randomUUID().toString();
        list.add(f);
        save();
        return f;
    }

    public static Finds load(Path file) {
        Finds s = new Finds(file);
        if (!Files.isRegularFile(file)) return s;
        try {
            List<Find> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Find>>() { }.getType());
            if (l != null) {
                for (Find f : l) {
                    if (f.mat == null || f.mat.isEmpty()) continue;
                    if (f.id == null) f.id = java.util.UUID.randomUUID().toString();
                    s.list.add(f);
                }
            }
            Path sf = file.resolveSibling("finds_seen.json");
            if (Files.isRegularFile(sf)) {
                List<String> ids = GSON.fromJson(Files.readString(sf, StandardCharsets.UTF_8), new TypeToken<List<String>>() { }.getType());
                if (ids != null) s.seen.addAll(ids);
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: находки {} не прочитаны: {}", file, e.toString());
        }
        return s;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(list), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Path sf = file.resolveSibling("finds_seen.json"), stmp = file.resolveSibling("finds_seen.json.tmp");
            Files.writeString(stmp, GSON.toJson(new ArrayList<>(seen)), StandardCharsets.UTF_8);
            Files.move(stmp, sf, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: находки {} не записаны: {}", file, e.toString());
        }
    }
}
