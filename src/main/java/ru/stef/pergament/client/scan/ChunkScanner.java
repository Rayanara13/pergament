package ru.stef.pergament.client.scan;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import ru.stef.pergament.store.RegionData;

/**
 * Скан одного чанка в регион: для каждой колонки 16×16 — что видно сверху.
 * <ul>
 *   <li>Без потолка (верхний мир, Энд): верх по карте высот WORLD_SURFACE, вниз через «невидимое».
 *       Вода — глубина до дна, цвет — дна (воду красит стиль). Нет ни одного блока — пустота Энда.</li>
 *   <li>С потолком (Незер): крыша карте не нужна — берём пол на уровне игрока: от его головы вниз
 *       до первого твёрдого. Стоим в стене — рисуем стену на этом уровне (разрез), ниже не заглядываем.</li>
 * </ul>
 * Только клиентский поток (читает мир и модели).
 */
public final class ChunkScanner {
    private ChunkScanner() {}

    /**
     * Земля под кроной: вниз через листву, стволы и всё, сквозь что проходишь (трава, цветы).
     * По ней — горизонтали и тропы; иначе тропа «идёт по макушкам деревьев».
     */
    static int groundBelow(LevelChunk chunk, ClientLevel level, BlockPos.MutableBlockPos pos, int wx, int y, int wz, int minY) {
        int g = y;
        while (g > minY) {
            pos.set(wx, g, wz);
            BlockState s = chunk.getBlockState(pos);
            boolean canopy = s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)
                    || (s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty());
            if (!canopy) break;
            g--;
        }
        pos.set(wx, y, wz);
        return g;
    }

    /** Плоский блок (высота коллизии ≤ 0.2) прямо на воде — кувшинка, ряска: колонка водная. */
    static boolean floatsOnWater(LevelChunk chunk, ClientLevel level, BlockPos.MutableBlockPos pos, BlockState s,
                                 int wx, int y, int wz) {
        var shape = s.getCollisionShape(level, pos);
        if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.2) return false;
        pos.set(wx, y - 1, wz);
        FluidState below = chunk.getBlockState(pos).getFluidState();
        pos.set(wx, y, wz);
        return !below.isEmpty() && below.is(FluidTags.WATER);
    }

    private record TintKey(BlockState state, Object biome) {}

    private static int color(BlockState s, ClientLevel level, BlockPos pos, java.util.Map<Object, Integer> cache) {
        if (!TextureColors.tinted(s)) return TextureColors.block(s, level, pos);
        return cache.computeIfAbsent(new TintKey(s, level.getBiome(pos).value()),
                k -> TextureColors.block(s, level, pos.immutable()));
    }

    public static void scan(ClientLevel level, LevelChunk chunk, RegionData region, int playerY) {
        ChunkPos cp = chunk.getPos();
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        int topY = ceiling ? Math.min(playerY + 2, minY + level.dimensionType().logicalHeight() - 1) : 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // тинт травы/листвы Minecraft усредняет по соседним колонкам — дорого; в чанке считаем раз на «блок + биом»
        java.util.Map<Object, Integer> tintCache = new java.util.HashMap<>();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = cp.getMinBlockX() + lx, wz = cp.getMinBlockZ() + lz;
                int rxl = wx & (RegionData.SIZE - 1), rzl = wz & (RegionData.SIZE - 1);
                int y = ceiling ? topY : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                if (ceiling) {
                    pos.set(wx, y, wz);
                    if (!chunk.getBlockState(pos).isAir()) {          // в стене — разрез на уровне игрока
                        BlockState s = chunk.getBlockState(pos);
                        region.set(rxl, rzl, TextureColors.block(s, level, pos), y, 0, RegionData.CLS_WALL);
                        continue;
                    }
                }
                // вниз через воздух и всё, что сверху не видно
                BlockState s = null;
                for (; y >= minY; y--) {
                    pos.set(wx, y, wz);
                    s = chunk.getBlockState(pos);
                    if (!s.isAir() && (!s.getFluidState().isEmpty() || !s.getCollisionShape(level, pos).isEmpty()
                            || s.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE)) break;
                }
                if (y < minY || s == null) {
                    region.set(rxl, rzl, 0xFF000000, minY, 0, RegionData.CLS_VOID);
                    continue;
                }
                FluidState fs = s.getFluidState();
                // кувшинка и прочая плоская мелочь на воде — это вода: цвет листа виден сверху, но идти нельзя
                int floatColor = 0;
                if (fs.isEmpty() && y > minY && floatsOnWater(chunk, level, pos, s, wx, y, wz)) {
                    floatColor = TextureColors.block(s, level, pos);
                    y--;
                    pos.set(wx, y, wz);
                    s = chunk.getBlockState(pos);
                    fs = s.getFluidState();
                }
                if (!fs.isEmpty() && fs.is(FluidTags.LAVA)) {
                    region.set(rxl, rzl, TextureColors.fluid(fs, level, pos), y, 0, RegionData.CLS_LAVA);
                    continue;
                }
                int depth = 0, waterColor = 0;
                if (!fs.isEmpty() && fs.is(FluidTags.WATER)) {
                    waterColor = TextureColors.fluid(fs, level, pos);   // настоящая вода этого биома
                    int surface = y;
                    while (y > minY && !s.getFluidState().isEmpty() && s.getFluidState().is(FluidTags.WATER)
                            && s.getCollisionShape(level, pos).isEmpty()) {
                        y--;
                        pos.set(wx, y, wz);
                        s = chunk.getBlockState(pos);
                    }
                    depth = Math.max(1, surface - y);
                }
                int top = depth > 0 ? y + depth : y;
                int ground = depth > 0 ? top : groundBelow(chunk, level, pos, wx, y, wz, minY);
                int c = floatColor != 0 ? floatColor : depth > 0 ? waterColor : color(s, level, pos, tintCache);
                region.set(rxl, rzl, c, top, ground, depth, RegionData.CLS_LAND);
            }
        }
        region.touch();
    }
}
