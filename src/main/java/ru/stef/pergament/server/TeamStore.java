package ru.stef.pergament.server;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.store.ChunkCodec;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.store.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Карта одной команды на сервере: регионы в том же формате, что у клиента, и индекс «чанк → запись».
 * Запись = номер {@code seq} (растёт на команду), класс (LIVE — свежий скан, ARCHIVE — старый архив/импорт)
 * и кто писал. Правила:
 * <ul>
 *   <li>LIVE всегда заменяет чанк; ARCHIVE только заполняет пустое.</li>
 *   <li>Чанк без единой разведанной колонки не принимается и не отдаётся.</li>
 * </ul>
 * Не потокобезопасен: всё — на одном потоке ввода-вывода командной части. Регионы пишутся раньше индекса,
 * так что после обрыва индекс никогда не ссылается на ненаписанное. {@link #incarnation()} — «воплощение» карты:
 * новое при создании и при потере индекса; клиент, увидев другое, начинает с нуля и отдаёт архив заново.
 */
public final class TeamStore {
    static final int MAGIC = 0x50475449;                         // "PGTI"
    private static final int CACHE = 12;

    /** Запись в индексе, упакованная в long: seq (46 бит) | writer (16 бит) | live (1 бит). */
    static long pack(long seq, int writer, boolean live) {
        return seq << 17 | (long) (writer & 0xFFFF) << 1 | (live ? 1 : 0);
    }

    static long seqOf(long e) {
        return e >>> 17;
    }

    static int writerOf(long e) {
        return (int) (e >>> 1 & 0xFFFF);
    }

    static boolean liveOf(long e) {
        return (e & 1) != 0;
    }

    /** Что отдать при докачке. */
    public record Entry(String dim, int cx, int cz, long seq, boolean live) {}

    /** Событие находки команды: под каким seq принято, в каком измерении. */
    public record StoredFind(long seq, String dim, ru.stef.pergament.net.TeamNet.FindRec rec) {}

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final int FIND_QUOTA = 200_000;
    private final Map<String, StoredFind> finds = new LinkedHashMap<>();
    private boolean dirtyFinds;

    /** Имя местности команды: в каком измерении и сама запись (teamSeq, rev, автор — назначены здесь). */
    public record StoredTopo(String dim, ru.stef.pergament.net.TeamNet.TopoRec rec) {}

    /** Файл имён: счётчики и записи. */
    private static final class TopoFile {
        long seq, rev;
        List<StoredTopo> items = new ArrayList<>();
    }

    private static final int TOPO_QUOTA = 20_000;
    private final Map<String, StoredTopo> topos = new LinkedHashMap<>();
    private long topoSeq, topoRev;
    private boolean dirtyTopos;

    private final Path dir;
    private long seq;
    private UUID incarnation = UUID.randomUUID();
    private final Map<UUID, Integer> writers = new HashMap<>();
    private final Map<String, Long2LongOpenHashMap> index = new HashMap<>();
    private final Map<String, RegionData> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> dirtyRegions = new java.util.HashSet<>();
    private boolean dirtyIndex;
    private long chunks;

    private TeamStore(Path dir) {
        this.dir = dir;
    }

    public long seq() {
        return seq;
    }

    public long chunks() {
        return chunks;
    }

    public UUID incarnation() {
        return incarnation;
    }

    public static TeamStore open(Path dir) {
        TeamStore s = new TeamStore(dir);
        s.loadTopos();                                               // имена — отдельно: сбой индекса карты их не трогает
        s.dirtyIndex = !Files.isRegularFile(dir.resolve("index.pgti"));   // новая карта: воплощение — на диск сразу
        try {
            s.load();
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: индекс команды {} не прочитан, начинаю пустым: {}", dir, e.toString());
            s.index.clear();
            s.writers.clear();
            s.seq = 0;
            s.chunks = 0;
            s.finds.clear();
            s.incarnation = UUID.randomUUID();                       // прежние watermark клиентов недействительны
            s.dirtyIndex = true;
        }
        return s;
    }

    /**
     * Имя местности от члена команды. Новое (по id) — получает порядок команды и ревизию, автор — отправитель;
     * известное — меняется только своё имя (любой член команды может переименовать), ревизия растёт.
     * Возвращает состояние записи у сервера (в т.ч. без изменений) или null — не принято (квота, чужое измерение).
     */
    public ru.stef.pergament.net.TeamNet.TopoRec acceptTopo(String dim, ru.stef.pergament.net.TeamNet.TopoRec r, UUID author) {
        StoredTopo old = topos.get(r.id());
        if (old != null) {
            if (!old.dim().equals(dim)) return null;
            var o = old.rec();
            if (o.custom().equals(r.custom())) return o;
            var upd = new ru.stef.pergament.net.TeamNet.TopoRec(o.id(), o.type(), o.anchor(), o.nameId(), r.custom(),
                    o.teamSeq(), ++topoRev, o.author());
            topos.put(o.id(), new StoredTopo(dim, upd));
            dirtyTopos = true;
            return upd;
        }
        if (topos.size() >= TOPO_QUOTA) return null;
        var rec = new ru.stef.pergament.net.TeamNet.TopoRec(r.id(), r.type(), r.anchor(), r.nameId(), r.custom(),
                ++topoSeq, ++topoRev, author);
        topos.put(r.id(), new StoredTopo(dim, rec));
        dirtyTopos = true;
        return rec;
    }

    /** Все имена команды (по порядку открытия) — для докачки. */
    public List<StoredTopo> topos() {
        return new ArrayList<>(topos.values());
    }

    /** Имена — на диск (атомарно); после этого их можно подтверждать. */
    public void flushTopos() throws IOException {
        if (!dirtyTopos) return;
        Files.createDirectories(dir);
        TopoFile tf = new TopoFile();
        tf.seq = topoSeq;
        tf.rev = topoRev;
        tf.items = new ArrayList<>(topos.values());
        Path f = dir.resolve("toponyms.json"), tmp = dir.resolve("toponyms.json.tmp");
        Files.writeString(tmp, GSON.toJson(tf), java.nio.charset.StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirtyTopos = false;
    }

    private void loadTopos() {
        Path f = dir.resolve("toponyms.json");
        if (!Files.isRegularFile(f)) return;
        try {
            TopoFile tf = GSON.fromJson(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8), TopoFile.class);
            if (tf == null) return;
            topoSeq = tf.seq;
            topoRev = tf.rev;
            if (tf.items != null) for (StoredTopo st : tf.items) if (st != null && st.rec() != null) topos.put(st.rec().id(), st);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: имена команды {} не прочитаны: {}", dir, e.toString());
        }
    }

    /** Номер автора в индексе (16 бит хватит на любую команду). */
    int writer(UUID id) {
        return writers.computeIfAbsent(id, k -> writers.size() + 1);
    }

    /**
     * Принять чанк. Возвращает новую запись индекса или 0, если не принят (ARCHIVE поверх существующего, пустой чанк).
     */
    public long accept(String dim, int cx, int cz, byte[] raw, boolean live, UUID from) throws IOException {
        if (!hasKnown(raw)) return 0;
        Long2LongOpenHashMap idx = index.computeIfAbsent(dim, k -> new Long2LongOpenHashMap());
        long key = ChunkPos.asLong(cx, cz);
        boolean had = idx.containsKey(key);
        if (!live && had) return 0;
        RegionData r = region(dim, cx >> 5, cz >> 5);
        ChunkCodec.apply(r, cx, cz, raw);
        dirtyRegions.add(rkey(dim, cx >> 5, cz >> 5));
        long e = pack(++seq, writer(from), live);
        idx.put(key, e);
        if (!had) chunks++;
        dirtyIndex = true;
        return e;
    }

    /**
     * Всё новее watermark, по регионам (клиент пишет файл раз). Своё тоже отдаём: второй компьютер или стёртая
     * папка карты иначе не получат его никогда; повторная доставка безвредна.
     */
    public List<Entry> since(long watermark) {
        List<Entry> out = new ArrayList<>();
        for (var d : index.entrySet()) {
            for (Long2LongMap.Entry e : d.getValue().long2LongEntrySet()) {
                long v = e.getLongValue();
                if (seqOf(v) <= watermark) continue;
                long k = e.getLongKey();
                out.add(new Entry(d.getKey(), ChunkPos.getX(k), ChunkPos.getZ(k), seqOf(v), liveOf(v)));
            }
        }
        out.sort(Comparator.comparing(Entry::dim).thenComparingInt(x -> x.cx() >> 5).thenComparingInt(x -> x.cz() >> 5)
                .thenComparingLong(Entry::seq));
        return out;
    }

    /** Принять событие находки: новое по id — да (seq растёт), повтор или сверх квоты — нет. */
    public boolean acceptFind(String dim, ru.stef.pergament.net.TeamNet.FindRec f) {
        if (finds.containsKey(f.eid()) || finds.size() >= FIND_QUOTA) return false;
        finds.put(f.eid(), new StoredFind(++seq, dim, f));
        dirtyFinds = true;
        dirtyIndex = true;
        return true;
    }

    /** Находки новее watermark, в порядке seq. */
    public List<StoredFind> findsSince(long watermark) {
        List<StoredFind> out = new ArrayList<>();
        for (StoredFind f : finds.values()) if (f.seq() > watermark) out.add(f);
        return out;
    }

    /** Сырьё чанка из карты команды или null. */
    public byte[] read(String dim, int cx, int cz) throws IOException {
        Long2LongOpenHashMap idx = index.get(dim);
        if (idx == null || !idx.containsKey(ChunkPos.asLong(cx, cz))) return null;
        return ChunkCodec.extract(region(dim, cx >> 5, cz >> 5), cx, cz);
    }

    private static boolean hasKnown(byte[] raw) {
        for (int o = 10; o < raw.length; o += 11) if (raw[o] != 0) return true;
        return false;
    }

    private static String rkey(String dim, int rx, int rz) {
        return dim + "|" + rx + "|" + rz;
    }

    private Path dimDir(String dim) {
        return dir.resolve(dim.replace(':', '_').replace('/', '_'));
    }

    private RegionData region(String dim, int rx, int rz) throws IOException {
        String k = rkey(dim, rx, rz);
        RegionData r = regions.get(k);
        if (r != null) return r;
        r = RegionFile.read(dimDir(dim), rx, rz);
        if (r == null) r = new RegionData(rx, rz);
        regions.put(k, r);
        evict();
        return r;
    }

    private void evict() throws IOException {
        while (regions.size() > CACHE) {
            var it = regions.entrySet().iterator();
            var oldest = it.next();
            if (dirtyRegions.remove(oldest.getKey())) writeRegion(oldest.getKey(), oldest.getValue());
            it.remove();
        }
    }

    private void writeRegion(String k, RegionData r) throws IOException {
        String dim = k.substring(0, k.indexOf('|'));
        RegionFile.write(dimDir(dim), r);
    }

    /** Грязное на диск: сначала регионы и находки, потом индекс (индекс не опережает данные). */
    public void flush() throws IOException {
        flushTopos();
        if (dirtyFinds) {
            Files.createDirectories(dir);
            Path f = dir.resolve("finds.json"), tmp = dir.resolve("finds.json.tmp");
            Files.writeString(tmp, GSON.toJson(new ArrayList<>(finds.values())), java.nio.charset.StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirtyFinds = false;
        }
        for (String k : List.copyOf(dirtyRegions)) {
            RegionData r = regions.get(k);
            if (r != null) writeRegion(k, r);
            dirtyRegions.remove(k);
        }
        if (!dirtyIndex) return;
        Files.createDirectories(dir);
        Path f = dir.resolve("index.pgti"), tmp = dir.resolve("index.pgti.tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new DeflaterOutputStream(os), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(2);
            out.writeLong(incarnation.getMostSignificantBits());
            out.writeLong(incarnation.getLeastSignificantBits());
            out.writeLong(seq);
            out.writeInt(writers.size());
            for (var w : writers.entrySet()) {
                out.writeLong(w.getKey().getMostSignificantBits());
                out.writeLong(w.getKey().getLeastSignificantBits());
                out.writeInt(w.getValue());
            }
            out.writeInt(index.size());
            for (var d : index.entrySet()) {
                out.writeUTF(d.getKey());
                out.writeInt(d.getValue().size());
                for (Long2LongMap.Entry e : d.getValue().long2LongEntrySet()) {
                    out.writeLong(e.getLongKey());
                    out.writeLong(e.getLongValue());
                }
            }
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirtyIndex = false;
    }

    private void load() throws IOException {
        Path f = dir.resolve("index.pgti");
        if (!Files.isRegularFile(f)) return;
        Path ff = dir.resolve("finds.json");
        if (Files.isRegularFile(ff)) {
            StoredFind[] l = GSON.fromJson(Files.readString(ff, java.nio.charset.StandardCharsets.UTF_8), StoredFind[].class);
            if (l != null) for (StoredFind sf : l) if (sf != null && sf.rec() != null) finds.put(sf.rec().eid(), sf);
        }
        try (InputStream is = Files.newInputStream(f);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(is), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("не индекс команды");
            if (in.readInt() != 2) throw new IOException("версия индекса");
            incarnation = new UUID(in.readLong(), in.readLong());
            seq = in.readLong();
            int nw = in.readInt();
            if (nw < 0 || nw > 65535) throw new IOException("авторов " + nw);
            for (int k = 0; k < nw; k++) writers.put(new UUID(in.readLong(), in.readLong()), in.readInt());
            int nd = in.readInt();
            if (nd < 0 || nd > 1024) throw new IOException("измерений " + nd);
            for (int k = 0; k < nd; k++) {
                String dim = in.readUTF();
                int n = in.readInt();
                if (n < 0 || n > 50_000_000) throw new IOException("чанков " + n);
                Long2LongOpenHashMap m = new Long2LongOpenHashMap(n);
                for (int i = 0; i < n; i++) m.put(in.readLong(), in.readLong());
                index.put(dim, m);
                chunks += n;
            }
        }
    }
}
