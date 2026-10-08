package ru.stef.pergament.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Перевод строк интерфейса: ключи {@code pergament.*} в assets/pergament/lang (ru_ru, en_us). */
public final class T {
    private T() {}

    public static String t(String key, Object... args) {
        return I18n.get("pergament." + key, args);
    }

    public static Component c(String key, Object... args) {
        return Component.translatable("pergament." + key, args);
    }
}
