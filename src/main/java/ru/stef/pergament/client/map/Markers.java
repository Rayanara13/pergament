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
import java.util.UUID;

/**
 * Метки игрока одного мира и измерения: {@code markers.json} рядом с регионами.
 * Только локально — ничего никуда не уходит.
 */
public final class Markers {
    /** Значок: id (textures/gui/icon_mk_<id>.png), русское название, группа; показ — на языке игры (lang). */
    public record Icon(String id, String ru, String group, String gkey) {
        public String name() {
            String k = "pergament.icon." + id;
            return net.minecraft.client.resources.language.I18n.exists(k) ? net.minecraft.client.resources.language.I18n.get(k) : ru;
        }

        public String groupName() {
            String k = "pergament.icon_group." + gkey;
            return gkey != null && net.minecraft.client.resources.language.I18n.exists(k)
                    ? net.minecraft.client.resources.language.I18n.get(k) : group;
        }
    }

    /** Значки меток из assets/pergament/marker_icons.json (рисует tools/gen_markers.py), первый — по умолчанию. */
    public static final List<Icon> ICONS = loadIcons();
    public static final String DEFAULT_ICON = ICONS.isEmpty() ? "pin_red" : ICONS.get(0).id();

    private static List<Icon> loadIcons() {
        try (var in = Markers.class.getResourceAsStream("/assets/pergament/marker_icons.json")) {
            if (in == null) return List.of();
            List<Icon> l = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeToken<List<Icon>>() { }.getType());
            return l == null ? List.of() : List.copyOf(l);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: список значков не прочитан: {}", e.toString());
            return List.of();
        }
    }

    public static boolean knownIcon(String id) {
        for (Icon i : ICONS) if (i.id().equals(id)) return true;
        return false;
    }

    public static String iconName(String id) {
        for (Icon i : ICONS) if (i.id().equals(id)) return i.name();
        return id;
    }

    public static final class Marker {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String desc = "";
        public String icon = DEFAULT_ICON;
        public int x, y, z;
        public long created = System.currentTimeMillis();
        /** Чья метка: "" — игрока, "death" — автометка гибели (её подчищает лимит). */
        public String kind = "";

        public Marker copy() {
            Marker m = new Marker();
            m.id = id; m.name = name; m.desc = desc; m.icon = icon;
            m.x = x; m.y = y; m.z = z; m.created = created; m.kind = kind;
            return m;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private final List<Marker> list = new ArrayList<>();

    private Markers(Path file) {
        this.file = file;
    }

    public List<Marker> all() {
        return list;
    }

    public Marker byId(String id) {
        for (Marker m : list) if (m.id.equals(id)) return m;
        return null;
    }

    /** Новая или правленая метка: правка заменяет запись с тем же id. */
    public void put(Marker m) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(m.id)) {
                list.set(i, m);
                save();
                return;
            }
        }
        list.add(m);
        save();
    }

    public void remove(String id) {
        if (list.removeIf(m -> m.id.equals(id))) save();
    }

    public static Markers load(Path file) {
        Markers s = new Markers(file);
        if (!Files.isRegularFile(file)) return s;
        try {
            List<Marker> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Marker>>() { }.getType());
            if (l != null) {
                for (Marker m : l) {
                    if (m.icon == null || !knownIcon(m.icon)) m.icon = DEFAULT_ICON;
                    if (m.name == null) m.name = "";
                    if (m.desc == null) m.desc = "";
                    if (m.kind == null) m.kind = "";
                    s.list.add(m);
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: метки {} не прочитаны: {}", file, e.toString());
        }
        return s;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(list), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: метки {} не записаны: {}", file, e.toString());
        }
    }
}
