package ru.stef.pergament.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.geo.Toponyms;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Подписи местности одного измерения: категории чанков ({@code geo.bin}, пишется потоком карт атомарно)
 * и реестр имён ({@code toponyms.json}, им владеет только поток расчёта). Категории копятся сканом, объекты
 * пересчитываются в фоне, когда карте нужны подписи и что-то изменилось (не чаще раза в 3 с).
 * Формат регионов и обмен с командой не трогаются: подписи строятся по личной разведке.
 */
public final class Toponymy {
    private static final ExecutorService CALC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Pergament-Geo");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final int MAGIC = 0x5047454F, VERSION = 1;          // "PGEO"
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final String salt;
    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap();   // главный поток
    private long rev, savedRev, computedRev = -1, lastCompute;
    private boolean loading = true, computing;
    private volatile List<Toponyms.Label> labels = List.of();
    private Toponyms.Registry reg;                                          // только поток расчёта

    Toponymy(Path dir, String salt) {
        this.dir = dir;
        this.salt = salt;
        cells.defaultReturnValue(Long.MIN_VALUE);
        DimMap.IO.execute(() -> {
            Long2LongOpenHashMap loaded = read(dir.resolve("geo.bin"));
            Minecraft.getInstance().execute(() -> {
                for (var e : loaded.long2LongEntrySet()) cells.putIfAbsent(e.getLongKey(), e.getLongValue());   // свежий скан главнее
                loading = false;
                rev++;
                savedRev = cells.size() == loaded.size() ? rev : savedRev;
            });
        });
    }

    /** Скан: местность чанка. */
    public void put(long chunkKey, long geo) {
        if (cells.put(chunkKey, geo) != geo) rev++;
    }

    /** Импорт: местность, только если своей ещё нет. */
    public void putIfAbsent(long chunkKey, long geo) {
        if (!cells.containsKey(chunkKey)) {
            cells.put(chunkKey, geo);
            rev++;
        }
    }

    /** Поток карт: дописать местность в geo.bin неоткрытого измерения (своё не перетирать). */
    public static void mergeFile(Path dir, Long2LongOpenHashMap add) {
        Path f = dir.resolve("geo.bin");
        Long2LongOpenHashMap cur = read(f);
        int n = cur.size();
        for (var e : add.long2LongEntrySet()) cur.putIfAbsent(e.getLongKey(), e.getLongValue());
        if (cur.size() != n) write(f, cur);
    }

    public int size() {
        return cells.size();
    }

    /** Подписи для карты (последний расчёт); заодно заказать новый, если местность изменилась. */
    public List<Toponyms.Label> labels() {
        long now = System.currentTimeMillis();
        if (!loading && !computing && rev != computedRev && now - lastCompute > 3000) {
            Long2LongOpenHashMap snap = new Long2LongOpenHashMap(cells);
            long r = rev;
            computing = true;
            CALC.execute(() -> {
                try {
                    if (reg == null) reg = readRegistry();
                    Toponyms.Result res = Toponyms.compute(snap, reg, salt);
                    labels = res.labels();
                    if (res.changed()) writeRegistry();
                } catch (RuntimeException e) {
                    Pergament.LOG.warn("Пергамент: подписи местности не посчитаны: {}", e.toString());
                } finally {
                    Minecraft.getInstance().execute(() -> {
                        computing = false;
                        computedRev = r;
                        lastCompute = System.currentTimeMillis();
                    });
                }
            });
        }
        return labels;
    }

    /** Изменившееся — на диск (поток карт, атомарно). */
    void save() {
        if (loading || rev == savedRev) return;
        savedRev = rev;
        Long2LongOpenHashMap snap = new Long2LongOpenHashMap(cells);
        DimMap.IO.execute(() -> write(dir.resolve("geo.bin"), snap));
    }

    private static Long2LongOpenHashMap read(Path f) {
        Long2LongOpenHashMap out = new Long2LongOpenHashMap();
        if (!Files.isRegularFile(f)) return out;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return out;   // чужой или будущий формат — с нуля
            int n = in.readInt();
            for (int i = 0; i < n; i++) out.put(in.readLong(), in.readLong());
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не прочитан: {}", f, e.toString());
            out.clear();
        }
        return out;
    }

    private static void write(Path f, Long2LongOpenHashMap snap) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(snap.size());
                for (var e : snap.long2LongEntrySet()) {
                    out.writeLong(e.getLongKey());
                    out.writeLong(e.getLongValue());
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не записан: {}", f, e.toString());
        }
    }

    private Toponyms.Registry readRegistry() {
        Path f = dir.resolve("toponyms.json");
        try {
            if (Files.isRegularFile(f)) {
                Toponyms.Registry r = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Toponyms.Registry.class);
                if (r != null && r.items != null) {
                    r.items.removeIf(it -> it == null || it.type == null || it.nameId == null);
                    return r;
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не прочитан: {}", f, e.toString());
        }
        return new Toponyms.Registry();
    }

    private void writeRegistry() {
        Path f = dir.resolve("toponyms.json");
        try {
            Files.createDirectories(dir);
            Path tmp = f.resolveSibling("toponyms.json.tmp");
            Files.writeString(tmp, GSON.toJson(reg), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не записан: {}", f, e.toString());
        }
    }
}
