package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import ru.stef.pergament.client.PergamentConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Настройки карты прямо в игре, на пергаменте: «−»/«+» у каждой строки, значение сразу сохраняется
 * в {@code pergament-client.toml}. Насыщенность перекрашивает карту на лету.
 */
public class SettingsScreen extends Screen {

    private record Row(String label, java.util.function.Supplier<String> value, Runnable minus, Runnable plus) {}

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int left, top;

    public SettingsScreen(Screen parent) {
        super(T.c("settings.title"));
        this.parent = parent;
        rows.add(new Row(T.t("set.color_keep"), () -> pct(PergamentConfig.COLOR_KEEP.get()),
                () -> step(PergamentConfig.COLOR_KEEP, -0.05, 0.2, 1.0), () -> step(PergamentConfig.COLOR_KEEP, 0.05, 0.2, 1.0)));
        rows.add(new Row(T.t("set.front"), () -> String.valueOf(PergamentConfig.REVEAL_FRONT.get()),
                () -> step(PergamentConfig.REVEAL_FRONT, -1, 1, 32), () -> step(PergamentConfig.REVEAL_FRONT, 1, 1, 32)));
        rows.add(new Row(T.t("set.back"), () -> String.valueOf(PergamentConfig.REVEAL_BACK.get()),
                () -> step(PergamentConfig.REVEAL_BACK, -1, 1, 32), () -> step(PergamentConfig.REVEAL_BACK, 1, 1, 32)));
        rows.add(new Row(T.t("set.players"), () -> PergamentConfig.SHOW_PLAYERS.get() ? T.t("val.shown_pl") : T.t("val.hidden_pl"),
                () -> toggle(PergamentConfig.SHOW_PLAYERS), () -> toggle(PergamentConfig.SHOW_PLAYERS)));
        rows.add(new Row(T.t("set.mobs"), () -> PergamentConfig.SHOW_MOBS.get() ? T.t("val.shown_pl") : T.t("val.hidden_pl"),
                () -> toggle(PergamentConfig.SHOW_MOBS), () -> toggle(PergamentConfig.SHOW_MOBS)));
        rows.add(new Row(T.t("set.mob_vertical"), () -> String.valueOf(PergamentConfig.MOB_VERTICAL.get()),
                () -> step(PergamentConfig.MOB_VERTICAL, -5, 5, 384), () -> step(PergamentConfig.MOB_VERTICAL, 5, 5, 384)));
        rows.add(new Row(T.t("set.on_minimap"), () -> PergamentConfig.ENTITIES_ON_MINIMAP.get() ? T.t("val.yes") : T.t("val.no"),
                () -> toggle(PergamentConfig.ENTITIES_ON_MINIMAP), () -> toggle(PergamentConfig.ENTITIES_ON_MINIMAP)));
        rows.add(new Row(T.t("set.marker_scale"), () -> PergamentConfig.MARKER_SCALE.get() + "%",
                () -> step(PergamentConfig.MARKER_SCALE, -25, 50, 300), () -> step(PergamentConfig.MARKER_SCALE, 25, 50, 300)));
        rows.add(new Row(T.t("set.waypoints"), () -> PergamentConfig.WAYPOINTS.get() ? T.t("val.shown_pl") : T.t("val.hidden_pl"),
                () -> toggle(PergamentConfig.WAYPOINTS), () -> toggle(PergamentConfig.WAYPOINTS)));
        rows.add(new Row(T.t("set.deaths"), () -> PergamentConfig.DEATH_KEEP.get() == 0 ? T.t("val.never")
                : String.valueOf(PergamentConfig.DEATH_KEEP.get()),
                () -> step(PergamentConfig.DEATH_KEEP, -1, 0, 50), () -> step(PergamentConfig.DEATH_KEEP, 1, 0, 50)));
        rows.add(new Row(T.t("set.minimap"), () -> PergamentConfig.MINIMAP.get() ? T.t("val.shown") : T.t("val.hidden"),
                () -> toggle(PergamentConfig.MINIMAP), () -> toggle(PergamentConfig.MINIMAP)));
        rows.add(new Row(T.t("set.clock"), () -> PergamentConfig.MINIMAP_CLOCK.get() ? T.t("val.yes") : T.t("val.no"),
                () -> toggle(PergamentConfig.MINIMAP_CLOCK), () -> toggle(PergamentConfig.MINIMAP_CLOCK)));
        rows.add(new Row(T.t("set.minimap_size"), () -> String.valueOf(PergamentConfig.MINIMAP_SIZE.get()),
                () -> step(PergamentConfig.MINIMAP_SIZE, -8, 48, 256), () -> step(PergamentConfig.MINIMAP_SIZE, 8, 48, 256)));
        rows.add(new Row(T.t("set.minimap_zoom"), () -> T.t("val.px_per_block", fmt(PergamentConfig.MINIMAP_ZOOM.get())),
                () -> scale(PergamentConfig.MINIMAP_ZOOM, 1 / Math.sqrt(2)), () -> scale(PergamentConfig.MINIMAP_ZOOM, Math.sqrt(2))));
        rows.add(new Row(T.t("set.minimap_corner"), () -> T.t("corner" + PergamentConfig.MINIMAP_CORNER.get()),
                () -> cycle(PergamentConfig.MINIMAP_CORNER, -1), () -> cycle(PergamentConfig.MINIMAP_CORNER, 1)));
    }

