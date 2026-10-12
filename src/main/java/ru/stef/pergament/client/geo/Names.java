package ru.stef.pergament.client.geo;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Имена для подписей: по типу (озеро, река, море, хребет, вулкан) пары «по-русски / по-английски»
 * (assets/pergament/names.json). Выданное имя хранится ссылкой «тип:номер[:повтор]», строка — по языку
 * интерфейса на момент показа: сменил язык — подписи переведутся, а имя останется тем же.
 */
public final class Names {
    private static Map<String, List<List<String>>> lists;
    private static final String[] ROMAN = {"", "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

    private Names() {}

    static synchronized Map<String, List<List<String>>> lists() {
        if (lists == null) {
            try (var in = Names.class.getResourceAsStream("/assets/pergament/names.json")) {
                lists = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, List<List<String>>>>() { }.getType());
            } catch (Exception e) {
                lists = Map.of();
            }
        }
        return lists;
    }

    /** Новое имя типа type: по хэшу места, без повторов в мире; кончились — то же с «II», «III»… */
    public static String pick(String type, long hash, Set<String> used) {
        List<List<String>> l = lists().get(type);
        if (l == null || l.isEmpty()) return type + ":0";
        int n = l.size(), start = (int) Math.floorMod(hash, (long) n);
        for (int rep = 1; ; rep++) {
            for (int i = 0; i < n; i++) {
                String id = type + ":" + (start + i) % n + (rep == 1 ? "" : ":" + rep);
                if (!used.contains(id)) return id;
            }
        }
    }

    /** Строка имени на языке интерфейса (ru* — по-русски, иначе по-английски); не нашли — запасная строка. */
    public static String display(String nameId, String lang, String fallback) {
        try {
            String[] p = nameId.split(":");
            List<String> pair = lists().get(p[0]).get(Integer.parseInt(p[1]));
            int rep = p.length > 2 ? Integer.parseInt(p[2]) : 1;
            String s = pair.get(lang != null && lang.startsWith("ru") ? 0 : 1);
            return s + (rep < ROMAN.length ? ROMAN[rep] : " " + rep);
        } catch (RuntimeException e) {
            return fallback == null ? "" : fallback;
        }
    }
}
