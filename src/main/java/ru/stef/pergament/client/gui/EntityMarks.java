package ru.stef.pergament.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import ru.stef.pergament.client.PergamentConfig;
import ru.stef.pergament.client.map.LocalMap;

import java.util.function.DoubleUnaryOperator;

/**
 * Игроки и мобы на карте и миникарте — только те, кого клиент видит (в пределах прорисовки):
 * сервер дальше не присылает, а мод серверной части не имеет.
 * <ul>
 *   <li>Игроки — настоящее лицо со скина (со вторым слоем), рамка тушью (своя — красная), чёрточка взгляда,
 *       у чужих на большой карте — ник.</li>
 *   <li>Мобы — настоящая голова из их модели и текстуры ({@link MobHeads}); не нашлась — кружок по типу:
 *       враждебные красные, мирные зелёные, водные синие. Включается в настройках.</li>
 * </ul>
 */
public final class EntityMarks {
    /** Самотест: id мобов, попавших на последний кадр большой карты. */
    public static final java.util.Set<Integer> LAST_DRAWN = new java.util.HashSet<>();

    private EntityMarks() {}

    /** Игроки (свой и чужие) — лица; size — сторона лица в пикселях GUI. */
    static void drawPlayers(GuiGraphics g, Font font, DoubleUnaryOperator sx, DoubleUnaryOperator sy, float partial,
                            int size, boolean names) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (PergamentConfig.SHOW_PLAYERS.get()) drawMates(g, font, sx, sy, size, names);
        if (LocalMap.get().viewingOther()) return;
        if (PergamentConfig.SHOW_PLAYERS.get()) {
            for (Player p : mc.level.players()) {
                if (p == mc.player || p.isInvisibleTo(mc.player) || !(p instanceof AbstractClientPlayer acp)) continue;
                double x = Mth.lerp(partial, p.xo, p.getX()), z = Mth.lerp(partial, p.zo, p.getZ());
                double fx = sx.applyAsDouble(x), fy = sy.applyAsDouble(z);
                int cx = sub(g, fx, fy), cy = (int) Math.floor(fy);
                head(g, acp.getSkinTextureLocation(), cx, cy, size, p.getYRot(), Paper.INK);
                if (names) {
                    String n = p.getGameProfile().getName();
                    int w = font.width(n) + 4;
                    g.fill(cx - w / 2, cy + size / 2 + 2, cx - w / 2 + w, cy + size / 2 + 12, 0xDDF1E4C0);
                    g.drawString(font, n, cx - w / 2 + 2, cy + size / 2 + 3, Paper.INK, false);
                }
                g.pose().popPose();
            }
        }
        if (mc.player != null) {
            double x = Mth.lerp(partial, mc.player.xo, mc.player.getX()), z = Mth.lerp(partial, mc.player.zo, mc.player.getZ());
            double fx = sx.applyAsDouble(x), fy = sy.applyAsDouble(z);
            int cx = sub(g, fx, fy), cy = (int) Math.floor(fy);
            head(g, mc.player.getSkinTextureLocation(), cx, cy, size, mc.player.getYRot(), Paper.RED);
            g.pose().popPose();
        }
    }

    /** Самотест: UUID сокомандников, попавших на последний кадр. */
    public static final java.util.Set<java.util.UUID> LAST_MATES = new java.util.HashSet<>();
    private static final int MATE_FRAME = 0xFF2E5A8C;

    /**
     * Сокомандники по FTB Teams (от сервера, где бы они ни были) — только над разведанным в показываемом
     * измерении. Кто в прорисовке, уже нарисован как обычный игрок — второй раз не рисуем.
     */
    private static void drawMates(GuiGraphics g, Font font, DoubleUnaryOperator sx, DoubleUnaryOperator sy, int size,
                                  boolean names) {
        Minecraft mc = Minecraft.getInstance();
        LocalMap map = LocalMap.get();
        var shown = map.shown();
        if (names) LAST_MATES.clear();
        if (shown == null) return;
        for (var m : ru.stef.pergament.client.TeamClient.mates()) {
            if (!m.dim().equals(map.shownDimension())) continue;
            if (!map.viewingOther() && mc.level.getPlayerByUUID(m.id()) != null) continue;
            if (shown.heightAt(m.x(), m.z()) == null) continue;              // не над разведанным — не выдаём
            var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(m.id());
            ResourceLocation skin = info != null ? info.getSkinLocation()
                    : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(m.id());
            double fx = sx.applyAsDouble(m.x() + 0.5), fy = sy.applyAsDouble(m.z() + 0.5);
            int cx = sub(g, fx, fy), cy = (int) Math.floor(fy);
            head(g, skin, cx, cy, size, m.yaw(), MATE_FRAME);
            if (names) {
                LAST_MATES.add(m.id());
                int w = font.width(m.name()) + 4;
                g.fill(cx - w / 2, cy + size / 2 + 2, cx - w / 2 + w, cy + size / 2 + 12, 0xDDF1E4C0);
                g.drawString(font, m.name(), cx - w / 2 + 2, cy + size / 2 + 3, MATE_FRAME, false);
            }
            g.pose().popPose();
        }
    }

    /** Мобы в прорисовке: лицо из текстуры или кружок по типу. */
    static void drawMobs(GuiGraphics g, DoubleUnaryOperator sx, DoubleUnaryOperator sy, float partial, int size,
                         int clipX0, int clipY0, int clipX1, int clipY1) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !PergamentConfig.SHOW_MOBS.get() || LocalMap.get().viewingOther()) return;
        if (size >= 8) LAST_DRAWN.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e instanceof Player || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || !le.isAlive() || e.isInvisibleTo(mc.player)) continue;
            if (Math.abs(e.getY() - mc.player.getY()) > PergamentConfig.MOB_VERTICAL.get()) continue;  // не тащить дно пещер на поверхность
            double x = Mth.lerp(partial, e.xo, e.getX()), z = Mth.lerp(partial, e.zo, e.getZ());
            double fx = sx.applyAsDouble(x), fy = sy.applyAsDouble(z);
            if (fx < clipX0 || fy < clipY0 || fx > clipX1 || fy > clipY1) continue;
            int cx = sub(g, fx, fy), cy = (int) Math.floor(fy);
            MobCategory cat = e.getType().getCategory();
            int frame = switch (cat) {
                case MONSTER -> 0xFFB02A1A;
                case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> 0xFF2E5A8C;
                case CREATURE -> 0xFF3E6B2E;
                default -> 0xFF6B5233;
            };
            if (size >= 8) LAST_DRAWN.add(e.getId());
            int h = size / 2;
            // фон прозрачный: голова прямо на карте; тип моба — чёрточкой под ней
            if (MobHeads.draw(g, le, cx, cy, size)) {
                g.fill(cx - h + 1, cy + h + 1, cx + h - 1, cy + h + 2, frame);
            } else {                                            // головы нет — кружок по типу
                g.fill(cx - h + 1, cy - h + 1, cx + h - 1, cy + h - 1, (frame & 0x00FFFFFF) | 0xCC000000);
            }
            g.pose().popPose();
        }
    }

    /** Лицо игрока в рамке и чёрточка взгляда снаружи. */
    /**
     * Точка в пикселях GUI с долями: доли — сдвигом позы (как фон карты, который едет плавно), целое — для рисования.
     * Без этого значки прыгают целыми пикселями GUI и «дрожат» относительно фона. Кладёт позу — снять popPose.
     */
    private static int sub(GuiGraphics g, double fx, double fy) {
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
        g.pose().pushPose();
        g.pose().translate(fx - ix, fy - iy, 0);
        return ix;
    }

    private static void head(GuiGraphics g, ResourceLocation skin, int cx, int cy, int size, float yRot, int frame) {
        int h = size / 2;
        double yaw = Math.toRadians(yRot);
        for (int i = h + 2; i <= h + 5; i++) {
            int lx = cx + (int) Math.round(-Math.sin(yaw) * i), ly = cy + (int) Math.round(Math.cos(yaw) * i);
            g.fill(lx - 1, ly - 1, lx + 1, ly + 1, frame);
        }
        g.fill(cx - h - 1, cy - h - 1, cx + h + 1, cy + h + 1, frame);
        PlayerFaceRenderer.draw(g, skin, cx - h, cy - h, size);
    }
}
