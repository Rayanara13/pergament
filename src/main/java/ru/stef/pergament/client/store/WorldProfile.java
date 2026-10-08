package ru.stef.pergament.client.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.stef.pergament.Pergament;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Чья карта: у клиента нет надёжного «паспорта» мира, поэтому профиль — по месту подключения.
 * Одиночка — по папке сохранения, сервер — по адресу. Один мир под двумя адресами (тайлнет и локалка)
 * склеивается только руками: {@code pergament/aliases.json} {"mp_192.168.1.10_25565": "mp_play.example.org_25565"}.
 * Автоматически ничего не склеиваем — два разных мира на одном адресе важнее не смешать.
 */
public final class WorldProfile {
    private WorldProfile() {}

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("pergament");
    }

    /** Профиль текущего подключения или null, если мира нет. */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer sp = mc.getSingleplayerServer();
        String key;
        if (sp != null) {
            Path p = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            key = "sp_" + safe(p.getFileName() == null ? sp.getWorldData().getLevelName() : p.getFileName().toString());
        } else {
            ServerData sd = mc.getCurrentServer();
            if (sd == null) return null;
            String ip = sd.ip.trim();
            if (!ip.contains(":")) ip = ip + ":25565";
            key = "mp_" + safe(ip);
        }
        return aliases().getOrDefault(key, key);
    }

    public static Path dir(String profile, String dimension) {
        return root().resolve("worlds").resolve(profile).resolve(safe(dimension));
    }

    private static Map<String, String> aliases() {
        Map<String, String> m = new HashMap<>();
        Path f = root().resolve("aliases.json");
        if (!Files.isRegularFile(f)) return m;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), safe(e.getValue().getAsString()));
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: aliases.json не прочитан: {}", e.toString());
        }
        return m;
    }

    /** Имя папки: буквы любого алфавита и цифры сохраняются («Новый мир» ≠ «Мир 2»), остальное — «_». */
    public static String safe(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }
}
