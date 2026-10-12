package ru.stef.pergament.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.stef.pergament.client.geo.Names;
import ru.stef.pergament.client.geo.Toponyms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Подписи местности на большой карте — как названия на картах: вода — светлым курсивом в синей обводке, хребты — прописными
 * вразрядку коричневым, вулкан — ▲ и имя тёмно-красным. Размер подписи постоянный (не растёт с зумом), с ореолом
 * цвета бумаги; крупные объекты подписываются первыми, наложения пропускаются, подпись больше объекта — не рисуется.
 */
final class TopoOverlay {
    /** Вода: светлые буквы с тёмно-синей обводкой (синее на синем не читается); суша — тушь с бумажным ореолом. */
    private static final int WATER = 0xFFF4ECD6, WATER_HALO = 0xE0173052, RANGE = 0xFF6B4A2B, VOLCANO = 0xFF8C2A1A,
            HALO = 0xD8F1E4C0;

    private TopoOverlay() {}

    static void draw(GuiGraphics g, Font font, MapScreen v, List<Toponyms.Label> labels) {
        if (labels.isEmpty()) return;
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        List<Toponyms.Label> order = new ArrayList<>(labels);
        order.sort(Comparator.comparingInt((Toponyms.Label l) -> l.type().equals(Toponyms.VOLCANO) ? 30 : l.chunks()).reversed());
        List<int[]> placed = new ArrayList<>();
        for (Toponyms.Label l : order) {
            String name = Names.display(l.nameId(), lang, l.fallback());
            if (name.isEmpty()) continue;
            boolean water = !l.type().equals(Toponyms.RANGE) && !l.type().equals(Toponyms.VOLCANO);
            boolean caps = l.type().equals(Toponyms.RANGE) || l.type().equals(Toponyms.SEA);
            if (caps) name = name.toUpperCase(Locale.ROOT);
            if (l.type().equals(Toponyms.VOLCANO)) name = "▲ " + name;
            float scale = l.type().equals(Toponyms.SEA) ? 1.25f : l.type().equals(Toponyms.RIVER) || l.type().equals(Toponyms.VOLCANO) ? 0.85f : 1f;
            int spacing = caps ? 1 : 0;
            int tw = Math.round(width(font, name, water, spacing) * scale), th = Math.round(9 * scale);
            double extent = l.sizeBlocks() * v.zoomGui();
            if (!l.type().equals(Toponyms.VOLCANO) && extent < tw * 0.5) continue;      // объект мельче подписи — не подписываем
            if (l.type().equals(Toponyms.VOLCANO) && v.zoomGui() < 0.5) continue;
            int cx = (int) Math.round(v.sx(l.x() + 0.5)), cy = (int) Math.round(v.sy(l.z() + 0.5));
            int x0 = cx - tw / 2, y0 = cy - th / 2;
            if (x0 + tw < 0 || y0 + th < 0 || x0 > v.width || y0 > v.height) continue;
            int[] box = {x0 - 3, y0 - 2, x0 + tw + 3, y0 + th + 2};
            boolean hit = false;
            for (int[] p : placed) {
                if (box[0] < p[2] && p[0] < box[2] && box[1] < p[3] && p[1] < box[3]) { hit = true; break; }
            }
            if (hit) continue;
            placed.add(box);
            int color = water ? WATER : l.type().equals(Toponyms.RANGE) ? RANGE : VOLCANO;
            var pose = g.pose();
            pose.pushPose();
            pose.translate(x0, y0, 0);
            pose.scale(scale, scale, 1f);
            int halo = water ? WATER_HALO : HALO;
            for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}}) {
                text(g, font, name, o[0], o[1], halo, water, spacing);
            }
            text(g, font, name, 0, 0, color, water, spacing);
            pose.popPose();
        }
    }

    private static int width(Font font, String s, boolean italic, int spacing) {
        if (spacing == 0) return font.width(styled(s, italic));
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += font.width(styled(new String(Character.toChars(cp)), italic)) + spacing;
            i += Character.charCount(cp);
        }
        return Math.max(0, w - spacing);
    }

    private static void text(GuiGraphics g, Font font, String s, int x, int y, int color, boolean italic, int spacing) {
        if (spacing == 0) {
            g.drawString(font, styled(s, italic), x, y, color, false);
            return;
        }
        for (int i = 0; i < s.length(); ) {                                    // вразрядку — по букве
            int cp = s.codePointAt(i);
            Component ch = styled(new String(Character.toChars(cp)), italic);
            g.drawString(font, ch, x, y, color, false);
            x += font.width(ch) + spacing;
            i += Character.charCount(cp);
        }
    }

    private static Component styled(String s, boolean italic) {
        return italic ? Component.literal(s).withStyle(ChatFormatting.ITALIC) : Component.literal(s);
    }
}
