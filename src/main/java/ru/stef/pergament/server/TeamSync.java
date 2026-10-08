package ru.stef.pergament.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.network.PacketDistributor;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.net.TeamNet;
import ru.stef.pergament.store.ChunkCodec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Обмен картой в команде (сервер). Всё тяжёлое (диск, распаковка, сборка кадров) — на одном своём потоке;
 * на тике — только проверки и отправка готового. Всё, что прислал клиент, считается враждебным:
 * автор — из соединения, команда — из FTB на сервере, измерение — из реестра, размеры, темп и очередь — с потолком.
 * Отметки ({@link TeamNet.Mark}) уходят только после записи карты команды на диск: клиент не может запомнить
 * то, чего у сервера после падения не окажется.
 */
public final class TeamSync {
    private static final TeamSync INSTANCE = new TeamSync();
    /** Чанков в секунду с игрока (с запасом на рывок), задач в очереди с игрока, чанков в карте команды. */
    private static final int UP_RATE = 256, UP_BURST = 2048, MAX_INFLIGHT = 64;
    private static final long TEAM_QUOTA = 1_500_000;
    private static final long SYNC_COOLDOWN_NS = 5_000_000_000L;
    private static final int FRAME_EVERY = 5, MARK_EVERY = 20 * 30;

    private MinecraftServer server;
    private ExecutorService io;
    private final Map<UUID, TeamStore> stores = new HashMap<>();               // только поток io
    private final Map<UUID, Session> sessions = new HashMap<>();               // только серверный поток
    private int ticks;

    /** Игрок с модом (серверный поток; счётчики выгрузок — и поток io). */
    private static final class Session {
        UUID team;
        int epoch;
        double tokens = UP_BURST;
        long lastRefill = System.nanoTime(), lastSync;
        /** Докачка: записи (заполняет io), готовые кадры (отдаёт io), цель watermark и воплощение карты. */
        List<TeamStore.Entry> cursor;
        int pos;
        boolean producing, synced;
        long target = -1;
        UUID incarnation;
        final ConcurrentLinkedQueue<Object> frames = new ConcurrentLinkedQueue<>();
        /** Выгрузки: обработано на io (наибольший id), первый отброшенный id, задач в очереди. */
        final AtomicInteger processed = new AtomicInteger(-1), firstDropped = new AtomicInteger(Integer.MAX_VALUE),
                inflight = new AtomicInteger();
    }

    private TeamSync() {}

