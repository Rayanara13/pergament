package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** Образец цвета пера: квадрат краски в рамке тушью, выбранный — в красной рамке. */
public class SwatchButton extends AbstractButton {
    private final int color;
    private final BooleanSupplier selected;
    private final Runnable action;

    public SwatchButton(int x, int y, int size, int color, BooleanSupplier selected, Runnable action) {
        super(x, y, size, size, T.c("pen.color"));
        this.color = color;
        this.selected = selected;
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x0 = getX(), y0 = getY();
        g.fill(x0, y0, x0 + width, y0 + height, color);
        g.renderOutline(x0, y0, width, height, Paper.INK);
        if (selected.getAsBoolean()) g.renderOutline(x0 - 2, y0 - 2, width + 4, height + 4, Paper.RED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
