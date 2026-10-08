package ru.stef.pergament.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Подсказка-записка: клочок бумаги с рамкой тушью у курсора, не вылезает за экран. */
final class Tip {
    private Tip() {}

    static void draw(GuiGraphics g, Font font, List<String> lines, int mx, int my, int sw, int sh) {
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s));
        int h = lines.size() * (font.lineHeight + 1) + 6;
        w += 10;
        int x = mx + 12, y = my + 12;
        if (x + w > sw - 4) x = mx - w - 12;
        if (y + h > sh - 4) y = Math.max(4, my - h - 12);
        x = Math.max(4, Math.min(x, sw - w - 4));                 // не за край экрана ни с какой стороны
        y = Math.max(4, Math.min(y, sh - h - 4));
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);          // поверх кнопок и маркеров
        g.fill(x, y, x + w, y + h, 0xF5F1E4C0);
        g.renderOutline(x, y, w, h, Paper.INK);
        int ty = y + 4;
        for (int k = 0; k < lines.size(); k++) {
            g.drawString(font, lines.get(k), x + 5, ty, k == 0 ? Paper.INK : Paper.INK_SOFT, false);
            ty += font.lineHeight + 1;
        }
        g.pose().popPose();
    }
}
