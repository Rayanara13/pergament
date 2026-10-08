package ru.stef.pergament.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.PergamentConfig;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.store.RegionFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Карта одного измерения одного мира: регионы (чтение/запись в фоне), их текстуры-пергамент,
 * метки, план шахтёра, рисунки. Живое измерение пишет скан; чужое (глобус) — только читает с диска.
 * Всё публичное — клиентский (render) поток.
 */
public final class DimMap {
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> daemon(r, "Pergament-IO"));
    private static final ExecutorService PAINT = Executors.newFixedThreadPool(2, r -> daemon(r, "Pergament-Paint"));
    private static final long RECOMPOSE_MS = 1_000, UNLOAD_MS = 120_000;
    private static long paintSeq;

    final String dim;
    final Path dir;
    private final String texKey;
    private boolean closed;
    private final Long2ObjectOpenHashMap<RegionData> regions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet loading = new LongOpenHashSet();
    private final LongOpenHashSet absent = new LongOpenHashSet();   // файла нет и в этой сессии не сканировали
    private final Long2LongOpenHashMap lastUse = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<Tex> textures = new Long2ObjectOpenHashMap<>();
    private MinerPlan miner;
    private Markers markers;
    private Drawings drawings;
    private Finds finds;

    private static final class Tex {
        ResourceLocation loc;
        int revision = -1;
        double keep = -1;
        long composedAt;
        boolean painting;
    }

    DimMap(String profile, String dim, Path dir) {
        this.dim = dim;
        this.dir = dir;
        this.texKey = Integer.toHexString((profile + "|" + dim).hashCode());
    }

    public String dimension() { return dim; }
    public int loadedRegions() { return regions.size(); }

    public Markers markers() {
        if (markers == null) markers = Markers.load(dir.resolve("markers.json"));
        return markers;
    }

    public Drawings drawings() {
        if (drawings == null) drawings = Drawings.load(dir.resolve("drawings.json"));
        return drawings;
    }

    public Finds finds() {
        if (finds == null) finds = Finds.load(dir.resolve("finds.json"));
        return finds;
    }

    public MinerPlan miner() {
        if (miner == null) miner = MinerPlan.load(dir.resolve("miner.json"));
        return miner;
    }

    /** Импорт: регион, только если уже в памяти (чтения не заказывает). */
    public RegionData inMemory(int rx, int rz) {
        return regions.get(ChunkPos.asLong(rx, rz));
    }

    /**
     * Импорт: дописать файл региона мимо памяти. Пока пишется, регион считается «читается» — скан не создаст
     * пустой поверх, а следующее чтение встанет в очередь потока после записи. false — регион в памяти/читается.
     */
    public boolean externalWrite(int rx, int rz, java.util.function.Consumer<Path> write) {
        long k = ChunkPos.asLong(rx, rz);
        if (regions.containsKey(k) || loading.contains(k)) return false;
        absent.remove(k);
        loading.add(k);
        IO.execute(() -> {
            try {
                write.accept(dir);
            } finally {
                Minecraft.getInstance().execute(() -> loading.remove(k));
            }
        });
        return true;
    }

    /** Задача на поток ввода-вывода карт — после всех уже заказанных чтений и записей. */
    public static void onIo(Runnable r) {
        IO.execute(r);
    }

    public boolean isLoading(int rx, int rz) {
        return loading.contains(ChunkPos.asLong(rx, rz));
    }

    /** Высота разведанной поверхности в точке или null (регион не в памяти / не разведано). */
    public Integer heightAt(int bx, int bz) {
        RegionData r = regions.get(ChunkPos.asLong(RegionData.regionOf(bx), RegionData.regionOf(bz)));
        if (r == null) return null;
        int i = ((bz & (RegionData.SIZE - 1)) << RegionData.SHIFT) | (bx & (RegionData.SIZE - 1));
        return r.known[i] == 0 ? null : (int) r.height[i] + 1;
    }

    /**
     * Регион, если он в памяти. Иначе — заказать чтение с диска и вернуть null.
     * create=true: файла нет — создать пустой (скану есть куда писать).
     */
    public RegionData region(int rx, int rz, boolean create) {
        long k = ChunkPos.asLong(rx, rz);
        lastUse.put(k, System.currentTimeMillis());
        RegionData r = regions.get(k);
        if (r != null) return r;
        if (absent.contains(k)) {
            if (!create) return null;
            r = new RegionData(rx, rz);
            regions.put(k, r);
            absent.remove(k);
            return r;
        }
        if (loading.add(k)) {
            IO.execute(() -> {
                RegionData loaded = null;
                try {
                    loaded = RegionFile.read(dir, rx, rz);
                } catch (Exception e) {
                    Pergament.LOG.warn("Пергамент: регион {} {} не прочитан: {}", rx, rz, e.toString());
                }
                RegionData res = loaded;
                Minecraft.getInstance().execute(() -> {
                    if (closed) return;
                    loading.remove(k);
                    if (res != null) regions.put(k, res);
                    else absent.add(k);
                });
            });
        }
        return null;
    }

    void saveDirty() {
        for (RegionData r : regions.values()) {
            if (!r.dirty()) continue;
            RegionData snap = r.snapshot();
            r.clean();
            IO.execute(() -> {
                try {
                    RegionFile.write(dir, snap);
                } catch (Exception e) {
                    Pergament.LOG.warn("Пергамент: регион {} {} не записан: {}", snap.rx, snap.rz, e.toString());
                }
            });
        }
    }

    static boolean waitIo(long timeoutMs) {
        try {
            IO.submit(() -> { }).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void unloadIdle(long now) {
        List<Long> drop = new ArrayList<>();
        for (Long2ObjectOpenHashMap.Entry<RegionData> e : regions.long2ObjectEntrySet()) {
            if (!e.getValue().dirty() && now - lastUse.getOrDefault(e.getLongKey(), now) > UNLOAD_MS) drop.add(e.getLongKey());
        }
        for (long k : drop) {
            regions.remove(k);
            Tex t = textures.remove(k);
            if (t != null && t.loc != null) Minecraft.getInstance().getTextureManager().release(t.loc);
        }
    }

    /** Освободить текстуры; грязное — на диск (у чужого измерения грязного нет). */
    void close() {
        saveDirty();
        closed = true;
        for (Tex t : textures.values()) if (t.loc != null) Minecraft.getInstance().getTextureManager().release(t.loc);
        textures.clear();
        regions.clear();
        loading.clear();
        absent.clear();
    }

    /** Текстура региона для отрисовки или null (не разведан / ещё красится). */
    public ResourceLocation texture(int rx, int rz) {
        RegionData r = region(rx, rz, false);
        long k = ChunkPos.asLong(rx, rz);
        Tex t = textures.get(k);
        if (r == null) return t == null ? null : t.loc;
        if (t == null) {
            t = new Tex();
            textures.put(k, t);
        }
        long now = System.currentTimeMillis();
        double keep = PergamentConfig.COLOR_KEEP.get();
        boolean stale = t.revision != r.revision() || t.keep != keep;
        if (!t.painting && stale && (t.loc == null || now - t.composedAt > RECOMPOSE_MS)) paint(k, t, r, keep);
        return t.loc;
    }

    private void paint(long k, Tex t, RegionData r, double keep) {
        t.painting = true;
        RegionData snap = r.snapshot();
        PAINT.execute(() -> {
            int[] px = PaperStyle.compose(snap, keep);
            Minecraft.getInstance().execute(() -> {
                t.painting = false;
                if (closed || textures.get(k) != t) return;
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, RegionData.SIZE, RegionData.SIZE, false);
                for (int z = 0, i = 0; z < RegionData.SIZE; z++) {
                    for (int x = 0; x < RegionData.SIZE; x++, i++) img.setPixelRGBA(x, z, px[i]);
                }
                ResourceLocation loc = new ResourceLocation(Pergament.MOD_ID,
                        "map/" + texKey + "/" + snap.rx + "_" + snap.rz + "_" + (++paintSeq));  // имя всегда новое: старое
                Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));  // освобождаем ниже —
                if (t.loc != null) Minecraft.getInstance().getTextureManager().release(t.loc);  // совпади они, «нет текстуры»
                t.loc = loc;
                t.revision = snap.revision();
                t.keep = keep;
                t.composedAt = System.currentTimeMillis();
            });
        });
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }
}
