package ru.stef.pergament.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import ru.stef.pergament.Pergament;

/**
 * Бумага и тушь. Текстура бумаги генерируется кодом (value noise + волокна),
 * чужих картинок нет. Создаётся лениво на render thread при первом рисовании.
 */
public final class Paper {
    public static final int PARCH = 0xFFECDCB2;
    public static final int INK = 0xFF3A2814;
    public static final int INK_SOFT = 0xFF654B2C;
    public static final int INK_FAINT = 0x553A2814;
    public static final int RED = 0xFF8E2A1C;
    public static final int GOLD = 0xFFC9A24A;

    static final int SIZE = 256;
    private static final ResourceLocation TEX = new ResourceLocation(Pergament.MOD_ID, "dynamic/paper");
    private static boolean ready;

    private Paper() {}

    private static void ensure() {
        if (ready) return;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        long seed = 1234567L;
        final int g = 8, cell = SIZE / g;
        float[] v = new float[g * g];
        for (int i = 0; i < v.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            v[i] = ((seed >>> 33) & 0xFFFF) / 65535f;
        }
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                float fx = (float) x / cell, fz = (float) z / cell;
                int ix = (int) fx, iz = (int) fz;
                float tx = smooth(fx - ix), tz = smooth(fz - iz);
                float a = lerp(at(v, g, ix, iz), at(v, g, ix + 1, iz), tx);
                float b = lerp(at(v, g, ix, iz + 1), at(v, g, ix + 1, iz + 1), tx);
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float fib = ((seed >>> 33) & 0xFF) / 255f;
                float k = 1f - (lerp(a, b, tz) * 0.10f + fib * 0.05f);
                int r = clamp((int) (0xEC * k)), gg = clamp((int) (0xDC * k)), bb = clamp((int) (0xB2 * k));
                img.setPixelRGBA(x, z, 0xFF000000 | (bb << 16) | (gg << 8) | r); // ABGR
            }
        }
        Minecraft.getInstance().getTextureManager().register(TEX, new DynamicTexture(img));
        ready = true;
    }

    /** Заливает прямоугольник бумагой (плиткой 256×256 без растяжения). */
    public static void fill(GuiGraphics g, int x0, int y0, int x1, int y1) {
        ensure();
        for (int y = y0; y < y1; y += SIZE) {
            for (int x = x0; x < x1; x += SIZE) {
                int w = Math.min(SIZE, x1 - x), h = Math.min(SIZE, y1 - y);
                g.blit(TEX, x, y, 0, 0, w, h, SIZE, SIZE);
            }
        }
    }

    /** Двойная рамка тушью — как у карты, обрезанной по линейке. */
    public static void frame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.renderOutline(x0, y0, x1 - x0, y1 - y0, INK);
        g.renderOutline(x0 + 3, y0 + 3, x1 - x0 - 6, y1 - y0 - 6, INK_FAINT);
    }

    /** Табличка: бумага, рамка тушью, гвоздики по углам — для надписей поверх карты. */
    public static void plaque(GuiGraphics g, int x0, int y0, int x1, int y1) {
        fill(g, x0, y0, x1, y1);
        g.renderOutline(x0, y0, x1 - x0, y1 - y0, INK);
        g.renderOutline(x0 + 2, y0 + 2, x1 - x0 - 4, y1 - y0 - 4, INK_FAINT);
        int[][] nails = {{x0 + 1, y0 + 1}, {x1 - 3, y0 + 1}, {x0 + 1, y1 - 3}, {x1 - 3, y1 - 3}};
        for (int[] n : nails) g.fill(n[0], n[1], n[0] + 2, n[1] + 2, 0xFF6B5233);
    }

    private static float at(float[] v, int g, int x, int z) {
        return v[Math.floorMod(z, g) * g + Math.floorMod(x, g)];
    }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static int clamp(int c) { return Math.max(0, Math.min(255, c)); }
}
