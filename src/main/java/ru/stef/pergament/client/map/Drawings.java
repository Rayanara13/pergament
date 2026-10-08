package ru.stef.pergament.client.map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ru.stef.pergament.Pergament;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Рисунки пером поверх карты: штрих = цвет + толщина + точки в БЛОКАХ мира (не в пикселях экрана),
 * поэтому при любом зуме ложатся на те же места. {@code drawings.json} рядом с регионами — отдельно
 * на мир и измерение, только локально.
 */
public final class Drawings {
    /** Цвета пера — тушь, киноварь, охра, зелень, синь, белила. */
    public static final int[] COLORS = {0xFF3A2814, 0xFF8E2A1C, 0xFFC98A1E, 0xFF3E6B2E, 0xFF2E5A8C, 0xFFF4EBD2};

    public static final class Stroke {
        public int color = COLORS[1];
        public int width = 2;
        public List<int[]> pts = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final List<Stroke> strokes = new ArrayList<>();

    private Drawings(Path file) {
        this.file = file;
    }

    public List<Stroke> all() {
        return strokes;
    }

    /** Добавить законченный штрих (точек ≥ 2), сохранить. */
    public void add(Stroke s) {
        if (s.pts.size() < 2) return;
        strokes.add(s);
        save();
    }

    /** Ластик: убрать штрихи, проходящие ближе radius блоков к точке. Сколько убрано. */
    public int eraseNear(double x, double z, double radius) {
        int before = strokes.size();
        strokes.removeIf(s -> near(s, x, z, radius));
        if (strokes.size() != before) save();
        return before - strokes.size();
    }

    public void clear() {
        strokes.clear();
        save();
    }

    static boolean near(Stroke s, double x, double z, double r) {
        for (int k = 1; k < s.pts.size(); k++) {
            if (segDist(x, z, s.pts.get(k - 1), s.pts.get(k)) <= r) return true;
        }
        return s.pts.size() == 1 && Math.hypot(s.pts.get(0)[0] - x, s.pts.get(0)[1] - z) <= r;
    }

    /** Расстояние от точки до отрезка ab. */
    static double segDist(double x, double z, int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / len2));
        return Math.hypot(a[0] + t * dx - x, a[1] + t * dz - z);
    }

    public static Drawings load(Path file) {
        Drawings d = new Drawings(file);
        if (!Files.isRegularFile(file)) return d;
        try {
            List<Stroke> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Stroke>>() { }.getType());
            if (l != null) for (Stroke s : l) if (s.pts != null && s.pts.size() >= 2) d.strokes.add(s);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: рисунки {} не прочитаны: {}", file, e.toString());
        }
        return d;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(strokes), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: рисунки {} не записаны: {}", file, e.toString());
        }
    }
}
