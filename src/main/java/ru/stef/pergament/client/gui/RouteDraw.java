package ru.stef.pergament.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import ru.stef.pergament.client.route.RoutePlan;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

/** Тропа тушью: тёмная обводка и красная середина, точки A/1/2/B. Общая для большой карты и миникарты. */
final class RouteDraw {
    private RouteDraw() {}

    static void draw(GuiGraphics g, Font font, RoutePlan plan, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                     double zoom, boolean labels) {
        List<int[]> path = plan.path();
        int half = zoom >= 2 ? 2 : 1;
        line(g, path, sx, sy, zoom, half + 1, Paper.INK);
        line(g, path, sx, sy, zoom, half, Paper.RED);
        if (!labels) return;
        List<int[]> pts = plan.points();
        for (int n = 0; n < pts.size(); n++) {
            int x = (int) Math.round(sx.applyAsDouble(pts.get(n)[0] + 0.5)), y = (int) Math.round(sy.applyAsDouble(pts.get(n)[1] + 0.5));
            String l = n == 0 ? "A" : n == pts.size() - 1 ? "B" : String.valueOf(n);
            g.fill(x - 5, y - 5, x + 6, y + 6, Paper.INK);
            g.fill(x - 4, y - 4, x + 5, y + 5, 0xFFF1E4C0);
            g.drawString(font, l, x - font.width(l) / 2 + 1, y - 3, Paper.INK, false);
        }
    }

    /** Ломаная по блокам; при мелком зуме прореживаем точки, соседние соединяем отрезками. */
    private static void line(GuiGraphics g, List<int[]> path, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                             double zoom, int half, int color) {
        if (path.isEmpty()) return;
        int stride = zoom >= 1 ? 1 : (int) Math.ceil(1 / zoom);
        int px = Integer.MIN_VALUE, py = 0;
        for (int k = 0; k < path.size(); k += stride) {
            int[] p = path.get(k);
            int x = (int) Math.round(sx.applyAsDouble(p[0] + 0.5)), y = (int) Math.round(sy.applyAsDouble(p[1] + 0.5));
            if (px != Integer.MIN_VALUE) seg(g, px, py, x, y, half, color);
            px = x;
            py = y;
        }
        int[] last = path.get(path.size() - 1);
        seg(g, px, py, (int) Math.round(sx.applyAsDouble(last[0] + 0.5)), (int) Math.round(sy.applyAsDouble(last[1] + 0.5)), half, color);
    }

    private static void seg(GuiGraphics g, int x0, int y0, int x1, int y1, int half, int color) {
        int n = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        if (n > 4000) return;
        for (int k = 0; k <= n; k++) {
            int x = x0 + (x1 - x0) * k / n, y = y0 + (y1 - y0) * k / n;
            g.fill(x - half + 1, y - half + 1, x + half, y + half, color);
        }
    }
}
