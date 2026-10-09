package ru.stef.pergament.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ru.stef.pergament.client.PergamentConfig;
import ru.stef.pergament.client.T;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Метки в мире (как путевые точки): значок, название и расстояние над точкой метки, видны сквозь стены.
 * Только метки с галочкой «в мире» (и метки гибели), только в своём измерении. Рисуется поверх экрана:
 * точка мира проецируется матрицами камеры этого кадра — значок всегда чёткий и одного размера.
 */
public final class WorldMarkers implements IGuiOverlay {
    public static final WorldMarkers INSTANCE = new WorldMarkers();
    private final Matrix4f view = new Matrix4f(), proj = new Matrix4f();
    private Vec3 cam = Vec3.ZERO;
    private boolean have;

    private WorldMarkers() {}

    /** Матрицы камеры этого кадра (вид без сдвига + проекция с покачиванием). */
    public static void capture(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        INSTANCE.view.set(e.getPoseStack().last().pose());
        INSTANCE.proj.set(e.getProjectionMatrix());
        INSTANCE.cam = e.getCamera().getPosition();
        INSTANCE.have = true;
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partial, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (!have || mc.player == null || mc.options.hideGui || !PergamentConfig.WAYPOINTS.get()) return;
        var live = LocalMap.get().live();
        if (live == null) return;
        record Spot(Markers.Marker m, double sx, double sy, double dist) {}
        List<Spot> spots = new ArrayList<>();
        Vector4f v = new Vector4f();
        for (Markers.Marker m : live.markers().all()) {
            if (!m.world) continue;
            double wx = m.x + 0.5, wy = m.y + 1.2, wz = m.z + 0.5;
            double dist = mc.player.position().distanceTo(new Vec3(wx, wy, wz));
            if (dist < 2) continue;                                   // стоишь на ней — не заслонять
            v.set((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z), 1f);
            view.transform(v);
            proj.transform(v);
            if (v.w <= 0.01f) continue;                               // позади камеры
            double nx = v.x / v.w, ny = v.y / v.w;
            if (Math.abs(nx) > 1.2 || Math.abs(ny) > 1.2) continue;
            spots.add(new Spot(m, (nx + 1) / 2 * w, (1 - ny) / 2 * h, dist));
        }
        spots.sort((a, b) -> Double.compare(b.dist, a.dist));         // ближние — поверх
        int s = Math.max(8, Math.round(16 * PergamentConfig.MARKER_SCALE.get() / 100f));
        var font = mc.font;
        for (Spot sp : spots) {
            int x = (int) Math.round(sp.sx), y = (int) Math.round(sp.sy);
            g.blit(MarkerOverlay.icon(sp.m.icon), x - s / 2, y - s, s, s, 0, 0, 16, 16, 16, 16);
            String name = sp.m.name.isEmpty() ? T.t("marker.unnamed") : sp.m.name;
            String d = T.t("unit.blocks", Math.round(sp.dist));
            int ty = y + 2;
            int tw = Math.max(font.width(name), font.width(d)) + 6;
            g.fill(x - tw / 2, ty - 1, x - tw / 2 + tw, ty + 19, 0x88000000);
            g.drawCenteredString(font, name, x, ty, 0xFFFFFFFF);
            g.drawCenteredString(font, d, x, ty + 10, 0xFFE8D8A8);
        }
    }
}
