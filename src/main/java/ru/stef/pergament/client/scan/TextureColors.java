package ru.stef.pergament.client.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Настоящий цвет блока на карте: средний цвет его верхней грани (непрозрачные пиксели спрайта),
 * умноженный на тинт биома, если грань тинтуется (трава, листва). Жидкости — по их «стоячей» текстуре
 * и тинту. MapColor — только последний запасной вариант, когда у блока нет ни модели, ни частицы.
 * Кэш по состоянию блока; тинт считается для каждой колонки (он зависит от биома). Только клиентский поток.
 */
public final class TextureColors {
    /** Средний цвет спрайта в ABGR и индекс тинта (-1 — без тинта). */
    private record Base(int abgr, int tint) {}

    private static final Map<BlockState, Base> BLOCKS = new HashMap<>();
    private static final Map<Object, Integer> SPRITES = new HashMap<>();
    private static final RandomSource RAND = RandomSource.create(42);

    private TextureColors() {}

    /** Сброс после перезагрузки ресурспаков — цвета берутся из текущих текстур. */
    public static void clear() {
        BLOCKS.clear();
        SPRITES.clear();
    }

    public static int block(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        Base b = BLOCKS.computeIfAbsent(state, TextureColors::base);
        if (b.tint < 0) return b.abgr;
        int rgb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, b.tint);
        return rgb == -1 ? b.abgr : multiply(b.abgr, rgb);
    }

    /** Тинтуется ли верхняя грань (трава, листва) — тогда цвет зависит от биома. */
    public static boolean tinted(BlockState state) {
        return BLOCKS.computeIfAbsent(state, TextureColors::base).tint >= 0;
    }

    public static int fluid(FluidState fs, BlockAndTintGetter level, BlockPos pos) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fs);
        TextureAtlasSprite sp = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ext.getStillTexture(fs, level, pos));
        int base = SPRITES.computeIfAbsent(sp.contents().name(), k -> average(sp));
        int argb = ext.getTintColor(fs, level, pos);
        return argb == -1 ? base : multiply(base, argb & 0xFFFFFF);
    }

    private static Base base(BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = model.getQuads(state, Direction.UP, RAND);
        if (quads.isEmpty()) quads = model.getQuads(state, null, RAND);
        TextureAtlasSprite sp;
        int tint = -1;
        if (!quads.isEmpty()) {
            BakedQuad q = quads.get(0);
            sp = q.getSprite();
            if (q.isTinted()) tint = q.getTintIndex();
        } else {
            sp = model.getParticleIcon();
        }
        if (sp != null && !"missingno".equals(sp.contents().name().getPath())) {
            TextureAtlasSprite s = sp;
            return new Base(SPRITES.computeIfAbsent(s.contents().name(), k -> average(s)), tint);
        }
        int mc = 0x808080;
        try {
            MapColor m = state.getMapColor(null, BlockPos.ZERO);
            if (m != null && m != MapColor.NONE) mc = m.col;
        } catch (RuntimeException e) {
            // модовый блок не любит null вместо мира — остаётся нейтральный серый
        }
        return new Base(0xFF000000 | ((mc & 0xFF) << 16) | (mc & 0xFF00) | ((mc >> 16) & 0xFF), -1);
    }

    /** Средний цвет непрозрачных пикселей первого кадра, ABGR. */
    static int average(TextureAtlasSprite sp) {
        int w = sp.contents().width(), h = sp.contents().height();
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = sp.getPixelRGBA(0, x, y);          // ABGR
                if ((p >>> 24) < 32) continue;
                r += p & 0xFF;
                g += (p >>> 8) & 0xFF;
                b += (p >>> 16) & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFF808080;
        return 0xFF000000 | ((int) (b / n) << 16) | ((int) (g / n) << 8) | (int) (r / n);
    }

    /** ABGR × RGB-тинт (0xRRGGBB). */
    static int multiply(int abgr, int rgb) {
        int r = (abgr & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
        int g = ((abgr >>> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
        int b = ((abgr >>> 16) & 0xFF) * (rgb & 0xFF) / 255;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }
}
