package ru.stef.pergament.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Проверочная галерея: голова каждого живого моба из реестра (ванилла и моды) крупно, с подписью.
 * Для самотеста (-Ppergament_gallery): видно разом, у кого голова нарисована не так.
 */
public final class HeadGalleryScreen extends Screen {
    private static final int CELL = 58, ICON = 36;
    private final List<LivingEntity> mobs = new ArrayList<>();
    private int page;

    public HeadGalleryScreen() {
        super(Component.literal("Головы мобов"));
    }

    @Override
    protected void init() {
        if (!mobs.isEmpty() || minecraft.level == null) return;
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) types.add(t);
        types.sort(Comparator.comparing(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        for (EntityType<?> t : types) {
            try {
                if (t.create(minecraft.level) instanceof LivingEntity le && !(le instanceof Player)
                        && !(le instanceof net.minecraft.world.entity.decoration.ArmorStand)) mobs.add(le);
            } catch (Throwable ignored) {
                // не создаётся вне мира — пропуск
            }
        }
    }

    public int pages() {
        return Math.max(1, (mobs.size() + perPage() - 1) / perPage());
    }

    public void page(int p) {
        page = p;
    }

    private int cols() {
        return Math.max(1, (width - 8) / CELL);
    }

    private int perPage() {
        return cols() * Math.max(1, (height - 24) / CELL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xFFECDCB2);
        g.drawString(font, "Головы мобов — " + (page + 1) + "/" + pages() + " (" + mobs.size() + ")", 6, 6, Paper.INK, false);
        int cols = cols(), from = page * perPage();
        for (int i = from; i < Math.min(mobs.size(), from + perPage()); i++) {
            int k = i - from, x = 4 + (k % cols) * CELL, y = 20 + (k / cols) * CELL;
            int cx = x + CELL / 2, cy = y + ICON / 2 + 2;
            g.fill(cx - ICON / 2 - 1, cy - ICON / 2 - 1, cx + ICON / 2 + 1, cy + ICON / 2 + 1, Paper.INK);
            g.fill(cx - ICON / 2, cy - ICON / 2, cx + ICON / 2, cy + ICON / 2, 0xFFF1E4C0);
            LivingEntity e = mobs.get(i);
            boolean ok = MobHeads.draw(g, e, cx, cy, ICON);
            String name = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (name.length() > 11) name = name.substring(0, 11);
            g.pose().pushPose();
            g.pose().translate(cx, y + ICON + 6, 0);
            g.pose().scale(0.5f, 0.5f, 1f);
            g.drawCenteredString(font, name, 0, 0, ok ? Paper.INK : Paper.RED);
            g.pose().popPose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
