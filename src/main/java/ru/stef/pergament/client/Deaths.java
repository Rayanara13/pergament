package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.DimMap;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Markers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Автометка «здесь погиб»: череп в точке смерти, в описании — причина с экрана смерти.
 * Ловим двумя путями: экран смерти (с причиной) и здоровье, упавшее до нуля (на случай doImmediateRespawn,
 * когда экрана нет). Второй сигнал той же смерти метку не дублирует, а дописывает.
 * Хранятся последние {@code deaths.keep} меток гибели в каждом измерении; метку, сохранённую игроком
 * из панели, чистка больше не трогает.
 */
public final class Deaths {
    public static final String KIND = "death";
    private static final long SAME_DEATH_MS = 3000;

    private static boolean wasDead;
    private static Markers.Marker last;
    private static long lastAt;

    private Deaths() {}

    public static void tick() {
        LocalPlayer p = Minecraft.getInstance().player;
        boolean dead = p != null && p.isDeadOrDying();
        if (dead && !wasDead) record(null);
        wasDead = dead;
    }

    public static void onScreen(ScreenEvent.Opening e) {
        if (e.getNewScreen() instanceof DeathScreen ds) record(cause(ds));
    }

    private static void record(String cause) {
        if (PergamentConfig.DEATH_KEEP.get() <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        DimMap dim = LocalMap.get().live();
        if (mc.player == null || dim == null) return;
        Markers ms = dim.markers();
        long now = System.currentTimeMillis();
        if (last != null && now - lastAt < SAME_DEATH_MS && ms.byId(last.id) != null) {   // та же смерть, второй сигнал
            if (cause != null && last.desc.isEmpty()) {
                last.desc = cause;
                ms.put(last);
            }
            return;
        }
        Markers.Marker m = new Markers.Marker();
        m.kind = KIND;
        m.icon = "skull";
        m.world = true;                                   // путь к месту гибели — виден в мире
        m.name = T.t("death.name", new SimpleDateFormat("dd.MM HH:mm").format(new Date(now)));
        m.desc = cause == null ? "" : cause;
        m.x = mc.player.getBlockX();
        m.y = mc.player.getBlockY();
        m.z = mc.player.getBlockZ();
        m.created = now;
        ms.put(m);
        last = m;
        lastAt = now;
        prune(ms);
        mc.player.displayClientMessage(T.c("death.marked", m.x, m.y, m.z), false);
    }

    /** Оставить последние N автометок гибели этого измерения. */
    private static void prune(Markers ms) {
        List<Markers.Marker> deaths = new ArrayList<>();
        for (Markers.Marker m : ms.all()) if (KIND.equals(m.kind)) deaths.add(m);
        deaths.sort((a, b) -> Long.compare(b.created, a.created));
        for (int i = PergamentConfig.DEATH_KEEP.get(); i < deaths.size(); i++) ms.remove(deaths.get(i).id);
    }

    /**
     * Причина смерти с экрана: у DeathScreen два поля Component — причина (final, из конструктора)
     * и счёт (заполняется в init, на момент открытия ещё пуст). Берём заполненное, без имён полей —
     * они в боевой сборке зашифрованы.
     */
    private static String cause(DeathScreen ds) {
        try {
            for (Field f : DeathScreen.class.getDeclaredFields()) {
                if (f.getType() != Component.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.get(ds) instanceof Component c) {
                    String s = c.getString().trim();
                    if (!s.isEmpty()) return s;
                }
            }
        } catch (Exception ex) {
            Pergament.LOG.debug("Пергамент: причина смерти не прочитана: {}", ex.toString());
        }
        return null;
    }

    /** Самотест: последняя метка гибели. */
    public static Markers.Marker lastForTest() {
        return last;
    }
}
