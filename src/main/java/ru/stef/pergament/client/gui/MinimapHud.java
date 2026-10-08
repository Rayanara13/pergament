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
        int y0 = (corner & 2) == 0 ? PAD : screenH - PAD - size - 12;
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
                int mx = (int) Math.round(cxs + (m.x + 0.5 - px) * zoom), my = (int) Math.round(cys + (m.z + 0.5 - pz) * zoom);
                if (mx < x0 || my < y0 || mx > x1 || my > y1) continue;
                g.blit(MarkerOverlay.icon(m.icon), mx - 4, my - 8, 8, 8, 0, 0, 16, 16, 16, 16);
            }
        }
        if (PergamentConfig.ENTITIES_ON_MINIMAP.get()) {
            EntityMarks.drawMobs(g, wx -> cxs + (wx - px) * zoom, wz -> cys + (wz - pz) * zoom, partial, 6, x0, y0, x1, y1);
        }
        EntityMarks.drawPlayers(g, mc.font, wx -> cxs + (wx - px) * zoom, wz -> cys + (wz - pz) * zoom, partial, 8, false);
        g.disableScissor();                                  // чужие игроки за краем миникарты — обрезаются
        Paper.frame(g, x0, y0, x1, y1);
        g.drawCenteredString(mc.font, T.t("compass.n"), (int) cxs, y0 + 3, Paper.RED);
        String co = p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
        g.drawString(mc.font, co, (int) cxs - mc.font.width(co) / 2, y1 + 2, 0xFFFFFFFF, true);
    }
}
