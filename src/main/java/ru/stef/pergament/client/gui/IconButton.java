package ru.stef.pergament.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.stef.pergament.Pergament;

/** Квадратная кнопка панели инструментов: клочок бумаги, рамка тушью, иконка 16×16 и подсказка. */
public class IconButton extends AbstractButton {
    public static final int SIZE = 20;
    private final ResourceLocation icon;
    private final Runnable action;
    private java.util.function.BooleanSupplier on = () -> false;

    public IconButton(int x, int y, String iconName, Component tip, Runnable action) {
        this(x, y, new ResourceLocation(Pergament.MOD_ID, "textures/gui/icon_" + iconName + ".png"), tip, action);
    }

    public IconButton(int x, int y, ResourceLocation icon, Component tip, Runnable action) {
        super(x, y, SIZE, SIZE, tip);
        this.icon = icon;
        this.action = action;
        setTooltip(Tooltip.create(tip));
    }

    /** Подсветка «инструмент включён». */
    public IconButton on(java.util.function.BooleanSupplier on) {
        this.on = on;
        return this;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x0 = getX(), y0 = getY();
        boolean active = on.getAsBoolean();
        g.fill(x0, y0, x0 + width, y0 + height, active ? 0xFFD9B98A : isHoveredOrFocused() ? 0xFFE3D09E : 0xFFF1E4C0);
        g.renderOutline(x0, y0, width, height, active ? Paper.RED : Paper.INK);
        g.blit(icon, x0 + 2, y0 + 2, 0, 0, 16, 16, 16, 16);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
