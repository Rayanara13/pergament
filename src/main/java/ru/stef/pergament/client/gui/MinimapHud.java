package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import ru.stef.pergament.client.PergamentConfig;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.store.RegionData;

/** Миникарта: квадрат пергамента с разведанным вокруг игрока, север вверху, координаты под ней. */
public final class MinimapHud implements IGuiOverlay {
    public static final MinimapHud INSTANCE = new MinimapHud();
    private static final int PAD = 6;

    private MinimapHud() {}

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partial, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        if (!PergamentConfig.MINIMAP.get() || mc.options.hideGui || mc.player == null
                || mc.options.renderDebug) return;
        int size = PergamentConfig.MINIMAP_SIZE.get();
        float zoom = PergamentConfig.MINIMAP_ZOOM.get().floatValue();
        int corner = PergamentConfig.MINIMAP_CORNER.get();      // 0 ↖, 1 ↗, 2 ↙, 3 ↘
        int x0 = (corner & 1) == 0 ? PAD : screenW - PAD - size;
        // табличка под миникартой: по ширине текста; влезает — две колонки, иначе столбиком
        boolean clock = PergamentConfig.MINIMAP_CLOCK.get();
        Player me = mc.player;
        String[][] cell = new String[4][];                              // {иконка, текст}
        int[] tint = new int[4];
        cell[0] = new String[]{"coords", me.getBlockX() + ", " + me.getBlockY() + ", " + me.getBlockZ()};
        tint[0] = Paper.INK;
        if (clock) {
            var c = ru.stef.pergament.client.GameClock.info(mc);
            cell[1] = new String[]{c.day() ? "day" : "night", c.game()};
            tint[1] = c.day() ? DAY : NIGHT;
            cell[2] = new String[]{"clock", c.real()};
            tint[2] = Paper.INK;
            cell[3] = new String[]{c.dateIcon(), c.date()};
            tint[3] = SEASON.getOrDefault(c.dateIcon(), Paper.INK);
        }
        int n = clock ? 4 : 1;
        int colA = cellW(mc, cell[0]), colB = 0;
        if (clock) {
            colA = Math.max(colA, cellW(mc, cell[2]));                  // левая: координаты, настоящее время
            colB = Math.max(cellW(mc, cell[1]), cellW(mc, cell[3]));    // правая: время в игре, дата
        }
        boolean two = clock && colA + colB + GAP + 6 <= size;
        int panelW = clock ? (two ? colA + colB + GAP + 6 : Math.max(colA, colB) + 6) : colA + 6;
        int panelH = (two ? 2 : n) * ROW + 4;
        int y0 = (corner & 2) == 0 ? PAD : screenH - PAD - size - 2 - panelH;
        int x1 = x0 + size, y1 = y0 + size;

        Player p = mc.player;
        double px = Mth.lerp(partial, p.xo, p.getX()), pz = Mth.lerp(partial, p.zo, p.getZ());
        double cxs = (x0 + x1) / 2.0, cys = (y0 + y1) / 2.0;

