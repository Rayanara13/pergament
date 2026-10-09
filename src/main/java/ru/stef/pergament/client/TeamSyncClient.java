package ru.stef.pergament.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.DimMap;
import ru.stef.pergament.client.map.Finds;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.store.WorldProfile;
import ru.stef.pergament.net.TeamNet;
import ru.stef.pergament.store.ChunkCodec;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.store.RegionFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Обмен картой и рудами в команде (клиент). Только подложка карты и подтверждённые находки руды: метки, рисунки,
 * мобы, шахтёр, тропы, метки гибели — не уходят. Находки — событиями с id: каждое вливается в жилы ровно раз.
 * <ul>
 *   <li>Свежий скан чанка — сразу серверу (LIVE). При первом входе в команду на этом сервере — весь свой архив
 *       (ARCHIVE: у других заполняет только пустое). Регион архива засчитан, только когда сервер подтвердил запись.</li>
 *   <li>Пришедшее сливается в свою карту навсегда: LIVE заменяет, ARCHIVE — только в пустое. Докачка не трогает чанки,
 *       которые в этой сессии я сам сканировал или получил свежими; пересылка — те, что я только что отправил сам.</li>
 *   <li>Watermark пишется на диск только по отметке сервера (её шлют после записи карты команды) и после того,
 *       как принятое легло в мои регионы.</li>
 * </ul>
 * Всё состояние — в объекте {@link Session}: смена команды или выход отбрасывают его целиком, запоздавшая работа
 * старой сессии пишет только в свои, уже никому не нужные очереди.
 */
public final class TeamSyncClient {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int LIVE_PER_TICK = 6, ARCHIVE_PER_TICK = 6, ARCHIVE_BUFFER = 512, MAX_RETRY = 64;
    private static final long RECENT_MS = 15_000;

    /** Что помним про команду на этом сервере (пишется на поток карт, читается при входе). */
    static final class State {
        String incarnation;
        long watermark;
        boolean archiveDone;
        Set<String> archiveRegions = new HashSet<>();
    }

    private static final class Session {
        final UUID team;
        final String profile;
        final State state;
        final LinkedHashSet<String> liveQueue = new LinkedHashSet<>();
        final Map<String, Long> recentOwn = new HashMap<>();
        /** Чанки, свежие в этой сессии (свой скан или пересылка) — докачка их не трогает. */
        final Set<String> fresh = new HashSet<>();
        final Map<String, Object[]> retry = new LinkedHashMap<>();       // регион → {dim, rx, rz, List<rec>, live}
        Archive archive;
        int uploadId;
        /** Регион архива целиком отправлен в пакете №… — ждёт подтверждения. */
        final Map<String, Integer> archiveSent = new HashMap<>();
        /** Находки к отправке: {dim, FindRec | null, archiveKey | null}. */
        final ArrayDeque<Object[]> findQueue = new ArrayDeque<>();

        Session(UUID team, String profile, State state) {
            this.team = team;
            this.profile = profile;
            this.state = state;
        }
    }

    /** Отдача архива: свой список регионов, своя очередь — старой сессии некуда утечь. */
    private static final class Archive {
        final ArrayDeque<Path[]> todo = new ArrayDeque<>();
        final ConcurrentLinkedQueue<Object[]> queue = new ConcurrentLinkedQueue<>();   // {dim, Rec | null, regionKey}
        final AtomicBoolean busy = new AtomicBoolean();
    }

    private static volatile Session cur;
    /** Счётчики для самотеста и окна. */
    public static volatile int sentLive, sentArchive, gotChunks, applied, sentFinds, gotFinds;

