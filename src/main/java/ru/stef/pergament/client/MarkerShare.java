package ru.stef.pergament.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Markers;
import ru.stef.pergament.client.store.WorldProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Метки через чат. Отправка — обычное сообщение, понятное и без мода: «Метка «Шахта» — 120 64 -300» и хвост
 * {@code [pgm значок измерение]}. У кого Пергамент, к такому сообщению (и к меткам Xaero {@code xaero-waypoint:…})
 * дописывается кнопка «[+ на карту]»: клик выполняет клиентскую команду, метка ложится в свою карту.
 * Ничего не уходит на сервер, кроме самого сообщения; принять метку можно от кого угодно.
 */
public final class MarkerShare {
    /** Разобранная метка из чата. y == null — высоты нет (Xaero «~»): возьмём с карты. */
    record Share(String name, int x, Integer y, int z, String icon, String dim, String from) {}

    private static final Pattern OURS = Pattern.compile(
            "«([^«»]{0,40})» — (-?\\d{1,8}) (-?\\d{1,8}) (-?\\d{1,8}) \\[pgm ([a-z0-9_]{1,40}) ([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)]");
    private static final Pattern XAERO = Pattern.compile(
            "xaero-waypoint:([^:]*):([^:]*):(-?\\d{1,8}):(~|-?\\d{1,8}):(-?\\d{1,8}):\\d+:(?:true|false):-?\\d+(?::(\\S*))?");
    /** Кнопки последних сообщений: токен → метка (команда несёт только токен — имя с пробелами не ломает разбор). */
    private static final Map<String, Share> TOKENS = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Share> e) {
            return size() > 200;
        }
    };

    /** Самотест: стиль последней кнопки «на карту» (по нему «кликает» экран чата). */
    static volatile net.minecraft.network.chat.Style lastButton;

    private MarkerShare() {}

    /** Отправить метку в общий чат. */
    public static void send(Markers.Marker m, String dim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || dim == null) return;
        String name = (m.name.isEmpty() ? T.t("marker.unnamed") : m.name).replace('«', '"').replace('»', '"');
        if (name.length() > 40) name = name.substring(0, 40);
        String text = T.t("share.text", name, Integer.toString(m.x), Integer.toString(m.y), Integer.toString(m.z))
                + " [pgm " + m.icon + " " + dim + "]";
        mc.player.connection.sendChat(text);
    }

    /** Сообщение чата с меткой — дописать кнопку «на карту». */
    public static void onChat(ClientChatReceivedEvent e) {
        String text = e.getMessage().getString();
        Share s = parse(text, senderName(e.getSender()));
        if (s == null) return;
        String token = UUID.randomUUID().toString().substring(0, 8);
        synchronized (TOKENS) {
            TOKENS.put(token, s);
        }
        String where = s.x + " " + (s.y == null ? "~" : s.y) + " " + s.z + "  ·  " + ru.stef.pergament.client.gui.MapScreen.dimLabel(s.dim);
        Component btn = Component.literal(" [" + T.t("share.add") + "]").withStyle(st -> st
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/pergament_mark " + token))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal((s.name.isEmpty() ? T.t("marker.unnamed") : s.name) + "\n" + where))));
        e.setMessage(e.getMessage().copy().append(btn));
        lastButton = btn.getStyle();
    }

    /** Наш хвост или метка Xaero в тексте сообщения; null — меток нет. */
    static Share parse(String text, String from) {
        Matcher m = OURS.matcher(text);
        if (m.find()) {
            String icon = Markers.knownIcon(m.group(5)) ? m.group(5) : Markers.DEFAULT_ICON;
            return new Share(m.group(1).trim(), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), icon, m.group(6), from);
        }
        m = XAERO.matcher(text);
        if (m.find()) {
            String name = m.group(1).replace("§§", ":");                     // так Xaero прячет двоеточие в имени
            if (name.startsWith("gui.xaero")) name = "";
            Integer y = m.group(4).equals("~") ? null : Integer.parseInt(m.group(4));
            return new Share(name.length() > 40 ? name.substring(0, 40) : name, Integer.parseInt(m.group(3)), y,
                    Integer.parseInt(m.group(5)), Markers.DEFAULT_ICON, xaeroDim(m.group(6)), from);
        }
        return null;
    }

    /** Измерение из хвоста Xaero (Internal-overworld-waypoints и т.п.); не узнали — то, где игрок. */
    private static String xaeroDim(String tail) {
        String t = tail == null ? "" : tail.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("nether")) return "minecraft:the_nether";
        if (t.contains("the-end") || t.contains("the_end") || t.matches(".*[-_]end[-_].*")) return "minecraft:the_end";
        if (t.contains("overworld")) return "minecraft:overworld";
        String live = LocalMap.get().dimension();
        return live != null ? live : "minecraft:overworld";
    }

    private static String senderName(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (id == null || mc.getConnection() == null) return "";
        var info = mc.getConnection().getPlayerInfo(id);
        return info == null ? "" : info.getProfile().getName();
    }

    /**
     * Клик по кнопке «на карту» в чате. Forge перехватывает клиентские команды только набранные руками —
     * команда из клика ушла бы на сервер («неизвестная команда»). Поэтому клик по нашей кнопке ловим сами.
     */
    public static void onMouse(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre e) {
        if (!(e.getScreen() instanceof net.minecraft.client.gui.screens.ChatScreen) || e.getButton() != 0) return;
        String token = tokenAt(e.getMouseX(), e.getMouseY());
        if (token == null) return;
        accept(token);
        e.setCanceled(true);
    }

    /** Токен нашей кнопки под точкой экрана чата или null. */
    static String tokenAt(double x, double y) {
        var st = Minecraft.getInstance().gui.getChat().getClickedComponentStyleAt(x, y);
        ClickEvent ce = st == null ? null : st.getClickEvent();
        if (ce == null || ce.getAction() != ClickEvent.Action.RUN_COMMAND || !ce.getValue().startsWith("/pergament_mark ")) return null;
        return ce.getValue().substring("/pergament_mark ".length()).trim();
    }

    public static void onRegisterCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("pergament_mark")
                .then(Commands.argument("token", StringArgumentType.word()).executes(ctx -> {
                    accept(StringArgumentType.getString(ctx, "token"));
                    return 1;
                })));
    }

    /** Кнопка «на карту»: метка — в карту своего измерения (не открытого сейчас — прямо в файл меток). */
    private static void accept(String token) {
        Minecraft mc = Minecraft.getInstance();
        Share s;
        synchronized (TOKENS) {
            s = TOKENS.get(token);
        }
        LocalMap lm = LocalMap.get();
        if (mc.player == null) return;
        if (s == null || lm.profile() == null) {
            mc.player.displayClientMessage(Component.literal(T.t("share.expired")).withStyle(ChatFormatting.GRAY), false);
            return;
        }
        Markers ms;
        if (s.dim.equals(lm.dimension())) ms = lm.live().markers();
        else if (s.dim.equals(lm.shownDimension())) ms = lm.markers();
        else ms = Markers.load(WorldProfile.dir(lm.profile(), s.dim).resolve("markers.json"));
        for (Markers.Marker o : ms.all()) {
            if (o.x == s.x && o.z == s.z && o.name.equals(s.name)) {
                mc.player.displayClientMessage(Component.literal(T.t("share.dup", label(s))).withStyle(ChatFormatting.GRAY), false);
                return;
            }
        }
        Markers.Marker m = new Markers.Marker();
        m.name = s.name;
        m.icon = s.icon;
        m.x = s.x;
        m.z = s.z;
        Integer h = s.y != null ? s.y : s.dim.equals(lm.dimension()) && lm.live() != null ? lm.live().heightAt(s.x, s.z) : null;
        m.y = h != null ? h : mc.player.getBlockY();
        if (!s.from.isEmpty()) m.desc = T.t("share.from", s.from);
        ms.put(m);
        mc.player.displayClientMessage(Component.literal(T.t("share.added", label(s))).withStyle(ChatFormatting.GREEN), false);
    }

    private static String label(Share s) {
        return s.name.isEmpty() ? T.t("marker.unnamed") : s.name;
    }
}