    public static TeamSync get() {
        return INSTANCE;
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> INSTANCE.start(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> INSTANCE.stop());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> INSTANCE.sessions.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::tick);
    }

    private void start(MinecraftServer s) {
        server = s;
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Pergament-TeamIO");
            t.setDaemon(true);
            return t;
        });
    }

    private void stop() {
        if (io == null) return;
        io.execute(this::flushAll);
        io.shutdown();
        try {
            io.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        stores.clear();
        io = null;
        sessions.clear();
        server = null;
    }

    private Path teamDir(UUID team) {
        return server.getWorldPath(LevelResource.ROOT).resolve("pergament").resolve("teams").resolve(team.toString());
    }

    private TeamStore store(UUID team, Path dir) {                              // поток io
        return stores.computeIfAbsent(team, k -> TeamStore.open(dir));
    }

    private void flushAll() {                                                   // поток io
        for (TeamStore st : stores.values()) flush(st);
    }

    private static boolean flush(TeamStore st) {
        try {
            st.flush();
            return true;
        } catch (Exception ex) {
            Pergament.LOG.warn("Пергамент: карта команды не записана: {}", ex.toString());
            return false;
        }
    }

    /** Текущая party игрока по FTB (на сервере, клиенту не верим). */
    private UUID partyNow(ServerPlayer p) {
        return TeamServer.get().teams().party(p);
    }

    private void send(ServerPlayer p, Object msg) {
        TeamNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg);
    }

    // ---------- тик: смена команды, кадры докачки, отметки ----------

    private void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || io == null) return;
        ticks++;
        TeamServer ts = TeamServer.get();
        if (ticks % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ts.hasMod(p.getUUID())) continue;
                Session s = sessions.computeIfAbsent(p.getUUID(), k -> new Session());
                UUID party = partyNow(p);
                if (!Objects.equals(party, s.team) || s.epoch == 0) {
                    Session fresh = new Session();                          // смена команды: всё старое — в мусор
                    fresh.team = party;
                    fresh.epoch = s.epoch + 1;
                    sessions.put(p.getUUID(), fresh);
                    send(p, new TeamNet.TeamInfo(party));
                }
            }
        }
        if (ticks % FRAME_EVERY == 0) pumpCatchup();
        if (ticks % MARK_EVERY == 0) marks();
    }

    // ---------- докачка ----------

    public void syncFrom(ServerPlayer p, UUID team, UUID incarnation, long watermark) {
        Session s = sessions.get(p.getUUID());
        long now = System.nanoTime();
        if (s == null || team == null || !team.equals(s.team) || !team.equals(partyNow(p)) || watermark < 0) return;
        if (s.producing || s.cursor != null || now - s.lastSync < SYNC_COOLDOWN_NS) return;   // одна докачка за раз
        s.lastSync = now;
        s.producing = true;
        UUID who = p.getUUID();
        Session mine = s;
        Path dir = teamDir(team);
        io.execute(() -> {
            TeamStore st = store(team, dir);
            flush(st);                                                       // отдаём только то, что уже на диске
            long wm = st.incarnation().equals(incarnation) ? watermark : 0;  // другое воплощение — с нуля
            List<TeamStore.Entry> list = st.since(wm);
            List<TeamStore.StoredFind> found = st.findsSince(wm);
            long target = st.seq();
            UUID inc = st.incarnation();
            server.execute(() -> {
                if (sessions.get(who) != mine) return;
                for (int i = 0; i < found.size(); ) {                       // находки — вперёд карты, кадрами по измерению
                    String dim = found.get(i).dim();
                    List<TeamNet.FindRec> l = new ArrayList<>();
                    while (i < found.size() && found.get(i).dim().equals(dim) && l.size() < TeamNet.MAX_FINDS_PUSH) {
                        l.add(found.get(i++).rec());
                    }
                    mine.frames.add(new TeamNet.FindPush(team, dim, l));
                }
                mine.cursor = list;
                mine.pos = 0;
                mine.target = target;
                mine.incarnation = inc;
                mine.producing = false;
                Pergament.LOG.info("Пергамент: докачка {} — {} чанков (seq {} → {})", p.getGameProfile().getName(),
                        list.size(), wm, target);
            });
        });
    }

    private void pumpCatchup() {
        for (var en : sessions.entrySet()) {
            Session s = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || s.team == null) continue;
            for (int k = 0; k < 2; k++) {                                   // до двух кадров за раз
                Object f = s.frames.poll();
                if (f == null) break;
                if (f instanceof TeamNet.Mark) s.synced = true;         // докачка кончилась — дальше живые отметки
                send(p, f);
            }
            if (s.cursor == null || s.producing || s.frames.size() >= 2) continue;
            if (s.pos >= s.cursor.size()) {
                s.frames.add(new TeamNet.Mark(s.team, s.incarnation, s.target, -1));
                s.cursor = null;
                continue;
            }
            s.producing = true;
            UUID team = s.team;
            List<TeamStore.Entry> cursor = s.cursor;
            int from = s.pos;
            Path dir = teamDir(team);
            io.execute(() -> {                                          // один кадр: одно измерение, ≤ PUSH_BYTES
                TeamStore st = store(team, dir);
                String dim = cursor.get(from).dim();
                List<TeamNet.Rec> live = new ArrayList<>(), arch = new ArrayList<>();
                int bytes = 0, i = from;
                while (i < cursor.size() && cursor.get(i).dim().equals(dim) && live.size() + arch.size() < TeamNet.MAX_PUSH
                        && bytes < TeamNet.PUSH_BYTES) {
                    TeamStore.Entry e = cursor.get(i++);
                    try {
                        byte[] raw = st.read(e.dim(), e.cx(), e.cz());
                        if (raw == null) continue;
                        byte[] z = ChunkCodec.deflate(raw);
                        bytes += z.length + 12;
                        (e.live() ? live : arch).add(new TeamNet.Rec(e.cx(), e.cz(), z));
                    } catch (Exception ex) {
                        Pergament.LOG.warn("Пергамент: чанк команды {} {} не прочитан: {}", e.cx(), e.cz(), ex.toString());
                    }
                }
                int next = i;
                server.execute(() -> {
                    if (sessions.get(en.getKey()) != s) return;
                    if (!live.isEmpty()) s.frames.add(new TeamNet.Push(team, dim, true, true, live));
                    if (!arch.isEmpty()) s.frames.add(new TeamNet.Push(team, dim, false, true, arch));
                    s.pos = next;
                    s.producing = false;
                });
            });
        }
    }

    /**
     * Раз в 30 с, по командам: записать карту на диск и только потом сказать каждому — до какого seq у него всё есть
     * (если докачался) и какие его выгрузки легли на диск.
     */
    private void marks() {
        Map<UUID, List<UUID>> byTeam = new HashMap<>();
        for (var en : sessions.entrySet()) {
            if (en.getValue().team != null) byTeam.computeIfAbsent(en.getValue().team, k -> new ArrayList<>()).add(en.getKey());
        }
        for (var t : byTeam.entrySet()) {
            Path dir = teamDir(t.getKey());
            Map<UUID, Session> who = new HashMap<>();
            for (UUID id : t.getValue()) who.put(id, sessions.get(id));
            io.execute(() -> {
                Map<UUID, Integer> acks = new HashMap<>();                  // что обработано ДО записи — то и легло
                for (var w : who.entrySet()) {
                    Session s = w.getValue();
                    acks.put(w.getKey(), Math.min(s.processed.get(), s.firstDropped.get() - 1));
                }
                TeamStore st = store(t.getKey(), dir);
                if (!flush(st)) return;
                long seq = st.seq();                                        // всё ≤ seq уже отдано серверному потоку
                UUID inc = st.incarnation();
                server.execute(() -> {
                    for (var w : who.entrySet()) {
                        Session s = w.getValue();
                        ServerPlayer p = server.getPlayerList().getPlayer(w.getKey());
                        if (p == null || sessions.get(w.getKey()) != s) continue;
                        send(p, new TeamNet.Mark(s.team, inc, s.synced ? seq : -1, acks.get(w.getKey())));
                    }
                });
            });
        }
    }

    // ---------- приём от клиента ----------

    public void upload(ServerPlayer p, TeamNet.Upload msg) {
        Session s = sessions.get(p.getUUID());
        UUID team = s == null ? null : s.team;
        if (team == null || msg.recs().isEmpty() || !team.equals(partyNow(p))) return;
        ResourceLocation dimId = ResourceLocation.tryParse(msg.dim());
        if (dimId == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId)) == null) return;
        long now = System.nanoTime();                                   // темп: ведро токенов
        s.tokens = Math.min(UP_BURST, s.tokens + (now - s.lastRefill) / 1e9 * UP_RATE);
        s.lastRefill = now;
        if (s.tokens < msg.recs().size() || s.inflight.get() >= MAX_INFLIGHT) {
            s.firstDropped.accumulateAndGet(msg.id(), Math::min);       // всё начиная с этого — без подтверждения
            Pergament.LOG.debug("Пергамент: {} шлёт карту быстрее, чем можно — пакет {} отброшен", p.getGameProfile().getName(), msg.id());
            return;
        }
        s.tokens -= msg.recs().size();
        s.inflight.incrementAndGet();
        UUID from = p.getUUID();
        String dim = dimId.toString();
        Path dir = teamDir(team);
        io.execute(() -> {
            List<TeamNet.Rec> accepted = new ArrayList<>();
            try {
                TeamStore st = store(team, dir);
                for (TeamNet.Rec r : msg.recs()) {
                    if (st.chunks() >= TEAM_QUOTA) break;
                    if (Math.abs(r.cx()) > 1_875_000 || Math.abs(r.cz()) > 1_875_000) continue;
                    try {
                        byte[] raw = ChunkCodec.inflate(r.z());
                        if (st.accept(dim, r.cx(), r.cz(), raw, msg.live(), from) != 0) accepted.add(r);
                    } catch (Exception ex) {
                        Pergament.LOG.debug("Пергамент: чанк от {} отброшен: {}", from, ex.toString());
                    }
                }
                s.processed.accumulateAndGet(msg.id(), Math::max);
            } finally {
                s.inflight.decrementAndGet();
            }
            if (accepted.isEmpty()) return;
            TeamNet.Push push = new TeamNet.Push(team, dim, msg.live(), false, accepted);
            server.execute(() -> relay(team, from, push));
        });
    }

    /** События находок: те же проверки, что у карты; новое по id — в карту команды и сокомандникам. */
    public void findUp(ServerPlayer p, TeamNet.FindUp msg) {
        Session s = sessions.get(p.getUUID());
        UUID team = s == null ? null : s.team;
        if (team == null || msg.finds().isEmpty() || !team.equals(partyNow(p))) return;
        ResourceLocation dimId = ResourceLocation.tryParse(msg.dim());
        if (dimId == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId)) == null) return;
        long now = System.nanoTime();
        s.tokens = Math.min(UP_BURST, s.tokens + (now - s.lastRefill) / 1e9 * UP_RATE);
        s.lastRefill = now;
        if (s.tokens < msg.finds().size() || s.inflight.get() >= MAX_INFLIGHT) {
            s.firstDropped.accumulateAndGet(msg.id(), Math::min);
            return;
        }
        s.tokens -= msg.finds().size();
        s.inflight.incrementAndGet();
        UUID from = p.getUUID();
        String dim = dimId.toString();
        Path dir = teamDir(team);
        io.execute(() -> {
            List<TeamNet.FindRec> accepted = new ArrayList<>();
            try {
                TeamStore st = store(team, dir);
                for (TeamNet.FindRec f : msg.finds()) if (f.sane() && st.acceptFind(dim, f)) accepted.add(f);
                s.processed.accumulateAndGet(msg.id(), Math::max);
            } finally {
                s.inflight.decrementAndGet();
            }
            if (accepted.isEmpty()) return;
            TeamNet.FindPush push = new TeamNet.FindPush(team, dim, accepted);
            server.execute(() -> relay(team, from, push));
        });
    }

    /** Свежее — всем онлайн-сокомандникам с модом, кроме автора. */
    private void relay(UUID team, UUID from, Object push) {
        for (var en : sessions.entrySet()) {
            if (en.getKey().equals(from) || !team.equals(en.getValue().team)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p != null && team.equals(partyNow(p))) send(p, push);
        }
    }
}
