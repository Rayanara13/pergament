package ru.stef.pergament.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.stef.pergament.client.T;
import ru.stef.pergament.client.geo.Names;
import ru.stef.pergament.client.geo.Toponyms;
import ru.stef.pergament.client.map.LocalMap;

/**
 * Переименовать объект местности: своё имя держится за место так же, как выданное; «Вернуть имя» — снова выданное.
 * Окно поверх карты (карта видна под ним).
 */
final class TopoRenameScreen extends Screen {
    private static final int W = 270, H = 92;
    private final MapScreen parent;
    private final Toponyms.Label label;
    private EditBox box;

    TopoRenameScreen(MapScreen parent, Toponyms.Label label) {
        super(T.c("topo.rename"));
        this.parent = parent;
        this.label = label;
    }

    @Override
    protected void init() {
        int x = (width - W) / 2, y = (height - H) / 2;
        box = new EditBox(font, x + 14, y + 32, W - 28, 12, T.c("topo.rename"));
        box.setBordered(false);
        box.setTextColor(0x3A2814);
        box.setMaxLength(40);
        box.setValue(TopoOverlay.name(label));
        addRenderableWidget(box);
        int bw = (W - 32) / 3;
        addRenderableWidget(new PaperButton(x + 10, y + H - 26, bw, T.c("button.save"), () -> false, () -> apply(box.getValue())));
        addRenderableWidget(new PaperButton(x + 16 + bw, y + H - 26, bw, T.c("topo.reset"), () -> false, () -> apply(null)));
        addRenderableWidget(new PaperButton(x + 22 + 2 * bw, y + H - 26, bw, T.c("button.close"), () -> false, this::onClose));
        setInitialFocus(box);
    }

    private void apply(String name) {
        var shown = LocalMap.get().shown();
        if (shown != null) {
            String generated = Names.display(label.nameId(), minecraft.getLanguageManager().getSelected(), label.fallback());
            shown.toponymy().rename(label.itemId(), name == null || name.strip().equals(generated) ? null : name);
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        parent.render(g, -1000, -1000, partial);
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(0, 0, width, height, 0x55000000);
        int x = (width - W) / 2, y = (height - H) / 2;
        Paper.plaque(g, x, y, x + W, y + H);
        g.drawString(font, T.t("topo.rename"), x + 10, y + 10, Paper.INK, false);
        g.fill(x + 10, y + 28, x + W - 10, y + 44, 0xFFF8EED4);
        g.renderOutline(x + 10, y + 28, W - 20, 16, Paper.INK);
        super.render(g, mouseX, mouseY, partial);
        g.pose().popPose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            apply(box.getValue());
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
