package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.MinerPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Шахтёр на большой карте: 9 клеток 33×33 вокруг точки установки. Пустая — тонкая рамка тушью,
 * «в работе» — золотистая заливка с красной рамкой, «выкопано» — штриховка тушью. В точке установки —
 * кирка. Наведение — записка с клеткой и подсказкой клика; руд нет и не будет.
 */
final class MinerOverlay {
    private static final ResourceLocation PICK = new ResourceLocation(Pergament.MOD_ID, "textures/gui/icon_pickaxe.png");

    private MinerOverlay() {}

    static void draw(GuiGraphics g, MapScreen v, MinerPlan p) {
        if (!p.hasOrigin()) return;
        for (int j = -MinerPlan.REACH; j <= MinerPlan.REACH; j++) {
            for (int i = -MinerPlan.REACH; i <= MinerPlan.REACH; i++) {
                int[] r = rect(v, p, i, j);
                int st = p.state(i, j);
                if (st == MinerPlan.WORKING) {
                    g.fill(r[0], r[1], r[2], r[3], 0x55C9A24A);
                    g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1], Paper.RED);
                } else if (st == MinerPlan.DONE) {
                    hatch(g, r);
                    g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1], Paper.INK);
                } else {
                    g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1], 0x993A2814);
                }
            }
        }
        int cx = (int) Math.round(v.sx(p.ox() + 0.5)), cy = (int) Math.round(v.sy(p.oz() + 0.5));
        g.blit(PICK, cx - 8, cy - 8, 0, 0, 16, 16, 16, 16);
    }

    /** Клетка (i, j) под курсором, если она из видимых девяти; иначе null. */
    static int[] hovered(MapScreen v, MinerPlan p, double mx, double my) {
        if (!p.hasOrigin()) return null;
        int i = p.cellI((int) Math.floor(v.wx(mx))), j = p.cellJ((int) Math.floor(v.wz(my)));
        return p.inReach(i, j) ? new int[]{i, j} : null;
    }

    static void drawTip(GuiGraphics g, Font font, MapScreen v, MinerPlan p, int[] ij, int mx, int my) {
        int st = p.state(ij[0], ij[1]);
        List<String> lines = new ArrayList<>();
        lines.add(T.t("miner.tip", ij[0], ij[1], p.ox() + MinerPlan.STEP * ij[0], p.oz() + MinerPlan.STEP * ij[1]));
        lines.add(T.t("miner.mark", T.t("miner.state" + st)));
        lines.add(T.t("miner.click" + st));
        Tip.draw(g, font, lines, mx, my, v.width, v.height);
    }

    private static int[] rect(MapScreen v, MinerPlan p, int i, int j) {
        double x0 = p.ox() + MinerPlan.STEP * i - MinerPlan.R, z0 = p.oz() + MinerPlan.STEP * j - MinerPlan.R;
        return new int[]{(int) Math.round(v.sx(x0)), (int) Math.round(v.sy(z0)),
                (int) Math.round(v.sx(x0 + MinerPlan.STEP)), (int) Math.round(v.sy(z0 + MinerPlan.STEP))};
    }

    /** Штриховка тушью под 45° — «выкопано». */
    private static void hatch(GuiGraphics g, int[] r) {
        int w = r[2] - r[0], h = r[3] - r[1];
        g.fill(r[0], r[1], r[2], r[3], 0x333A2814);
        for (int k = -h; k < w; k += 4) {
            for (int t = 0; t < h; t++) {
                int x = r[0] + k + t;
                if (x >= r[0] && x < r[2]) g.fill(x, r[1] + t, x + 1, r[1] + t + 1, 0x993A2814);
            }
        }
    }
}