    private TeamSyncClient() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> cur = null);
    }

    public static UUID team() {
        Session s = cur;
        return s == null ? null : s.team;
    }

    // ---------- от сервера ----------

    public static void onTeam(UUID t) {
        cur = null;
        String prof = WorldProfile.current();
        if (t == null || prof == null) return;
        Session s = new Session(t, prof, load(prof, t));
        cur = s;
        UUID inc = parse(s.state.incarnation);
        TeamNet.CHANNEL.sendToServer(new TeamNet.SyncFrom(t, inc, s.state.watermark));
        Pergament.LOG.info("Пергамент: команда {} — карта от {} и дальше; свой архив {}", t, s.state.watermark,
                s.state.archiveDone ? "уже отдан" : "отдаю");
        if (!s.state.archiveDone) startArchive(s);
    }

    public static void onPush(TeamNet.Push p) {
        Session s = cur;
        if (s == null || !s.team.equals(p.team())) return;              // запоздавшее от прежней команды — мимо
        long now = System.currentTimeMillis();
        Map<Long, List<Object[]>> byRegion = new HashMap<>();
        for (TeamNet.Rec r : p.recs()) {
            String k = key(p.dim(), r.cx(), r.cz());
            if (p.catchup() ? s.fresh.contains(k) : p.live() && recent(s.recentOwn, k, now)) continue;
            byte[] raw;
            try {
                raw = ChunkCodec.inflate(r.z());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (p.live() && !p.catchup()) s.fresh.add(k);
            gotChunks++;
            byRegion.computeIfAbsent(ChunkPos.asLong(r.cx() >> 5, r.cz() >> 5), x -> new ArrayList<>())
                    .add(new Object[]{r.cx(), r.cz(), raw});
        }
        for (var e : byRegion.entrySet()) {
            applyRegion(s, p.dim(), ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()), e.getValue(), p.live());
        }
    }

    /** События находок от команды: влить каждое ровно раз (по id) в жилы своего измерения. */
    public static void onFindPush(TeamNet.FindPush p) {
        Session s = cur;
        if (s == null || !s.team.equals(p.team())) return;
        DimMap lm = LocalMap.get().live();
        Finds target = lm != null && p.dim().equals(lm.dimension()) ? lm.finds()
                : Finds.load(WorldProfile.dir(s.profile, p.dim()).resolve("finds.json"));
        int n = 0;
        for (TeamNet.FindRec r : p.finds()) if (target.applyOnce(r.eid(), toFind(r))) n++;
        gotFinds += n;
    }

    /** Своя находка (событие до слияния в жилу) — команде. */
    public static void foundLocal(String dim, Finds finds, Finds.Find event) {
        Session s = cur;
        if (s == null) return;
        String eid = UUID.randomUUID().toString();
        finds.markSeen(eid);                                             // эхо от команды не вольётся второй раз
        s.findQueue.add(new Object[]{dim, toRec(eid, event), null});
    }

    static TeamNet.FindRec toRec(String eid, Finds.Find f) {
        int flags = (f.byIndicator ? 1 : 0) | (f.byHand ? 2 : 0) | (f.byProspector ? 4 : 0);
        return new TeamNet.FindRec(eid, f.mat, f.name == null ? "" : f.name, f.rgb, f.x, f.y, f.z, f.minY, f.maxY,
                Math.max(1, f.count), Math.max(0, f.spread), flags, f.time);
    }

    static Finds.Find toFind(TeamNet.FindRec r) {
        Finds.Find f = new Finds.Find();
        f.mat = r.mat();
        f.name = r.name();
        f.rgb = r.rgb();
        f.x = r.x(); f.y = r.y(); f.z = r.z();
        f.minY = r.minY(); f.maxY = r.maxY();
        f.count = r.count(); f.spread = r.spread();
        f.byIndicator = (r.flags() & 1) != 0;
        f.byHand = (r.flags() & 2) != 0;
        f.byProspector = (r.flags() & 4) != 0;
        f.time = r.time();
        return f;
    }

    /**
     * Карта профиля пополнилась мимо скана (импорт из Xaero): архив снова «не отдан» — во всех командах этого профиля.
     * Идёт сессия — отдаём сразу; иначе — при следующем входе. Сервер возьмёт только то, чего у команды нет.
     */
    public static void archiveAgain(String profile) {
        if (profile == null) return;
        Session s = cur;
        if (s != null && profile.equals(s.profile)) {
            synchronized (s.state) {
                s.state.archiveDone = false;
                s.state.archiveRegions.clear();
            }
            s.archiveSent.clear();
            startArchive(s);
            Pergament.LOG.info("Пергамент: карта пополнилась — отдаю архив команде заново");
        }
        Path dir = WorldProfile.root().resolve("worlds").resolve(profile);
        try (Stream<Path> fs = Files.list(dir)) {
            for (Path f : fs.filter(x -> x.getFileName().toString().matches("team_[0-9a-f-]+\\.json")).toList()) {
                UUID t = UUID.fromString(f.getFileName().toString().substring(5, f.getFileName().toString().length() - 5));
                if (s != null && profile.equals(s.profile) && t.equals(s.team)) continue;   // текущая — уже выше
                State st = load(profile, t);
                st.archiveDone = false;
                st.archiveRegions.clear();
                DimMap.onIo(() -> save(profile, t, st));
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: состояние команд не обновлено: {}", e.toString());
        }
    }

    public static void onMark(TeamNet.Mark m) {
        Session s = cur;
        if (s == null || !s.team.equals(m.team())) return;
        State st = s.state;
        String inc = m.incarnation().toString();
        boolean lost = st.incarnation != null && !st.incarnation.equals(inc);
        synchronized (st) {
            if (lost) {                                                 // сервер потерял карту команды: с нуля и архив заново
                st.watermark = 0;
                st.archiveDone = false;
                st.archiveRegions.clear();
                s.archiveSent.clear();
            }
            st.incarnation = inc;
            if (m.ack() >= 0) {                                          // подтверждённые регионы архива
                for (Iterator<Map.Entry<String, Integer>> it = s.archiveSent.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    if (e.getValue() <= m.ack()) {
                        st.archiveRegions.add(e.getKey());
                        it.remove();
                    }
                }
            }
            if (s.archive == null && s.archiveSent.isEmpty() && !st.archiveDone && !lost && !hasArchiveFinds(s)) {
                st.archiveDone = true;
                st.archiveRegions.clear();
                Pergament.LOG.info("Пергамент: свой архив команде отдан и подтверждён");
            }
        }
        if (lost) {
            Pergament.LOG.info("Пергамент: сервер начал карту команды заново — отдаю свой архив ещё раз");
            startArchive(s);
        }
        LocalMap.get().saveNow();                                     // свои регионы — в очередь записи раньше отметки
        long seq = m.seq();
        DimMap.onIo(() -> {                                             // поток карт: всё принятое уже записано
            synchronized (st) {
                if (seq >= 0) st.watermark = Math.max(st.watermark, seq);
            }
            save(s.profile, s.team, st);
        });
    }

    private static boolean hasArchiveFinds(Session s) {
        for (Object[] q : s.findQueue) if (q[2] != null) return true;
        return false;
    }

    private static boolean recent(Map<String, Long> m, String k, long now) {
        Long t = m.get(k);
        return t != null && now - t < RECENT_MS;
    }

    private static String key(String dim, int cx, int cz) {
        return dim + "|" + cx + "|" + cz;
    }

    private static UUID parse(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------- слияние в свою карту ----------

    private static void applyRegion(Session s, String dim, int rx, int rz, List<Object[]> recs, boolean live) {
        DimMap lm = LocalMap.get().live();
        if (lm != null && dim.equals(lm.dimension())) {
            RegionData target = lm.inMemory(rx, rz);
            if (target != null) {
                if (merge(target, recs, live) > 0) target.touch();
                return;
            }
            if (!lm.externalWrite(rx, rz, dir -> mergeFile(dir, dim, rx, rz, recs, live))) {
                String rk = dim + "|" + rx + "|" + rz + "|" + live;         // регион читается — повторим, склеив
                Object[] old = s.retry.get(rk);
                if (old != null) {
                    @SuppressWarnings("unchecked") List<Object[]> l = (List<Object[]>) old[3];
                    l.addAll(recs);
                } else if (s.retry.size() < MAX_RETRY) {
                    s.retry.put(rk, new Object[]{dim, rx, rz, new ArrayList<>(recs), live});
                } else {
                    DimMap.onIo(() -> mergeFile(WorldProfile.dir(s.profile, dim), dim, rx, rz, recs, live));
                }
            }
            return;
        }
        Path dir = WorldProfile.dir(s.profile, dim);
        DimMap.onIo(() -> mergeFile(dir, dim, rx, rz, recs, live));
    }

    static int merge(RegionData target, List<Object[]> recs, boolean live) {
        int n = 0;
        for (Object[] r : recs) {
            int cx = (int) r[0], cz = (int) r[1];
            if (!live && ChunkCodec.known(target, cx, cz)) continue;          // архив — только в пустое
            ChunkCodec.apply(target, cx, cz, (byte[]) r[2]);
            n++;
        }
        applied += n;
        return n;
    }

    private static void mergeFile(Path dir, String dim, int rx, int rz, List<Object[]> recs, boolean live) {
        try {
            Files.createDirectories(dir);
            Path dimTxt = dir.resolve("dim.txt");
            if (!Files.isRegularFile(dimTxt)) Files.writeString(dimTxt, dim, StandardCharsets.UTF_8);
            RegionData r = RegionFile.read(dir, rx, rz);
            if (r == null) r = new RegionData(rx, rz);
            if (merge(r, recs, live) > 0) RegionFile.write(dir, r);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: карта команды в регион {} {} не легла: {}", rx, rz, e.toString());
        }
    }

    // ---------- выгрузка ----------

    /** Скан своего чанка — в очередь серверу; докачка его уже не перезапишет. */
    public static void scanned(String dim, int cx, int cz) {
        Session s = cur;
        if (s == null) return;
        String k = key(dim, cx, cz);
        s.liveQueue.add(k);
        s.fresh.add(k);
    }

    public static void tick() {
        Session s = cur;
        if (s == null) return;
        if (!s.retry.isEmpty()) {
            List<Object[]> again = new ArrayList<>(s.retry.values());
            s.retry.clear();
            for (Object[] r : again) {
                @SuppressWarnings("unchecked") List<Object[]> recs = (List<Object[]>) r[3];
                applyRegion(s, (String) r[0], (int) r[1], (int) r[2], recs, (boolean) r[4]);
            }
        }
        sendLive(s);
        sendFinds(s);
        sendArchive(s);
    }

    /** Находки: до 32 одного измерения в пакете; метка архива — после последней находки своего файла. */
    private static void sendFinds(Session s) {
        Object[] first = s.findQueue.peek();
        if (first == null) return;
        String dim = (String) first[0];
        List<TeamNet.FindRec> recs = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        while (recs.size() < TeamNet.MAX_FINDS) {
            Object[] q = s.findQueue.peek();
            if (q == null || !q[0].equals(dim)) break;
            s.findQueue.poll();
            if (q[1] == null) {
                ended.add((String) q[2]);
                continue;
            }
            recs.add((TeamNet.FindRec) q[1]);
        }
        if (!recs.isEmpty()) {
            TeamNet.CHANNEL.sendToServer(new TeamNet.FindUp(++s.uploadId, dim, recs));
            sentFinds += recs.size();
        }
        for (String k : ended) s.archiveSent.put(k, s.uploadId);
    }

    private static void sendLive(Session s) {
        DimMap lm = LocalMap.get().live();
        if (lm == null || s.liveQueue.isEmpty()) return;
        String dim = lm.dimension();
        List<TeamNet.Rec> recs = new ArrayList<>();
        int bytes = 0;
        long now = System.currentTimeMillis();
        Iterator<String> it = s.liveQueue.iterator();
        while (it.hasNext() && recs.size() < LIVE_PER_TICK) {
            String k = it.next();
            String[] p = k.split("\\|");
            int cx = Integer.parseInt(p[1]), cz = Integer.parseInt(p[2]);
            RegionData r = p[0].equals(dim) ? lm.inMemory(cx >> 5, cz >> 5) : null;
            if (r == null || !ChunkCodec.known(r, cx, cz)) {            // ушёл из измерения / регион выгружен — этот скан не взять
                it.remove();
                continue;
            }
            byte[] z = ChunkCodec.deflate(ChunkCodec.extract(r, cx, cz));
            if (bytes + z.length > TeamNet.UP_BYTES) break;              // не влез — остаётся первым в очереди
            it.remove();
            bytes += z.length;
            recs.add(new TeamNet.Rec(cx, cz, z));
            s.recentOwn.put(k, now);
        }
        if (recs.isEmpty()) return;
        TeamNet.CHANNEL.sendToServer(new TeamNet.Upload(++s.uploadId, dim, true, recs));
        sentLive += recs.size();
        if (s.recentOwn.size() > 4096) s.recentOwn.values().removeIf(t -> now - t > RECENT_MS);
    }

    /** Архив: регионы своих измерений, ещё не подтверждённые сервером. */
    private static void startArchive(Session s) {
        LocalMap.get().saveNow();            // свежее из памяти — на диск раньше, чем архив начнут читать (та же очередь)
        Archive a = new Archive();
        Path worlds = WorldProfile.root().resolve("worlds").resolve(s.profile);
        try (Stream<Path> dims = Files.list(worlds)) {
            for (Path d : dims.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(d.resolve("dim.txt"))) continue;
                queueArchiveFinds(s, d);
                try (Stream<Path> fs = Files.list(d)) {
                    for (Path f : fs.filter(x -> x.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.pgmt")).sorted().toList()) {
                        a.todo.add(new Path[]{d, f});
                    }
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: свой архив не перечислен: {}", e.toString());
        }
        s.archive = a;
    }

    /** Свои жилы измерения — архивом (id события = id жилы: повтор после обрыва не раздует счётчики у команды). */
    private static void queueArchiveFinds(Session s, Path dimDir) {
        String key = "finds|" + dimDir;
        boolean done;
        synchronized (s.state) {
            done = s.state.archiveRegions.contains(key);
        }
        Path ff = dimDir.resolve("finds.json");
        if (done || !Files.isRegularFile(ff)) return;
        try {
            String dim = Files.readString(dimDir.resolve("dim.txt"), StandardCharsets.UTF_8).trim();
            DimMap lm = LocalMap.get().live();
            Finds finds = lm != null && dim.equals(lm.dimension()) ? lm.finds() : Finds.load(ff);
            for (Finds.Find f : finds.all()) {
                String eid = "agg-" + f.id;
                finds.markSeen(eid);
                s.findQueue.add(new Object[]{dim, toRec(eid, f), null});
            }
            s.findQueue.add(new Object[]{dim, null, key});
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: находки {} не отданы: {}", ff, e.toString());
        }
    }

    private static void sendArchive(Session s) {
        Archive a = s.archive;
        if (a == null) return;
        if (a.queue.size() < ARCHIVE_BUFFER && !a.busy.get()) {
            Path[] next = null;
            while (!a.todo.isEmpty()) {
                Path[] c = a.todo.poll();
                String rk = c[1].toString();
                boolean done;
                synchronized (s.state) {
                    done = s.state.archiveRegions.contains(rk);
                }
                if (!done && !s.archiveSent.containsKey(rk)) {
                    next = c;
                    break;
                }
            }
            if (next != null) {
                a.busy.set(true);
                Path[] job = next;
                DimMap.onIo(() -> readArchive(a, job));
            } else if (a.queue.isEmpty()) {
                s.archive = null;                                        // всё отправлено; засчитает подтверждение сервера
                Pergament.LOG.info("Пергамент: свой архив отправлен команде — {} чанков, жду подтверждения", sentArchive);
                return;
            }
        }
        Object[] first = a.queue.peek();
        if (first == null) return;
        String dim = (String) first[0];
        List<TeamNet.Rec> recs = new ArrayList<>();
        List<String> regionsEnded = new ArrayList<>();
        int bytes = 0;
        while (recs.size() < ARCHIVE_PER_TICK) {
            Object[] q = a.queue.peek();
            if (q == null || !q[0].equals(dim)) break;
            if (q[1] == null) {                                          // метка «регион кончился»
                a.queue.poll();
                regionsEnded.add((String) q[2]);
                continue;
            }
            TeamNet.Rec r = (TeamNet.Rec) q[1];
            if (bytes + r.z().length > TeamNet.UP_BYTES) break;
            a.queue.poll();
            bytes += r.z().length;
            recs.add(r);
        }
        if (!recs.isEmpty()) {
            TeamNet.CHANNEL.sendToServer(new TeamNet.Upload(++s.uploadId, dim, false, recs));
            sentArchive += recs.size();
        }
        for (String rk : regionsEnded) s.archiveSent.put(rk, s.uploadId);   // последний пакет региона — уже отправлен
    }

    private static void readArchive(Archive a, Path[] job) {
        try {
            String dim = Files.readString(job[0].resolve("dim.txt"), StandardCharsets.UTF_8).trim();
            String[] p = job[1].getFileName().toString().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            RegionData r = RegionFile.read(job[0], rx, rz);
            if (r != null) {
                for (int cz = rz << 5; cz < (rz << 5) + 32; cz++) {
                    for (int cx = rx << 5; cx < (rx << 5) + 32; cx++) {
                        if (!ChunkCodec.known(r, cx, cz)) continue;
                        a.queue.add(new Object[]{dim, new TeamNet.Rec(cx, cz, ChunkCodec.deflate(ChunkCodec.extract(r, cx, cz))), null});
                    }
                }
            }
            a.queue.add(new Object[]{dim, null, job[1].toString()});
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: регион архива {} не прочитан: {}", job[1], e.toString());
        } finally {
            a.busy.set(false);
        }
    }

    // ---------- состояние на диске ----------

    private static Path stateFile(String prof, UUID t) {
        return WorldProfile.root().resolve("worlds").resolve(prof).resolve("team_" + t + ".json");
    }

    private static State load(String prof, UUID t) {
        try {
            Path f = stateFile(prof, t);
            if (Files.isRegularFile(f)) {
                State s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class);
                if (s != null) {
                    if (s.archiveRegions == null) s.archiveRegions = new HashSet<>();
                    return s;
                }
            }
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: состояние команды не прочитано: {}", e.toString());
        }
        return new State();
    }

    private static void save(String prof, UUID t, State s) {
        try {
            Path f = stateFile(prof, t);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            String json;
            synchronized (s) {
                json = GSON.toJson(s);
            }
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Pergament.LOG.warn("Пергамент: состояние команды не записано: {}", e.toString());
        }
    }

    /** Самотест: известен ли чанк в живой карте (регион не в памяти — заказать чтение). */
    public static boolean knownForTest(int bx, int bz) {
        DimMap lm = LocalMap.get().live();
        if (lm == null) return false;
        lm.region(RegionData.regionOf(bx), RegionData.regionOf(bz), false);
        return lm.heightAt(bx, bz) != null;
    }
}