    @Override
    protected void init() {
        int w = 360, h = 34 + rows.size() * 22 + 34;
        left = (width - w) / 2;
        top = (height - h) / 2;
        for (int k = 0; k < rows.size(); k++) {
            Row r = rows.get(k);
            int y = top + 30 + k * 22;
            addRenderableWidget(new PaperButton(left + w - 140, y, 20, Component.literal("−"), () -> false, r.minus()));
            addRenderableWidget(new PaperButton(left + w - 30, y, 20, Component.literal("+"), () -> false, r.plus()));
        }
        addRenderableWidget(new PaperButton(left + 12, top + h - 24, 130, T.c("set.xaero"), () -> false,
                () -> minecraft.setScreen(new XaeroImportScreen(this))));
        addRenderableWidget(new PaperButton(left + w - 92, top + h - 24, 80, T.c("button.done"), () -> false, this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int w = 360, h = 34 + rows.size() * 22 + 34;
        Paper.plaque(g, left, top, left + w, top + h);
        g.drawCenteredString(font, title, left + w / 2, top + 10, Paper.INK);
        for (int k = 0; k < rows.size(); k++) {
            Row r = rows.get(k);
            int y = top + 30 + k * 22 + 3;
            g.drawString(font, r.label(), left + 12, y, Paper.INK, false);
            String v = r.value().get();
            g.drawString(font, v, left + w - 75 - font.width(v) / 2, y, Paper.RED, false);    // между «−» и «+»
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void step(ForgeConfigSpec.DoubleValue v, double d, double min, double max) {
        v.set(Math.round(Math.max(min, Math.min(max, v.get() + d)) * 100) / 100.0);
        v.save();
    }

    private static void step(ForgeConfigSpec.IntValue v, int d, int min, int max) {
        v.set(Math.max(min, Math.min(max, v.get() + d)));
        v.save();
    }

    private static void scale(ForgeConfigSpec.DoubleValue v, double k) {
        v.set(Math.round(Math.max(0.25, Math.min(4.0, v.get() * k)) * 1000) / 1000.0);
        v.save();
    }

    private static void cycle(ForgeConfigSpec.IntValue v, int d) {
        v.set(Math.floorMod(v.get() + d, 4));                       // четыре угла
        v.save();
    }

    private static void toggle(ForgeConfigSpec.BooleanValue v) {
        v.set(!v.get());
        v.save();
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String fmt(double v) {
        DoubleFunction<String> f = x -> Math.abs(x - Math.round(x)) < 0.01 ? String.valueOf(Math.round(x)) : String.format("%.2f", x);
        return f.apply(v);
    }
}
