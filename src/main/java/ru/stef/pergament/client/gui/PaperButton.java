package ru.stef.pergament.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** Кнопка-ярлык на бумаге: тушью обведена, включённая — залита красной тушью. */
public class PaperButton extends AbstractButton {
    private final Runnable action;
    private final BooleanSupplier on;

    public PaperButton(int x, int y, int w, Component label, BooleanSupplier on, Runnable action) {
        super(x, y, w, 14, label);
        this.on = on;
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        boolean active = on.getAsBoolean();
        int x0 = getX(), y0 = getY(), x1 = x0 + width, y1 = y0 + height;
        g.fill(x0, y0, x1, y1, active ? Paper.RED : isHoveredOrFocused() ? 0xFFE3D09E : 0xFFEADBB3);
        g.renderOutline(x0, y0, width, height, Paper.INK);
        var font = Minecraft.getInstance().font;
        int tw = font.width(getMessage());
        g.drawString(font, getMessage(), x0 + (width - tw) / 2, y0 + 3, active ? 0xFFFBF1D8 : Paper.INK, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