        Paper.fill(g, x0, y0, x1, y1);
        g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        int t = RegionData.SIZE;
        double half = size / 2.0 / zoom;
        int rx0 = Math.floorDiv((int) Math.floor(px - half), t), rx1 = Math.floorDiv((int) Math.floor(px + half), t);
        int rz0 = Math.floorDiv((int) Math.floor(pz - half), t), rz1 = Math.floorDiv((int) Math.floor(pz + half), t);
        PoseStack pose = g.pose();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                var live = LocalMap.get().live();
                ResourceLocation tex = live == null ? null : live.texture(rx, rz);
                if (tex == null) continue;
                pose.pushPose();
                pose.translate(cxs + ((double) rx * t - px) * zoom, cys + ((double) rz * t - pz) * zoom, 0);
                pose.scale(zoom, zoom, 1);
                g.blit(tex, 0, 0, 0, 0, t, t, t, t);
                pose.popPose();
            }
        }
        var route = ru.stef.pergament.client.route.RoutePlan.get();
        if (!route.path().isEmpty() && !LocalMap.get().viewingOther()) {
            RouteDraw.draw(g, mc.font, route, wx -> cxs + (wx - px) * zoom, wz -> cys + (wz - pz) * zoom, zoom, false);
        }
        var marks = LocalMap.get().live() == null ? null : LocalMap.get().live().markers();
        if (marks != null) {
            for (var m : marks.all()) {
                double fx = cxs + (m.x + 0.5 - px) * zoom, fy = cys + (m.z + 0.5 - pz) * zoom;
                if (fx < x0 || fy < y0 || fx > x1 || fy > y1) continue;
                int mx = (int) Math.floor(fx), my = (int) Math.floor(fy), ms = MarkerOverlay.size(8);
                pose.pushPose();
                pose.translate(fx - mx, fy - my, 0);                         // доли пикселя — как у фона
                g.blit(MarkerOverlay.icon(m.icon), mx - ms / 2, my - ms, ms, ms, 0, 0, 16, 16, 16, 16);
                pose.popPose();
            }
        }
        if (PergamentConfig.ENTITIES_ON_MINIMAP.get()) {
            EntityMarks.drawMobs(g, wx -> cxs + (wx - px) * zoom, wz -> cys + (wz - pz) * zoom, partial, 6, x0, y0, x1, y1);
        }
        EntityMarks.drawPlayers(g, mc.font, wx -> cxs + (wx - px) * zoom, wz -> cys + (wz - pz) * zoom, partial, 8, false);
        g.disableScissor();                                  // чужие игроки за краем миникарты — обрезаются
        Paper.frame(g, x0, y0, x1, y1);
        g.drawCenteredString(mc.font, T.t("compass.n"), (int) cxs, y0 + 3, Paper.RED);
        // табличка в рамке точно по тексту, прижата к краю экрана со стороны угла
        int ty = y1 + 2, px0 = (corner & 1) == 0 ? x0 : x1 - panelW;
        Paper.plaque(g, px0, ty, px0 + panelW, ty + panelH);
        if (two) {
            row(g, mc, cell[0], tint[0], px0 + 3, ty + 3);
            row(g, mc, cell[2], tint[2], px0 + 3, ty + 3 + ROW);
            row(g, mc, cell[1], tint[1], px0 + 3 + colA + GAP, ty + 3);
            row(g, mc, cell[3], tint[3], px0 + 3 + colA + GAP, ty + 3 + ROW);
        } else {
            int[] order = {0, 1, 2, 3};
            for (int k = 0; k < n; k++) row(g, mc, cell[order[k]], tint[order[k]], px0 + 3, ty + 3 + k * ROW);
        }
    }

    private static final int GAP = 8;

    private static int cellW(Minecraft mc, String[] c) {
        return c == null ? 0 : 11 + mc.font.width(c[1]);
    }

    private static final int ROW = 10, DAY = 0xFF9A6200, NIGHT = 0xFF2E4A8C;
    /** Цвет даты по сезону TFC. */
    private static final java.util.Map<String, Integer> SEASON = java.util.Map.of(
            "spring", 0xFF3E7B2E, "summer", 0xFF9A6E08, "autumn", 0xFFA0461C, "winter", 0xFF2F6E9E);

    /** Ячейка таблички: иконка 8×8 и текст тушью, без тени (на бумаге). */
    private static void row(GuiGraphics g, Minecraft mc, String[] c, int color, int x, int y) {
        g.blit(new net.minecraft.resources.ResourceLocation(ru.stef.pergament.Pergament.MOD_ID,
                "textures/gui/icon_ci_" + c[0] + ".png"), x, y, 8, 8, 0, 0, 16, 16, 16, 16);
        g.drawString(mc.font, c[1], x + 11, y, color, false);
    }
}
