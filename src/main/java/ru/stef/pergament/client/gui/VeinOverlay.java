package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import ru.stef.pergament.client.map.Veins;

import java.util.ArrayList;
import java.util.List;

/**
 * Подтверждённые жилы GT на большой карте: ромбик в центре настоящим цветом основного материала,
 * пунктирный круг по размеру скопления, у истощённой — пустой ромб с крестом. Наведение — записка
 * с названием жилы и составом. Список кэшируется на секунду: кэш GT меняется редко.
 */
final class VeinOverlay {
    private static List<Veins.Vein> cached = List.of();
    private static long cachedAt;
    private static String cachedKey = "";

    private VeinOverlay() {}

    static List<Veins.Vein> visible(MapScreen v) {
        Veins.Source src = Veins.source();
        Minecraft mc = Minecraft.getInstance();
        if (src == null || mc.level == null) return List.of();
        List<Veins.Vein> out = new ArrayList<>(fromGt(v, src, mc));
        var finds = ru.stef.pergament.client.map.LocalMap.get().finds();
        if (finds != null) {
            for (var f : finds.all()) {
                java.util.List<String> by = new ArrayList<>();
                if (f.byIndicator) by.add(T.t("vein.by_indicator"));
                if (f.byProspector) by.add(T.t("vein.by_prospector"));
                if (f.byHand) by.add(T.t("vein.by_hand"));
                String how = String.join(" + ", by);
                out.add(new Veins.Vein(f.x, f.y, f.z, Math.max(6, f.spread), f.name, List.of(new Veins.Mat(f.name, f.rgb)), false,
                        T.t("vein.confirmed", how, f.count, f.minY, f.maxY)));
            }
        }
        return out;
    }

    private static List<Veins.Vein> fromGt(MapScreen v, Veins.Source src, Minecraft mc) {
        if (!mc.level.dimension().location().toString().equals(ru.stef.pergament.client.map.LocalMap.get().shownDimension())) {
            return List.of();                            // кэш GT — про измерение игрока, в глобусе его не путаем
        }
        int x0 = (int) Math.floor(v.wx(0)) - 64, z0 = (int) Math.floor(v.wz(0)) - 64;
        int x1 = (int) Math.ceil(v.wx(v.width)) + 64, z1 = (int) Math.ceil(v.wz(v.height)) + 64;
        String key = mc.level.dimension().location() + "|" + (x0 >> 6) + "," + (z0 >> 6) + "," + (x1 >> 6) + "," + (z1 >> 6);
        long now = System.currentTimeMillis();
        if (!key.equals(cachedKey) || now - cachedAt > 1000) {
            try {
                cached = src.inArea(mc.level.dimension(), x0, z0, x1 - x0, z1 - z0);
            } catch (RuntimeException e) {
                cached = List.of();                     // GT поменял кэш под нами — в следующую секунду ещё раз
            }
            cachedAt = now;
            cachedKey = key;
        }
        return cached;
    }

    static void draw(GuiGraphics g, MapScreen v, List<Veins.Vein> veins) {
        for (Veins.Vein vein : veins) {
            int cx = (int) Math.round(v.sx(vein.x() + 0.5)), cy = (int) Math.round(v.sy(vein.z() + 0.5));
            int rgb = vein.mats().isEmpty() ? 0x808080 : vein.mats().get(0).rgb();
            double r = vein.radius() * v.zoomGui();
            if (r >= 6) ring(g, cx, cy, r, 0xC0000000 | rgb);
            diamond(g, cx, cy, 5, Paper.INK);
            if (vein.depleted()) {
                diamond(g, cx, cy, 4, 0xFFF1E4C0);
                g.fill(cx - 2, cy, cx + 3, cy + 1, Paper.INK);
            } else {
                diamond(g, cx, cy, 4, 0xFF000000 | rgb);
            }
        }
    }

    static Veins.Vein hovered(MapScreen v, List<Veins.Vein> veins, double mx, double my) {
        Veins.Vein best = null;
        double bestD = 7 * 7;
        for (Veins.Vein vein : veins) {
            double dx = v.sx(vein.x() + 0.5) - mx, dy = v.sy(vein.z() + 0.5) - my;
            if (dx * dx + dy * dy < bestD) {
                bestD = dx * dx + dy * dy;
                best = vein;
            }
        }
        return best;
    }

    static void drawTip(GuiGraphics g, Font font, MapScreen v, Veins.Vein vein, int mx, int my) {
        List<String> lines = new ArrayList<>();
        lines.add(vein.name() + (vein.depleted() ? T.t("vein.depleted") : ""));
        lines.add(T.t("vein.center", vein.x(), vein.y(), vein.z()));
        if (vein.mats().size() > 1) for (Veins.Mat m : vein.mats()) lines.add("  " + m.name());
        if (vein.note() != null && !vein.note().isEmpty()) lines.add(vein.note());
        Tip.draw(g, font, lines, mx, my, v.width, v.height);
    }

    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Пунктирный круг: точки через одну. */
    private static void ring(GuiGraphics g, int cx, int cy, double r, int color) {
        int n = (int) Math.max(24, r * 2);
        for (int k = 0; k < n; k += 2) {
            double a = 2 * Math.PI * k / n;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
