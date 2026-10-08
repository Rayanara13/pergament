package ru.stef.pergament.client.map;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.PergamentConfig;
import ru.stef.pergament.client.scan.ChunkScanner;
import ru.stef.pergament.client.scan.Reveal;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.client.store.WorldProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Карта, которую игрок разведал сам. Сессия = профиль мира; в ней «живое» измерение (где игрок —
 * его сканируем) и, если открыт глобус, «просматриваемое» (только с диска).
 * <ul>
 *   <li>Раз в 10 тиков: чанки в обзоре ({@link Reveal}), загруженные клиентом, встают в очередь скана —
 *       новые и не сканированные дольше минуты (так карта догоняет стройку).</li>
 *   <li>Очередь съедается с бюджетом по ВРЕМЕНИ ({@code scanBudgetMs} в тик), ближние чанки первыми.</li>
 *   <li>Регионы — в {@link DimMap}: чтение/запись в фоне, на диск раз в 30 с и при выходе.</li>
 * </ul>
 * Методы данных (регионы, текстуры, метки…) отвечают за ПОКАЗЫВАЕМОЕ измерение — так экран карты,
 * тропа и метки работают и в глобусе. Всё публичное — клиентский (render) поток.
 */
public final class LocalMap {
    private static final LocalMap INSTANCE = new LocalMap();
    private static final long RESCAN_MS = 60_000, SAVE_MS = 30_000;

    private String profile;
    private DimMap live, viewing;
    private final Long2LongOpenHashMap lastScan = new Long2LongOpenHashMap();
    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    private int ticks;
    private long lastSave;
    private int scannedTotal;
    private long scanNanos;                   // суммарное время скана — для замера в тяжёлых сборках

    private LocalMap() {}

    public static LocalMap get() { return INSTANCE; }

    public String profile() { return profile; }
    /** Живое измерение (где игрок). */
    public String dimension() { return live == null ? null : live.dim; }
    public int queued() { return queue.size(); }
    public int scannedTotal() { return scannedTotal; }
    /** Среднее время скана одного чанка, мс. */
    public double avgScanMs() { return scannedTotal == 0 ? 0 : scanNanos / 1e6 / scannedTotal; }

    /** Живое измерение — для миникарты (она всегда про то, где игрок). */
    public DimMap live() { return live; }

    /** Измерение, которое сейчас на экране: живое или выбранное глобусом. */
    public DimMap shown() { return viewing != null ? viewing : live; }
    public String shownDimension() { return shown() == null ? null : shown().dim; }
    public boolean viewingOther() { return viewing != null; }

    // ---- данные показываемого измерения
    public int loadedRegions() { return shown() == null ? 0 : shown().loadedRegions(); }
    public Markers markers() { return shown() == null ? null : shown().markers(); }
    public Drawings drawings() { return shown() == null ? null : shown().drawings(); }
    public Finds finds() { return shown() == null ? null : shown().finds(); }
    public MinerPlan miner() { return shown() == null ? null : shown().miner(); }
    public boolean isLoading(int rx, int rz) { return shown() != null && shown().isLoading(rx, rz); }
    public Integer heightAt(int bx, int bz) { return shown() == null ? null : shown().heightAt(bx, bz); }
    public RegionData region(int rx, int rz, boolean create) { return shown() == null ? null : shown().region(rx, rz, create); }
    public ResourceLocation texture(int rx, int rz) { return shown() == null ? null : shown().texture(rx, rz); }

    /** Глобус: показать другое измерение этого мира (null или живое — вернуться к живому). */
    public void view(String dimId) {
        if (viewing != null) {
            viewing.close();
            viewing = null;
        }
        if (dimId == null || live == null || dimId.equals(live.dim)) return;
        viewing = new DimMap(profile, dimId, WorldProfile.dir(profile, dimId));
    }

    /** Измерения, где игрок что-то разведал в этом мире (по папкам с dim.txt), живое — первым. */
    public List<String> knownDimensions() {
        Set<String> out = new LinkedHashSet<>();
        if (live != null) out.add(live.dim);
        if (profile == null) return new ArrayList<>(out);
        Path worlds = WorldProfile.root().resolve("worlds").resolve(profile);
        try (Stream<Path> s = Files.list(worlds)) {
            s.map(p -> p.resolve("dim.txt")).filter(Files::isRegularFile).forEach(f -> {
                try {
                    out.add(Files.readString(f, StandardCharsets.UTF_8).trim());
                } catch (Exception e) {
                    // не прочли — значит, это измерение в глобусе не покажем
                }
            });
        } catch (Exception e) {
            // папки мира ещё нет
        }
        return new ArrayList<>(out);
    }

