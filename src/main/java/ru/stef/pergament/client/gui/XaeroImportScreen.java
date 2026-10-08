package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.stef.pergament.client.store.WorldProfile;
import ru.stef.pergament.client.xaero.XaeroImport;

import java.util.List;

/**
 * Импорт из Xaero's World Map: какие миры Xaero есть, какой совпал с текущим, сколько регионов в каждом измерении.
 * Ничего не начинается без кнопки «Импортировать». Импорт идёт в карту ТЕКУЩЕГО мира и только в пустые места.
 */
public final class XaeroImportScreen extends Screen {
    private static final int W = 380;
    private final Screen parent;
    private List<XaeroImport.World> worlds = List.of();
    private int selected = -1;
    private int left, top, h;

    public XaeroImportScreen(Screen parent) {
        super(T.c("xaero.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        worlds = XaeroImport.worlds();
        if (selected < 0 || selected >= worlds.size()) {
            selected = -1;
            for (int k = 0; k < worlds.size(); k++) if (worlds.get(k).matchesCurrent()) selected = k;
        }
        h = Math.min(height - 20, 120 + worlds.size() * 16 + 60);
        left = (width - W) / 2;
        top = (height - h) / 2;
        for (int k = 0; k < worlds.size(); k++) {
            int idx = k;
            addRenderableWidget(new PaperButton(left + 12, top + 44 + k * 16, W - 24, Component.literal(worlds.get(k).name()
                    + (worlds.get(k).matchesCurrent() ? T.t("xaero.this_world") : "")), () -> selected == idx, () -> selected = idx));
        }
        int by = top + h - 26, bw = 110;
        addRenderableWidget(new PaperButton(left + 12, by, bw, T.c("xaero.import"),
                () -> running(), this::start));
        addRenderableWidget(new PaperButton(left + 12 + bw + 8, by, bw, T.c("xaero.stop"),
                () -> false, XaeroImport::cancel));
        addRenderableWidget(new PaperButton(left + W - 12 - 80, by, 80, T.c("button.back"), () -> false, this::onClose));
    }

    private static boolean running() {
        XaeroImport j = XaeroImport.current();
        return j != null && !j.done;
    }

    private void start() {
        if (selected < 0 || running()) return;
        XaeroImport.start(worlds.get(selected).sources());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        Paper.plaque(g, left, top, left + W, top + h);
        g.drawCenteredString(font, title, left + W / 2, top + 10, Paper.INK);
        g.drawString(font, T.t("xaero.target", WorldProfile.current()), left + 12, top + 26, Paper.INK_SOFT, false);
        int y = top + 48 + worlds.size() * 16;
        if (worlds.isEmpty()) {
            g.drawString(font, T.t("xaero.none"), left + 12, top + 46, Paper.RED, false);
        } else if (selected >= 0) {
            for (XaeroImport.Source s : worlds.get(selected).sources()) {
                g.drawString(font, T.t("xaero.source", s.label(), s.files()), left + 18, y, Paper.INK, false);
                y += 11;
            }
            if (!worlds.get(selected).matchesCurrent()) {
                g.drawString(font, T.t("xaero.mismatch"),
                        left + 12, y + 2, Paper.RED, false);
                y += 11;
            }
        }
        g.drawString(font, T.t("xaero.only_empty"), left + 12, y + 6, Paper.INK_SOFT, false);
        g.drawString(font, T.t("xaero.real_colors"), left + 12, y + 17, Paper.INK_SOFT, false);
        XaeroImport j = XaeroImport.current();
        if (j != null) {
            int py = top + h - 46, pw = W - 24;
            float f = j.total == 0 ? 1 : Math.min(1f, j.merged / (float) j.total);
            g.fill(left + 12, py, left + 12 + pw, py + 6, Paper.INK_FAINT);
            g.fill(left + 12, py, left + 12 + (int) (pw * f), py + 6, Paper.RED);
            String s = (j.done ? T.t("xaero.done", j.status) : T.t("xaero.progress", j.merged, j.total))
                    + T.t("xaero.counts", j.chunksAdded, j.chunksKept)
                    + (j.errors > 0 ? T.t("xaero.errors", j.errors) : "");
            g.drawString(font, s, left + 12, py - 11, Paper.INK, false);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);                            // импорт продолжается в фоне
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
