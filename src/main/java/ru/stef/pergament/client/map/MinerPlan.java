package ru.stef.pergament.client.map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.stef.pergament.Pergament;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * План шахтёра GT (MV: радиус 16 → квадрат 33×33, копает ниже себя). Точку установки ставит игрок,
 * от неё сетка: клетка (i, j) — центр (ox + 33·i, oz + 33·j), ±16 блоков. Видны 9 клеток вокруг
 * точки установки (i, j ∈ −1…1). Отметки «в работе»/«выкопано» — пометки игрока, не состояние машины:
 * мод не знает, что делает шахтёр, и не притворяется. Руд в плане нет.
 * Хранится в {@code miner.json} рядом с регионами — отдельно на мир и измерение.
 */
public final class MinerPlan {
    public static final int STEP = 33, R = 16, REACH = 1;
    public static final int NONE = 0, WORKING = 1, DONE = 2;

    private final Path file;
    private Integer ox, oz;
    private final Map<Long, Integer> states = new HashMap<>();

    public MinerPlan(Path file) {
        this.file = file;
    }

    public boolean hasOrigin() { return ox != null; }
    public int ox() { return ox; }
    public int oz() { return oz; }

    public static int cell(int origin, int block) {
        return Math.floorDiv(block - origin + R, STEP);
    }

    public int cellI(int bx) { return cell(ox, bx); }
    public int cellJ(int bz) { return cell(oz, bz); }

    /** Клетка в видимых 3×3 вокруг точки установки? */
    public boolean inReach(int i, int j) {
        return Math.abs(i) <= REACH && Math.abs(j) <= REACH;
    }

    public int state(int i, int j) {
        return states.getOrDefault(key(i, j), NONE);
    }

    /** Клик по клетке: нет → в работе → выкопано → нет. */
    public void cycle(int i, int j) {
        int s = (state(i, j) + 1) % 3;
        if (s == NONE) states.remove(key(i, j)); else states.put(key(i, j), s);
        save();
    }

    /** Новая точка установки: старые отметки относились к старой сетке — сбрасываем. */
    public void setOrigin(int x, int z) {
        ox = x;
        oz = z;
        states.clear();
        save();
    }

    public void clear() {
        ox = oz = null;
        states.clear();
        save();
    }

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
    }

    public static MinerPlan load(Path file) {
        MinerPlan p = new MinerPlan(file);
        if (!Files.isRegularFile(file)) return p;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("ox")) {
                p.ox = o.get("ox").getAsInt();
                p.oz = o.get("oz").getAsInt();
            }
            JsonObject c = o.getAsJsonObject("cells");
            if (c != null) {
                for (var e : c.entrySet()) {
                    String[] ij = e.getKey().split(",");
                    p.states.put(key(Integer.parseInt(ij[0].trim()), Integer.parseInt(ij[1].trim())), e.getValue().getAsInt());
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: план шахтёра {} не прочитан: {}", file, e.toString());
        }
        return p;
    }

    private void save() {
        JsonObject o = new JsonObject();
        if (ox != null) {
            o.addProperty("ox", ox);
            o.addProperty("oz", oz);
        }
        JsonObject c = new JsonObject();
        for (var e : states.entrySet()) {
            c.addProperty((int) (e.getKey() >> 32) + "," + (int) (long) e.getKey(), e.getValue());
        }
        o.add("cells", c);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: план шахтёра {} не записан: {}", file, e.toString());
        }
    }
}