    // ---------- тик: сессия, обзор, скан, сохранение ----------

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null) {
            if (profile != null) close();
            return;
        }
        String prof = WorldProfile.current();
        String d = level.dimension().location().toString();
        if (prof == null) return;
        if (!prof.equals(profile) || live == null || !d.equals(live.dim)) open(prof, d);

        if (++ticks % 10 == 0) enqueueReveal(p);
        drainQueue(level, p);
        long now = System.currentTimeMillis();
        if (now - lastSave > SAVE_MS) {
            lastSave = now;
            live.saveDirty();
            live.unloadIdle(now);
            if (viewing != null) viewing.unloadIdle(now);
        }
    }

    private void open(String prof, String d) {
        close();
        profile = prof;
        Path dir = WorldProfile.dir(prof, d);
        live = new DimMap(prof, d, dir);
        try {                                                    // для глобуса: настоящее имя измерения у папки
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("dim.txt"), d, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: dim.txt не записан: {}", e.toString());
        }
        Pergament.LOG.info("Пергамент: карта {} / {} → {}", prof, d, dir);
    }

    /** Грязное — в очередь записи (не дожидаясь). */
    public void saveNow() {
        if (live != null) live.saveDirty();
    }

    /** Всё грязное — на диск и дождаться записи (выход из игры, самотест). */
    public boolean flush(long timeoutMs) {
        if (live != null) live.saveDirty();
        return DimMap.waitIo(timeoutMs);
    }

    /** Выход из мира/смена измерения: всё грязное — на диск, текстуры — освободить. */
    public void close() {
        if (profile == null) return;
        if (viewing != null) viewing.close();
        if (live != null) live.close();
        viewing = null;
        live = null;
        lastScan.clear();
        queue.clear();
        profile = null;
    }

    private void enqueueReveal(LocalPlayer p) {
        int front = PergamentConfig.REVEAL_FRONT.get(), back = PergamentConfig.REVEAL_BACK.get();
        int pcx = p.chunkPosition().x, pcz = p.chunkPosition().z;
        float yaw = p.getYRot();
        long now = System.currentTimeMillis();
        List<long[]> found = new ArrayList<>();
        for (int dz = -front; dz <= front; dz++) {
            for (int dx = -front; dx <= front; dx++) {
                if (!Reveal.inside(dx, dz, yaw, front, back)) continue;
                long key = ChunkPos.asLong(pcx + dx, pcz + dz);
                long last = lastScan.getOrDefault(key, 0L);
                if (last != 0 && now - last < RESCAN_MS) continue;
                if (queue.contains(key)) continue;
                found.add(new long[]{key, (long) dx * dx + (long) dz * dz});
            }
        }
        found.sort((a, b) -> Long.compare(a[1], b[1]));          // ближние первыми
        for (long[] f : found) queue.add(f[0]);
    }

    private void drainQueue(ClientLevel level, LocalPlayer p) {
        long budget = (long) (PergamentConfig.SCAN_BUDGET_MS.get() * 1_000_000L);
        long t0 = System.nanoTime();
        int skipped = 0;
        while (!queue.isEmpty() && System.nanoTime() - t0 < budget && skipped < queue.size()) {
            long key = queue.removeFirstLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;       // не прогружен — вернётся при следующем обзоре
            RegionData r = live.region(RegionData.regionOf(cx << 4), RegionData.regionOf(cz << 4), true);
            if (r == null) {                                      // регион ещё читается с диска
                queue.add(key);
                skipped++;
                continue;
            }
            long s0 = System.nanoTime();
            ChunkScanner.scan(level, chunk, r, p.getBlockY());
            ru.stef.pergament.client.TeamSyncClient.scanned(live.dim, cx, cz);
            scanNanos += System.nanoTime() - s0;
            lastScan.put(key, System.currentTimeMillis());
            scannedTotal++;
        }
    }
}
