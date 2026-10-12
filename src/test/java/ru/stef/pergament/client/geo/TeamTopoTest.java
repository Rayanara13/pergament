package ru.stef.pergament.client.geo;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static ru.stef.pergament.client.geo.Toponyms.key;

class TeamTopoTest {
    private static TeamTopo.Rec rec(String id, long anchor, String nameId, String custom, long teamSeq, long rev) {
        TeamTopo.Rec r = new TeamTopo.Rec();
        r.id = id;
        r.type = Toponyms.LAKE;
        r.anchor = anchor;
        r.nameId = nameId;
        r.custom = custom;
        r.teamSeq = teamSeq;
        r.rev = rev;
        return r;
    }

    private static Long2LongOpenHashMap lake() {
        var cells = new Long2LongOpenHashMap();
        for (int z = 0; z <= 2; z++) for (int x = 0; x <= 2; x++) cells.put(key(x, z), Geo.pack(Geo.LAKE, 1f, 60, 0, 0));
        return cells;
    }

    @Test
    void olderRevisionNeverOverwritesNewer() {
        var o = new TeamTopo.Overlay();
        assertTrue(TeamTopo.merge(o, List.of(rec("a", 1, "lake:1", "Новое", 1, 5))));
        assertFalse(TeamTopo.merge(o, List.of(rec("a", 1, "lake:1", "Старое", 1, 3))));   // запоздавшее эхо
        assertEquals("Новое", o.items.get(0).custom);
        assertTrue(TeamTopo.merge(o, List.of(rec("a", 1, "lake:1", "", 1, 6))));
        assertEquals("", o.items.get(0).custom);
    }

    @Test
    void otherTeamsLayerIsNotApplied() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var o = TeamTopo.forTeam(null, a);
        TeamTopo.merge(o, List.of(rec("x", 1, "lake:1", "", 1, 1)));
        assertSame(o, TeamTopo.forTeam(o, a));
        assertTrue(TeamTopo.forTeam(o, b).items.isEmpty());             // другая команда — слой с нуля
        assertTrue(TeamTopo.forTeam(o, null).items.isEmpty());          // без команды — ничего командного
    }

    @Test
    void teammatesEarlierNameWinsMyOwn() {
        var mine = new Toponyms.Registry();
        Toponyms.compute(lake(), mine, "w");                            // я назвал озеро сам
        String myName = mine.items.get(0).nameId;
        var o = new TeamTopo.Overlay();
        TeamTopo.merge(o, List.of(rec("mate", key(1, 1), "lake:3".equals(myName) ? "lake:4" : "lake:3", "", 7, 1)));
        var v = TeamTopo.view(mine, o, new HashMap<>());
        var label = Toponyms.compute(lake(), v, "w").labels().get(0);
        assertEquals("mate", label.itemId());                           // у команды раньше — её имя
    }

    @Test
    void pendingRenameShowsUntilServerAnswers() {
        var mine = new Toponyms.Registry();
        Toponyms.compute(lake(), mine, "w");
        String id = mine.items.get(0).id;
        var o = new TeamTopo.Overlay();
        TeamTopo.merge(o, List.of(rec(id, mine.items.get(0).anchor, mine.items.get(0).nameId, "", 1, 1)));
        Map<String, TeamTopo.Pending> pending = new HashMap<>();
        pending.put(id, new TeamTopo.Pending("Тапкино болото"));
        assertEquals("Тапкино болото", Toponyms.compute(lake(), TeamTopo.view(mine, o, pending), "w").labels().get(0).custom());
        pending.get(id).req = 4;
        TeamTopo.acked(pending, 4, List.of(), new HashSet<>());
        assertTrue(pending.isEmpty());
    }

    @Test
    void outgoingSendsOnlyWhatTeamLacks() {
        var mine = new Toponyms.Registry();
        Toponyms.compute(lake(), mine, "w");
        var o = new TeamTopo.Overlay();
        Map<String, Long> inFlight = new HashMap<>();
        var out = TeamTopo.outgoing(mine, o, new HashMap<>(), new HashSet<>(), inFlight, 1000, 30_000, 64);
        assertEquals(1, out.size());
        inFlight.put(out.get(0).id, 1000L);
        assertTrue(TeamTopo.outgoing(mine, o, new HashMap<>(), new HashSet<>(), inFlight, 2000, 30_000, 64).isEmpty());
        assertEquals(1, TeamTopo.outgoing(mine, o, new HashMap<>(), new HashSet<>(), inFlight, 40_000, 30_000, 64).size());
        TeamTopo.merge(o, List.of(rec(out.get(0).id, out.get(0).anchor, out.get(0).nameId, "", 1, 1)));
        assertTrue(TeamTopo.outgoing(mine, o, new HashMap<>(), new HashSet<>(), new HashMap<>(), 50_000, 30_000, 64).isEmpty());
        var refused = new HashSet<String>();
        refused.add(out.get(0).id);
        assertTrue(TeamTopo.outgoing(mine, new TeamTopo.Overlay(), new HashMap<>(), refused, new HashMap<>(), 0, 1, 64).isEmpty());
    }

    @Test
    void newNamesAvoidTeamNames() {
        var mine = new Toponyms.Registry();
        var o = new TeamTopo.Overlay();
        var far = new Long2LongOpenHashMap();
        for (int z = 0; z <= 2; z++) for (int x = 100; x <= 102; x++) far.put(key(x, z), Geo.pack(Geo.LAKE, 1f, 60, 0, 0));
        var v0 = TeamTopo.view(mine, o, new HashMap<>());
        String would = Toponyms.compute(far, v0, "w").labels().get(0).nameId();   // что выдалось бы без команды
        TeamTopo.merge(o, List.of(rec("mate", key(500, 500), would, "", 1, 1)));  // у товарища это имя уже есть
        var v = TeamTopo.view(new Toponyms.Registry(), o, new HashMap<>());
        int before = v.items.size();
        var label = Toponyms.compute(far, v, "w").labels().get(0);
        assertNotEquals(would, label.nameId());
        var mine2 = new Toponyms.Registry();
        assertTrue(TeamTopo.adopt(mine2, v, before));
        assertEquals(1, mine2.items.size());
    }
}
