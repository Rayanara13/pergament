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

    /**
     * Всё, сквозь что проходишь, — не поверхность: трава (бледный switchgrass TFC — «снег» по всему лугу), цветы,
     * лежачие камни, палки, ракушки, шишки, кусочки руды. Карта показывает землю под ними.
     * Видны: листва (в TFC она проходима, но это крона леса), снег, рельсы и посевы — это сведения, а не мусор.
     */
    public static boolean litter(BlockState s, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        // листва — поверхность, хоть сквозь неё в TFC и проходишь (без этого на карте одни стволы)
        if (s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.SNOW)
                || s.is(net.minecraft.tags.BlockTags.RAILS) || s.is(net.minecraft.tags.BlockTags.CROPS)) return false;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id != null && (id.getPath().contains("snow") || id.getPath().contains("crop"))) return false;
        try {
            return s.getCollisionShape(level, pos).isEmpty();
        } catch (RuntimeException e) {
            return false;                                          // блок мода капризничает без мира — оставляем
        }
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

    /** Песок сверху (ванильный, TFC «sand/…», красный) — не песчаник. */
    public static boolean sand(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.SAND)) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id == null) return false;
        String p = id.getPath();
        return (p.equals("sand") || p.startsWith("sand/") || p.endsWith("_sand")) && !p.contains("stone");
    }

    /** Скан без местности (измерение с потолком — разрез, не поверхность). */
    public static final long NO_GEO = Long.MIN_VALUE;
    /** Самотест (-Ppergament_bright): счётчик блоков со светлым цветом сверху. */
    public static final java.util.Map<String, Integer> BRIGHT = System.getProperty("pergament.selftest.bright") != null
            ? new java.util.HashMap<>() : null;

    /** Скан чанка в регион; возвращает местность чанка для подписей ({@link ru.stef.pergament.client.geo.Geo}). */
    public static long scan(ClientLevel level, LevelChunk chunk, RegionData region, int playerY) {
        ChunkPos cp = chunk.getPos();
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        int topY = ceiling ? Math.min(playerY + 2, minY + level.dimensionType().logicalHeight() - 1) : 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // тинт травы/листвы Minecraft усредняет по соседним колонкам — дорого; в чанке считаем раз на «блок + биом»
        java.util.Map<Object, Integer> tintCache = new java.util.HashMap<>();
        int waterCols = 0, cols = 0, canopyCols = 0, sandCols = 0, peakY = Integer.MIN_VALUE, peakLx = 8, peakLz = 8;
        int[] biomeVotes = new int[8];
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
                            || s.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE)
                            && !litter(s, level, pos)) break;
                }
                if (y < minY || s == null) {
                    region.set(rxl, rzl, 0xFF000000, minY, 0, RegionData.CLS_VOID);
                    continue;
                }
                cols++;
                if ((lx & 3) == 2 && (lz & 3) == 2) {                  // местность: биом — в 16 точках чанка
                    biomeVotes[ru.stef.pergament.client.geo.BiomeCat.of(level.getBiome(pos))]++;
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
                if (BRIGHT != null && depth == 0) {                    // самотест: какие блоки дают светлые крапинки
                    int r = c & 0xFF, gg = (c >> 8) & 0xFF, bb = (c >> 16) & 0xFF;
                    if (r + gg + bb > 400) BRIGHT.merge(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock()) + "", 1, Integer::sum);
                }
                if (depth > 0 || floatColor != 0) {
                    waterCols++;
                } else {
                    if (s.is(net.minecraft.tags.BlockTags.LEAVES)) canopyCols++;
                    else if (sand(s)) sandCols++;
                    if (ground > peakY) { peakY = ground; peakLx = lx; peakLz = lz; }
                }
            }
        }
        region.touch();
        if (ceiling || cols == 0) return NO_GEO;
        byte biome = 0;
        for (byte b = 1; b < biomeVotes.length; b++) if (biomeVotes[b] > biomeVotes[biome]) biome = b;
        if (peakY == Integer.MIN_VALUE) peakY = level.getSeaLevel();
        float water = waterCols / (float) cols, canopy = canopyCols / (float) cols, sand = sandCols / (float) cols;
        return ru.stef.pergament.client.geo.Geo.pack(ru.stef.pergament.client.geo.Geo.classify(biome, water, canopy, sand),
                water, peakY, peakLx, peakLz, canopy, sand);
    }
}
