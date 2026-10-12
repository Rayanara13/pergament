package ru.stef.pergament.client.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Имена местности команды — отдельный слой поверх своего реестра, привязанный к UUID команды (вышел из команды —
 * слой не применяется и никуда не утекает; другая команда — слой с нуля). Каждую запись слоя сервер нумерует
 * (teamSeq — кто открыл раньше) и ревизует (rev — монотонно на каждое изменение): применяется только более новая
 * ревизия, поэтому запоздавшее эхо не затрёт свежее. Своё переименование висит «в пути», пока сервер не ответит
 * на этот запрос. Чистая логика — без Minecraft, под тесты.
 */
public final class TeamTopo {
    /** Запись команды: как у своей, плюс ревизия и автор (UUID — от сервера). */
    public static final class Rec {
        public String id, type, nameId, custom, author;
        public long anchor, teamSeq, rev;
    }

    /** Слой команды одного измерения (toponyms_team.json). */
    public static final class Overlay {
        public String team;
        public List<Rec> items = new ArrayList<>();
    }

    /** Переименование в пути: id → своё имя ("" — вернуть выданное), номер запроса, отправленного с ним (0 — не отправлено). */
    public static final class Pending {
        public String custom;
        public int req;
        public long sentAt;

        public Pending(String custom) {
            this.custom = custom;
        }
    }

    private TeamTopo() {}

    /** Слой для команды team: чужой команды — пустой (старое не применяется). */
    public static Overlay forTeam(Overlay o, UUID team) {
        if (o != null && team != null && team.toString().equals(o.team)) return o;
        Overlay fresh = new Overlay();
        fresh.team = team == null ? null : team.toString();
        return fresh;
    }

    /** Влить записи от сервера: новая — добавить, известная — только если ревизия новее. true — слой изменился. */
    public static boolean merge(Overlay o, List<Rec> recs) {
        Map<String, Rec> by = new HashMap<>();
        for (Rec r : o.items) by.put(r.id, r);
        boolean changed = false;
        for (Rec r : recs) {
            Rec old = by.get(r.id);
            if (old == null) {
                o.items.add(r);
                by.put(r.id, r);
                changed = true;
            } else if (r.rev > old.rev) {
                old.type = r.type;
                old.nameId = r.nameId;
                old.custom = r.custom;
                old.author = r.author;
                old.anchor = r.anchor;
                old.teamSeq = r.teamSeq;
                old.rev = r.rev;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Ответ сервера на свой запрос req: переименования этого запроса больше не «в пути» (истина — у сервера);
     * отвергнутые — запомнить, чтобы не слать их снова в этой сессии.
     */
    public static void acked(Map<String, Pending> pending, int req, List<String> rejected, Set<String> refused) {
        pending.values().removeIf(p -> p.req == req && req > 0);
        refused.addAll(rejected);
    }

    /**
     * Реестр для расчёта: записи команды (с её порядком) вместо своих с тем же id, свои — остальные; переименования
     * в пути — поверх. seq — свой счётчик (новые записи расчёта — свои).
     */
    public static Toponyms.Registry view(Toponyms.Registry mine, Overlay team, Map<String, Pending> pending) {
        Toponyms.Registry v = new Toponyms.Registry();
        v.seq = mine.seq;
        Set<String> teamIds = new HashSet<>();
        if (team != null) {
            for (Rec r : team.items) {
                teamIds.add(r.id);
                Toponyms.Item it = new Toponyms.Item();
                it.id = r.id;
                it.type = r.type;
                it.nameId = r.nameId;
                it.name = Names.display(r.nameId, "en", r.nameId);
                it.anchor = r.anchor;
                it.teamSeq = r.teamSeq;
                it.custom = r.custom == null || r.custom.isEmpty() ? null : r.custom;
                v.items.add(it);
            }
        }
        for (Toponyms.Item it : mine.items) if (!teamIds.contains(it.id)) v.items.add(copy(it));
        if (pending != null) {
            for (Toponyms.Item it : v.items) {
                Pending p = pending.get(it.id);
                if (p != null) it.custom = p.custom.isEmpty() ? null : p.custom;
            }
        }
        return v;
    }

    /** После расчёта по view: новые записи (их не было ни у меня, ни у команды) — в свой реестр. */
    public static boolean adopt(Toponyms.Registry mine, Toponyms.Registry view, int viewSizeBefore) {
        boolean added = false;
        for (int i = viewSizeBefore; i < view.items.size(); i++) {
            mine.items.add(view.items.get(i));
            added = true;
        }
        mine.seq = Math.max(mine.seq, view.seq);
        for (Toponyms.Item v : view.items) {                       // id старым записям (раньше без id) — и у себя
            for (Toponyms.Item m : mine.items) if (m != v && m.id == null && m.anchor == v.anchor && m.type.equals(v.type)) m.id = v.id;
        }
        return added;
    }

    /**
     * Что отдать команде: свои записи, которых у команды нет (и которые сервер не отверг), и переименования в пути,
     * ещё не отправленные (или отправленные давно — без ответа). Не больше max.
     */
    public static List<Rec> outgoing(Toponyms.Registry mine, Overlay team, Map<String, Pending> pending, Set<String> refused,
                                     Map<String, Long> inFlight, long now, long retryMs, int max) {
        Set<String> teamIds = new HashSet<>();
        if (team != null) for (Rec r : team.items) teamIds.add(r.id);
        List<Rec> out = new ArrayList<>();
        for (Toponyms.Item it : mine.items) {
            if (out.size() >= max) break;
            Long sent = inFlight.get(it.id);                         // отправлена и ответа ещё ждём
            if (it.id == null || teamIds.contains(it.id) || refused.contains(it.id) || sent != null && now - sent < retryMs) continue;
            Rec r = toRec(it);
            Pending p = pending.get(it.id);
            if (p != null) r.custom = p.custom;
            out.add(r);
        }
        for (var e : pending.entrySet()) {
            if (out.size() >= max) break;
            Pending p = e.getValue();
            if (refused.contains(e.getKey()) || p.req > 0 && now - p.sentAt < retryMs) continue;
            if (out.stream().anyMatch(r -> r.id.equals(e.getKey()))) continue;
            Rec r = null;
            if (team != null) for (Rec t : team.items) if (t.id.equals(e.getKey())) r = copyRec(t);
            if (r == null) {
                for (Toponyms.Item it : mine.items) if (e.getKey().equals(it.id)) r = toRec(it);
            }
            if (r == null) continue;
            r.custom = p.custom;
            out.add(r);
        }
        return out;
    }

    static Rec toRec(Toponyms.Item it) {
        Rec r = new Rec();
        r.id = it.id;
        r.type = it.type;
        r.nameId = it.nameId;
        r.anchor = it.anchor;
        r.custom = it.custom == null ? "" : it.custom;
        return r;
    }

    private static Rec copyRec(Rec t) {
        Rec r = new Rec();
        r.id = t.id;
        r.type = t.type;
        r.nameId = t.nameId;
        r.anchor = t.anchor;
        r.custom = t.custom;
        r.teamSeq = t.teamSeq;
        r.rev = t.rev;
        r.author = t.author;
        return r;
    }

    private static Toponyms.Item copy(Toponyms.Item s) {
        Toponyms.Item it = new Toponyms.Item();
        it.id = s.id;
        it.type = s.type;
        it.nameId = s.nameId;
        it.name = s.name;
        it.anchor = s.anchor;
        it.seq = s.seq;
        it.custom = s.custom;
        it.teamSeq = 0;                                             // своя запись: командного порядка нет
        return it;
    }
}
