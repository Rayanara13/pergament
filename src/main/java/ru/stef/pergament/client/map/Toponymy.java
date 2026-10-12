package ru.stef.pergament.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.geo.TeamTopo;
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
    private Long2LongOpenHashMap lastSnap;                                  // только поток расчёта
    // ---- имена команды (только поток расчёта): слой команды, свои переименования в пути, отвергнутое, отправленное
    private TeamTopo.Overlay overlay;
    private final java.util.Map<String, TeamTopo.Pending> pending = new java.util.HashMap<>();
    private final java.util.Set<String> refused = new java.util.HashSet<>();
    private final java.util.Map<String, Long> inFlight = new java.util.HashMap<>();
    private java.util.UUID lastTeam;                                        // команда последнего расчёта (поток расчёта)
    private java.util.UUID teamMain;                                        // команда, с которой считали (главный поток)

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

    /** Подписи для большой карты (последний расчёт); заодно заказать новый, если местность изменилась (раз в 3 с). */
    public List<Toponyms.Label> labels() {
        return labels(3000);
    }

    /**
     * Подписи; пересчёт — не чаще раза в every мс (миникарта видна всё время, а скан меняет местность постоянно:
     * ей хватает раза в 15 с — копия таблицы чанков в главном потоке не должна дёргать кадр).
     */
    public List<Toponyms.Label> labels(long every) {
        long now = System.currentTimeMillis();
        java.util.UUID team = ru.stef.pergament.client.TeamSyncClient.team();
        boolean teamChanged = !java.util.Objects.equals(team, teamMain);
        if (!loading && !computing && (rev != computedRev || teamChanged) && (now - lastCompute > every || teamChanged)) {
            Long2LongOpenHashMap snap = new Long2LongOpenHashMap(cells);
            long r = rev;
            computing = true;
            teamMain = team;
            CALC.execute(() -> {
                try {
                    lastSnap = snap;
                    lastTeam = team;
                    recompute();
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

    /**
     * Поток расчёта: объекты и подписи по своему реестру и слою команды (если она есть); новые имена — в свой реестр.
     */
    private void recompute() {
        if (reg == null) reg = readRegistry();
        if (overlay == null) overlay = readOverlay();
        if (lastSnap == null) return;
        TeamTopo.Overlay team = lastTeam == null ? null : TeamTopo.forTeam(overlay, lastTeam);
        Toponyms.Registry view = TeamTopo.view(reg, team, lastTeam == null ? null : pending);
        int before = view.items.size();
        Toponyms.Result res = Toponyms.compute(lastSnap, view, salt);
        boolean added = TeamTopo.adopt(reg, view, before);
        labels = res.labels();
        if (res.changed() || added) writeRegistry();
    }

    /**
     * Своё имя объекта (null или пусто — вернуть выданное). В команде — ещё и «в пути» к серверу (до его ответа
     * подпись показывает своё). Своя запись помнит имя и вне команды.
     */
    public void rename(String itemId, String custom) {
        String name = custom == null || custom.isBlank() ? null : custom.strip();
        CALC.execute(() -> {
            if (reg == null) reg = readRegistry();
            for (Toponyms.Item it : reg.items) if (itemId.equals(it.id)) it.custom = name;
            writeRegistry();
            if (lastTeam != null) pending.put(itemId, new TeamTopo.Pending(name == null ? "" : name));
            recompute();
        });
    }

    /** От сервера: имена команды этого измерения (докачка, свежее, ответ на свой запрос). */
    public void mergeTeam(java.util.UUID team, ru.stef.pergament.net.TeamNet.TopoPush p) {
        CALC.execute(() -> {
            if (overlay == null) overlay = readOverlay();
            overlay = TeamTopo.forTeam(overlay, team);
            boolean ch = TeamTopo.merge(overlay, recs(p));
            if (p.req() > 0) {
                TeamTopo.acked(pending, p.req(), p.rejected(), refused);
                for (var r : p.recs()) inFlight.remove(r.id());
                for (String id : p.rejected()) inFlight.remove(id);
            }
            if (ch) writeOverlay(dir, overlay);
            if (team.equals(lastTeam)) recompute();
        });
    }

    /**
     * Что отдать команде (свои имена, которых у неё нет, и переименования в пути) — не больше пакета; отмечается как
     * отправленное с номером req. send вызывается в главном потоке, если есть что слать.
     */
    public void collectOutgoing(java.util.UUID team, int req, java.util.function.Consumer<List<ru.stef.pergament.net.TeamNet.TopoRec>> send) {
        CALC.execute(() -> {
            if (reg == null) reg = readRegistry();
            if (overlay == null) overlay = readOverlay();
            long now = System.currentTimeMillis();
            var out = TeamTopo.outgoing(reg, TeamTopo.forTeam(overlay, team), pending, refused, inFlight, now, 30_000,
                    ru.stef.pergament.net.TeamNet.MAX_TOPO_UP);
            if (out.isEmpty()) return;
            List<ru.stef.pergament.net.TeamNet.TopoRec> recs = new java.util.ArrayList<>();
            for (TeamTopo.Rec r : out) {
                TeamTopo.Pending pn = pending.get(r.id);
                if (pn != null) {
                    pn.req = req;
                    pn.sentAt = now;
                }
                inFlight.put(r.id, now);
                recs.add(new ru.stef.pergament.net.TeamNet.TopoRec(r.id, r.type, r.anchor, r.nameId,
                        r.custom == null ? "" : r.custom, 0, 0, null));
            }
            Minecraft.getInstance().execute(() -> send.accept(recs));
        });
    }

    /** Имена команды для неоткрытого измерения — прямо в его слой на диске (в потоке расчёта, по очереди с открытыми). */
    public static void mergeTeamFile(Path dir, java.util.UUID team, ru.stef.pergament.net.TeamNet.TopoPush p) {
        CALC.execute(() -> {
            TeamTopo.Overlay o = TeamTopo.forTeam(readOverlay(dir), team);
            if (TeamTopo.merge(o, recs(p))) writeOverlay(dir, o);
        });
    }

    private static List<TeamTopo.Rec> recs(ru.stef.pergament.net.TeamNet.TopoPush p) {
        List<TeamTopo.Rec> l = new java.util.ArrayList<>();
        for (var r : p.recs()) {
            TeamTopo.Rec t = new TeamTopo.Rec();
            t.id = r.id();
            t.type = r.type();
            t.anchor = r.anchor();
            t.nameId = r.nameId();
            t.custom = r.custom();
            t.teamSeq = r.teamSeq();
            t.rev = r.rev();
            t.author = r.author() == null ? null : r.author().toString();
            l.add(t);
        }
        return l;
    }

    private TeamTopo.Overlay readOverlay() {
        return readOverlay(dir);
    }

    private static TeamTopo.Overlay readOverlay(Path dir) {
        Path f = dir.resolve("toponyms_team.json");
        try {
            if (Files.isRegularFile(f)) {
                TeamTopo.Overlay o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), TeamTopo.Overlay.class);
                if (o != null && o.items != null) {
                    o.items.removeIf(r -> r == null || r.id == null || r.type == null || r.nameId == null);
                    return o;
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не прочитан: {}", f, e.toString());
        }
        return new TeamTopo.Overlay();
    }

    private static void writeOverlay(Path dir, TeamTopo.Overlay o) {
        Path f = dir.resolve("toponyms_team.json");
        try {
            Files.createDirectories(dir);
            Path tmp = f.resolveSibling("toponyms_team.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: {} не записан: {}", f, e.toString());
        }
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
