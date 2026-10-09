package ru.stef.pergament.client.xaero;

import ru.stef.pergament.client.T;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.DimMap;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.scan.TextureColors;
import ru.stef.pergament.client.store.WorldProfile;
import ru.stef.pergament.store.ChunkCodec;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.store.RegionFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Импорт разведанного из Xaero's World Map в карту ТЕКУЩЕГО мира.
 * <ul>
 *   <li>Чтение и разбор файлов — фоновый поток; раскраска — клиентский поток, тем же способом, что и свой скан
 *       (средняя текстура верхней грани × тинт биома), цвета Xaero не берём.</li>
 *   <li>Слияние: только в чанки, которых у нас ещё нет. Своё разведанное импорт не перетирает никогда.</li>
 *   <li>Измерение, где стоит игрок, — прямо в живую карту; остальные — файлами в фоне.</li>
 * </ul>
 */
public final class XaeroImport {
    /** Что импортировать: папка регионов Xaero → наше измерение. */
    public record Source(String dimension, Path dir, String label, int files) {}

    private static XaeroImport running;

    private final String profile;
    private final List<Source> sources;
    private final BlockingQueue<Parsed> parsed = new ArrayBlockingQueue<>(2);
    private final Thread reader;
    private volatile boolean cancelled, readerDone;
    private Parsed pending;
    private RegionData pendingColored;

    public final int total;
    public volatile int read, merged, chunksAdded, chunksKept, errors;
    private boolean finishQueued;
    /** Регионов в очереди на запись: каждый ~3 МБ, поэтому раскраска ждёт, если диск не успевает. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_IN_FLIGHT = 4;
    public volatile String status = "";
    public volatile boolean done;

    private record Parsed(Source src, XaeroRegion region) {}

    private XaeroImport(String profile, List<Source> sources) {
        this.profile = profile;
        this.sources = List.copyOf(sources);
        this.total = sources.stream().mapToInt(Source::files).sum();
        this.reader = daemon(this::readAll, "pergament-xaero-read");
    }

    public static XaeroImport current() {
        return running;
    }

    /** Запустить для текущего мира; false — уже идёт или мира нет. */
    public static boolean start(List<Source> sources) {
        if (running != null && !running.done) return false;
        String prof = WorldProfile.current();
        if (prof == null || sources.isEmpty()) return false;
        running = new XaeroImport(prof, sources);
        running.reader.start();
        Pergament.LOG.info("Пергамент: импорт Xaero в {} — {} регионов", prof, running.total);
        return true;
    }

    public static void cancel() {
        if (running != null && !running.done) {
            running.cancelled = true;
            running.finish(T.t("xaero.cancelled"));
        }
    }

    // ---------- фон: чтение файлов ----------

