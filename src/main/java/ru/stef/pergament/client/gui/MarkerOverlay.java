package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.Markers;

import java.util.ArrayList;
import java.util.List;

/** Метки игрока: иконка остриём в точку, под ней ярлычок с названием; наведение — записка. */
final class MarkerOverlay {
    private MarkerOverlay() {}

    static ResourceLocation icon(String name) {
        return new ResourceLocation(Pergament.MOD_ID, "textures/gui/icon_mk_" + name + ".png");
    }

    /** Сторона значка на карте с учётом настройки размера. */
    static int size(int base) {
        return Math.max(4, Math.round(base * ru.stef.pergament.client.PergamentConfig.MARKER_SCALE.get() / 100f));
    }

    static void draw(GuiGraphics g, Font font, MapScreen v, List<Markers.Marker> list, String selectedId) {
        int s = size(16);
        for (Markers.Marker m : list) {
            int x = (int) Math.round(v.sx(m.x + 0.5)), y = (int) Math.round(v.sy(m.z + 0.5));
            if (x < -40 - s || y < -40 - s || x > v.width + 40 + s || y > v.height + 40 + s) continue;
            g.blit(icon(m.icon), x - s / 2, y - s + 1, s, s, 0, 0, 16, 16, 16, 16);
            if (m.id.equals(selectedId)) g.renderOutline(x - s / 2 - 2, y - s - 1, s + 4, s + 4, Paper.RED);
            if (!m.name.isEmpty() && v.zoomGui() >= 0.25) {
                int w = font.width(m.name) + 6;
                g.fill(x - w / 2, y + 2, x - w / 2 + w, y + 2 + font.lineHeight + 2, 0xE6F1E4C0);
                g.renderOutline(x - w / 2, y + 2, w, font.lineHeight + 2, Paper.INK);
                g.drawString(font, m.name, x - w / 2 + 3, y + 3, Paper.INK, false);
            }
        }
    }

    static Markers.Marker hovered(MapScreen v, List<Markers.Marker> list, double mx, double my) {
        Markers.Marker best = null;
        int s = size(16);
        double bestD = Math.max(9, s * 0.6) * Math.max(9, s * 0.6);
        for (Markers.Marker m : list) {
            double dx = v.sx(m.x + 0.5) - mx, dy = v.sy(m.z + 0.5) - s / 2.0 + 1 - my;     // центр иконки над точкой
            if (dx * dx + dy * dy < bestD) {
                bestD = dx * dx + dy * dy;
                best = m;
            }
        }
        return best;
    }

    static void drawTip(GuiGraphics g, Font font, MapScreen v, Markers.Marker m, int mx, int my) {
        List<String> lines = new ArrayList<>();
        lines.add(m.name.isEmpty() ? T.t("marker.unnamed") : m.name);
        if (!m.desc.isEmpty()) lines.add(m.desc);
        lines.add("X " + m.x + "  Y " + m.y + "  Z " + m.z + distance(m));
        lines.add(T.t("marker.click"));
        Tip.draw(g, font, lines, mx, my, v.width, v.height);
    }

    static String distance(Markers.Marker m) {
        var p = Minecraft.getInstance().player;
        if (p == null) return "";
        return "  ·  " + T.t("unit.blocks", Math.round(Math.hypot(p.getX() - m.x, p.getZ() - m.z)));
    }
}