    private void readAll() {
        try {
            for (Source s : sources) {
                List<Path> files;
                try (var st = Files.list(s.dir)) {
                    files = st.filter(p -> p.getFileName().toString().matches("-?\\d+_-?\\d+\\.zip")).sorted().toList();
                }
                for (Path f : files) {
                    if (cancelled) return;
                    String[] xz = f.getFileName().toString().replace(".zip", "").split("_");
                    try (var in = Files.newInputStream(f)) {
                        XaeroRegion r = XaeroRegion.read(in, Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
                        parsed.put(new Parsed(s, r));
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        fail();
                        Pergament.LOG.warn("Пергамент: Xaero {} не прочитан: {}", f, e.toString());
                    }
                    read++;
                }
            }
        } catch (Exception e) {
            fail();
            Pergament.LOG.warn("Пергамент: импорт Xaero оборвался: {}", e.toString());
        } finally {
            readerDone = true;
        }
    }

    // ---------- клиентский тик: раскраска и слияние, по региону за тик ----------

    public static void tick() {
        XaeroImport job = running;
        if (job == null || job.done) return;
        if (!job.profile.equals(WorldProfile.current())) {      // вышел из мира — бросаем, не пишем в чужой
            cancel();
            return;
        }
        job.step();
    }

    private void step() {
        if (pending == null) pending = parsed.poll();
        if (pending == null) {
            if (readerDone && parsed.isEmpty() && !finishQueued) {
                finishQueued = true;                               // финиш — после всех заказанных записей файлов
                DimMap.onIo(() -> Minecraft.getInstance().execute(() -> finish(T.t("xaero.finished"))));
            }
            return;
        }
        if (inFlight.get() >= MAX_IN_FLIGHT) return;
        if (pendingColored == null) pendingColored = color(pending.region);
        RegionData colored = pendingColored;
        String dim = pending.src.dimension;
        DimMap live = LocalMap.get().live();
        if (live != null && dim.equals(live.dimension())) {
            RegionData target = live.inMemory(colored.rx, colored.rz);
            if (target != null) {                                  // регион открыт — правим в памяти, сохранит карта
                int[] r = mergeInto(target, colored);
                if (r[0] > 0) target.touch();
                count(r);
            } else {
                inFlight.incrementAndGet();
                if (!live.externalWrite(colored.rx, colored.rz, dir -> mergeFile(dir, dim, colored))) {
                    inFlight.decrementAndGet();
                    return;                                        // читается с диска — следующий тик
                }
            }
        } else {
            Path dir = WorldProfile.dir(profile, dim);
            inFlight.incrementAndGet();
            DimMap.onIo(() -> mergeFile(dir, dim, colored));
        }
        pending = null;
        pendingColored = null;
    }

    /** Поток ввода-вывода: прочитать файл региона, влить пустые чанки, записать. */
    private void mergeFile(Path dir, String dim, RegionData colored) {
        try {
            if (cancelled) return;
            Files.createDirectories(dir);
            Path dimTxt = dir.resolve("dim.txt");
            if (!Files.isRegularFile(dimTxt)) Files.writeString(dimTxt, dim, StandardCharsets.UTF_8);
            RegionData target = RegionFile.read(dir, colored.rx, colored.rz);
            if (target == null) target = new RegionData(colored.rx, colored.rz);
            int[] r = mergeInto(target, colored);
            if (r[0] > 0) RegionFile.write(dir, target);
            count(r);
        } catch (Exception e) {
            fail();
            Pergament.LOG.warn("Пергамент: регион {} {} не записан: {}", colored.rx, colored.rz, e.toString());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private synchronized void fail() {
        errors++;
    }

    private synchronized void count(int[] r) {
        chunksAdded += r[0];
        chunksKept += r[1];
        merged++;
    }

    /** Чанки из colored, которых нет в target: {добавлено, оставлено своё}. */
    static int[] mergeInto(RegionData target, RegionData colored) {
        int added = 0, kept = 0;
        int cx0 = colored.rx << 5, cz0 = colored.rz << 5;
        for (int cz = cz0; cz < cz0 + 32; cz++) {
            for (int cx = cx0; cx < cx0 + 32; cx++) {
                if (!ChunkCodec.known(colored, cx, cz)) continue;
                if (ChunkCodec.known(target, cx, cz)) {
                    kept++;
                    continue;
                }
                byte[] raw = ChunkCodec.extract(colored, cx, cz);
                ChunkCodec.apply(target, cx, cz, raw);
                added++;
            }
        }
        return new int[]{added, kept};
    }

    private void finish(String how) {
        if (done) return;
        done = true;
        reader.interrupt();
        status = how;
        if (chunksAdded > 0) ru.stef.pergament.client.TeamSyncClient.archiveAgain(profile);   // импорт — и команде
        Minecraft mc = Minecraft.getInstance();
        String msg = T.t("xaero.msg", how, chunksAdded, chunksKept) + (errors > 0 ? T.t("xaero.errors", errors) : "");
        Pergament.LOG.info(msg);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }

    // ---------- раскраска нашим способом ----------

    private static RegionData color(XaeroRegion xr) {
        Minecraft mc = Minecraft.getInstance();
        BlockState[] states = new BlockState[xr.palette.size()];
        for (int k = 0; k < states.length; k++) states[k] = state(xr.palette.get(k));
        Biome[] biomes = new Biome[xr.biomes.size()];
        var reg = mc.level == null ? null : mc.level.registryAccess().registry(Registries.BIOME).orElse(null);
        for (int k = 0; k < biomes.length; k++) {
            ResourceLocation id = ResourceLocation.tryParse(xr.biomes.get(k));
            biomes[k] = reg == null || id == null ? null : reg.get(id);
        }
        Biome fallback = reg == null ? null : reg.get(new ResourceLocation("minecraft", "plains"));
        RegionData out = new RegionData(xr.rx, xr.rz);
        Tint tint = new Tint();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Map<Long, Integer> cache = new HashMap<>();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                cache.clear();                                     // тинт — раз на «блок + биом» в чанке, как в скане
                for (int lz = cz * 16; lz < cz * 16 + 16; lz++) {
                    for (int lx = cx * 16; lx < cx * 16 + 16; lx++) {
                        int i = XaeroRegion.index(lx, lz), st = xr.state[i];
                        if (st == XaeroRegion.NONE) continue;
                        BlockState s = st == XaeroRegion.GRASS ? Blocks.GRASS_BLOCK.defaultBlockState() : states[st];
                        if (s == null) continue;                   // блока больше нет в сборке — оставляем неразведанным
                        int bi = xr.biome[i];
                        tint.biome = bi >= 0 && biomes[bi] != null ? biomes[bi] : fallback;
                        int h = xr.height[i], t = xr.top[i];
                        int wx = (xr.rx << 9) + lx, wz = (xr.rz << 9) + lz;
                        pos.set(wx, h, wz);
                        int ov = xr.over[i];
                        BlockState os = ov == XaeroRegion.WATER ? Blocks.WATER.defaultBlockState() : ov >= 0 ? states[ov] : null;
                        FluidState fs = os != null ? os.getFluidState() : s.getFluidState();
                        if (!fs.isEmpty()) {
                            int top = os != null ? t : h;
                            pos.set(wx, top, wz);
                            int c = safe(() -> TextureColors.fluid(fs, tint, pos), 0xFF7F4F2F);
                            if (fs.is(FluidTags.LAVA)) {
                                out.set(lx, lz, c, top, top, 0, RegionData.CLS_LAVA);
                            } else {
                                int depth = Math.max(1, top - h);
                                out.set(lx, lz, c, top, top, depth, RegionData.CLS_LAND);
                            }
                        } else if (os != null) {                    // лёд, стекло — видно сверху
                            pos.set(wx, t, wz);
                            out.set(lx, lz, color(os, tint, pos, cache, ov, bi), t, h, 0, RegionData.CLS_LAND);
                        } else if (s.isAir()) {
                            out.set(lx, lz, 0xFF000000, h, h, 0, RegionData.CLS_VOID);
                        } else {
                            out.set(lx, lz, color(s, tint, pos, cache, st, bi), h, h, 0, RegionData.CLS_LAND);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int color(BlockState s, Tint tint, BlockPos pos, Map<Long, Integer> cache, int st, int bi) {
        if (!TextureColors.tinted(s)) return safe(() -> TextureColors.block(s, tint, pos), 0xFF808080);
        return cache.computeIfAbsent(((long) st << 32) | (bi & 0xFFFFFFFFL),
                k -> safe(() -> TextureColors.block(s, tint, pos.immutable()), 0xFF808080));
    }

    private static int safe(java.util.function.IntSupplier f, int fallback) {
        try {
            return f.getAsInt();
        } catch (RuntimeException e) {                            // тинт мода может хотеть настоящий мир
            return fallback;
        }
    }

    /** Состояние блока из NBT Xaero; блока нет в сборке — null. */
    static BlockState state(XaeroRegion.BlockNbt nbt) {
        ResourceLocation id = ResourceLocation.tryParse(nbt.name());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block b = BuiltInRegistries.BLOCK.get(id);
        BlockState s = b.defaultBlockState();
        for (var e : nbt.props().entrySet()) {
            Property<?> p = b.getStateDefinition().getProperty(e.getKey());
            if (p != null) s = with(s, p, e.getValue());
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
    }

    /** Мир-заглушка для тинта: биом колонки берётся из файла Xaero, а не из загруженных чанков. */
    private static final class Tint implements BlockAndTintGetter {
        Biome biome;

        @Override
        public float getShade(Direction d, boolean shade) {
            return 1f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return Minecraft.getInstance().level.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return biome == null ? -1 : resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return Minecraft.getInstance().level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return Minecraft.getInstance().level.getMinBuildHeight();
        }
    }

    // ---------- поиск источников ----------

    /** Мир Xaero: папка и его измерения. */
    public record World(String name, Path dir, List<Source> sources, boolean matchesCurrent) {}

    /** Все миры Xaero в папке игры; совпадающий с текущим подключением — помечен. */
    public static List<World> worlds() {
        List<World> out = new ArrayList<>();
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("xaero").resolve("world-map");
        String prof = WorldProfile.current();
        try (var st = Files.list(root)) {
            for (Path w : st.filter(Files::isDirectory).sorted().toList()) {
                List<Source> src = new ArrayList<>();
                try (var ds = Files.list(w)) {
                    for (Path d : ds.filter(Files::isDirectory).sorted().toList()) {
                        String dim = dimension(d.getFileName().toString());
                        if (dim == null) continue;
                        Source s = best(dim, d);
                        if (s != null) src.add(s);
                    }
                }
                if (!src.isEmpty()) out.add(new World(w.getFileName().toString(), w, src, matches(w.getFileName().toString(), prof)));
            }
        } catch (Exception e) {
            // папки Xaero нет — импортировать нечего
        }
        return out;
    }

    /** Имя папки измерения Xaero → id измерения. */
    static String dimension(String folder) {
        return switch (folder) {
            case "null" -> "minecraft:overworld";
            case "DIM-1" -> "minecraft:the_nether";
            case "DIM1" -> "minecraft:the_end";
            default -> folder.contains("$") ? folder.replaceFirst("\\$", ":") : null;
        };
    }

    /**
     * Самая полная папка регионов измерения: среди mw$… — с наибольшим числом файлов; обычный слой,
     * а если его нет (Незер у Xaero — только пещерные слои) — пещерный слой с наибольшим числом файлов.
     */
    private static Source best(String dim, Path dimDir) throws Exception {
        Source best = null;
        try (var ms = Files.list(dimDir)) {
            for (Path mw : ms.filter(Files::isDirectory).toList()) {
                int n = zips(mw);
                if (n > 0 && (best == null || n > best.files)) best = new Source(dim, mw, label(dim) , n);
                Path caves = mw.resolve("caves");
                if (n == 0 && Files.isDirectory(caves)) {
                    try (var cs = Files.list(caves)) {
                        for (Path layer : cs.filter(Files::isDirectory).toList()) {
                            int k = zips(layer);
                            if (k > 0 && (best == null || k > best.files)) {
                                best = new Source(dim, layer, T.t("xaero.caves", label(dim), layer.getFileName()), k);
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static int zips(Path dir) throws Exception {
        try (var st = Files.list(dir)) {
            return (int) st.filter(p -> p.getFileName().toString().matches("-?\\d+_-?\\d+\\.zip")).count();
        }
    }

    private static String label(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> T.t("dim.overworld");
            case "minecraft:the_nether" -> T.t("dim.the_nether");
            case "minecraft:the_end" -> T.t("dim.the_end");
            default -> dim;
        };
    }

    /** Папка Xaero того же мира: сервер — по адресу (порт 25565 Xaero не пишет), одиночка — по папке сохранения. */
    static boolean matches(String xaeroFolder, String profile) {
        if (profile == null) return false;
        if (profile.startsWith("mp_") && xaeroFolder.startsWith("Multiplayer_")) {
            String host = WorldProfile.safe(xaeroFolder.substring("Multiplayer_".length()));
            String p = profile.substring(3);
            return p.equals(host) || p.equals(host + "_25565");
        }
        return profile.startsWith("sp_") && profile.substring(3).equals(WorldProfile.safe(xaeroFolder));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

}
