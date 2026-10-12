package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.gui.EntityMarks;
import ru.stef.pergament.client.gui.MapScreen;

/**
 * Командный самотест, сторона клиента ({@code -Dpergament.teamtest=1}): Alice держит открытой карту и раз в секунду
 * пишет, кого из сокомандников видит и нарисован ли он; Bob просто стоит. Сервер выключился — клиент закрывается.
 */
final class TeamSelfTest {
    private static int inWorld, gone, shots, homeX, homeZ, inTeam;

    /** Находка руками, как от OreWatch: событием команде и в свои жилы. */
    private static void find(ru.stef.pergament.client.map.DimMap live, String mat, int x, int z) {
        var f = new ru.stef.pergament.client.map.Finds.Find();
        f.mat = mat;
        f.name = mat;
        f.rgb = 0xC0A040;
        f.x = x; f.z = z; f.y = f.minY = f.maxY = 40;
        f.count = 3;
        f.byHand = true;
        TeamSyncClient.foundLocal(live.dimension(), live.finds(), f.copy());
        live.finds().addOrMerge(f);
        Pergament.LOG.info("SELFTEST team alice found {}", mat);
    }

    private static String findsText(ru.stef.pergament.client.map.DimMap live) {
        if (live == null) return "-";
        StringBuilder b = new StringBuilder();
        for (var f : live.finds().all()) b.append(f.mat).append('x').append(f.count).append(' ');
        return b.toString().trim();
    }
    private static boolean wasNear;
    private static int bobInTeam;

    /** Имена команды: одно и то же озеро (3×3 чанка) «открывается» на обоих клиентах. */
    private static void lake(ru.stef.pergament.client.map.DimMap live) {
        for (int z = 100; z <= 102; z++) {
            for (int x = 100; x <= 102; x++) {
                live.toponymy().put(ru.stef.pergament.client.geo.Toponyms.key(x, z),
                        ru.stef.pergament.client.geo.Geo.pack(ru.stef.pergament.client.geo.Geo.LAKE, 1f, 60, 0, 0));
            }
        }
    }

    private static String lakeLabel(ru.stef.pergament.client.map.DimMap live) {
        var l = live.toponymy().labels(0).stream().filter(x -> x.chunks() == 9).findFirst().orElse(null);
        return l == null ? "-" : l.itemId() + "/" + l.nameId() + "/" + l.custom();
    }

    private TeamSelfTest() {}

    static void init() {
        if (System.getProperty("pergament.teamtest") == null) return;
        MinecraftForge.EVENT_BUS.addListener(TeamSelfTest::tick);
    }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (inWorld > 0 && ++gone > 60) mc.stop();
            if (inWorld == 0 && mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                Pergament.LOG.info("SELFTEST team вход сорвался — переподключаюсь");     // таймаут рукопожатия бывает
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("localhost:25565"),
                        new net.minecraft.client.multiplayer.ServerData("teamtest", "localhost:25565", false), false);
            }
            return;
        }
        gone = 0;
        inWorld++;
        boolean alice = "Alice".equals(mc.getUser().getName());
        if (inWorld == 1) {
            homeX = mc.player.getBlockX();
            homeZ = mc.player.getBlockZ();
        }
        var live = ru.stef.pergament.client.map.LocalMap.get().live();
        if (!alice) {
            if (TeamSyncClient.team() != null && live != null) {           // имена: открыл позже Alice, переименовал
                int t = ++bobInTeam;
                if (t == 300) lake(live);
                if (t > 300 && t % 40 == 0) {
                    Pergament.LOG.info("SELFTEST team bob topo label={} gotTopos={} sentTopos={}", lakeLabel(live),
                            TeamSyncClient.gotTopos, TeamSyncClient.sentTopos);
                }
                if (t == 520) {
                    var l = live.toponymy().labels(0).stream().filter(x -> x.chunks() == 9).findFirst().orElse(null);
                    if (l != null) live.toponymy().rename(l.itemId(), "Общее озеро");
                    Pergament.LOG.info("SELFTEST team bob renames {}", l == null ? "-" : l.itemId());
                }
            }
            if (inWorld > 200 && inWorld % 20 == 0) {
                Pergament.LOG.info("SELFTEST team bob at={},{} team={} knowsPlus200={} knowsMinus200={} got={} applied={} finds={}",
                        mc.player.getBlockX(), mc.player.getBlockZ(), TeamSyncClient.team() != null,
                        TeamSyncClient.knownForTest(homeX + 200, homeZ), TeamSyncClient.knownForTest(homeX - 200, homeZ),
                        TeamSyncClient.gotChunks, TeamSyncClient.applied, findsText(live));
            }
            return;
        }
        // находки: одна до команды (уйдёт архивом), одна в команде (живым событием)
        if (inWorld == 150 && live != null) find(live, "pergtest:pre", homeX + 30, homeZ);
        if (TeamSyncClient.team() != null && ++inTeam == 100 && live != null) find(live, "pergtest:live", homeX - 30, homeZ);
        if (inTeam == 60 && live != null) lake(live);                       // имена: Alice открывает озеро первой
        if (inTeam > 60 && inTeam % 40 == 0 && live != null) {
            Pergament.LOG.info("SELFTEST team alice topo label={} sentTopos={} gotTopos={}", lakeLabel(live),
                    TeamSyncClient.sentTopos, TeamSyncClient.gotTopos);
        }
        if (inWorld > 200 && inWorld % 100 == 0 && live != null) {
            Pergament.LOG.info("SELFTEST team alice finds={} sentFinds={} gotFinds={}", findsText(live),
                    TeamSyncClient.sentFinds, TeamSyncClient.gotFinds);
        }
        if (inWorld > 200 && inWorld % 100 == 0) {
            Pergament.LOG.info("SELFTEST team alice sync team={} sentLive={} sentArchive={}", TeamSyncClient.team() != null,
                    TeamSyncClient.sentLive, TeamSyncClient.sentArchive);
        }
        if (inWorld == 200) {
            MapScreen.setZoom(1);                                     // Bob в 200 блоках — должен влезть в кадр
            mc.setScreen(new MapScreen());
        }
        if (inWorld > 200 && inWorld % 20 == 0) {
            if (!(mc.screen instanceof MapScreen)) mc.setScreen(new MapScreen());
            var mates = TeamClient.mates();
            String where = mates.isEmpty() ? "-" : mates.get(0).name() + "@" + mates.get(0).x() + "," + mates.get(0).z();
            boolean drawn = !EntityMarks.LAST_MATES.isEmpty();
            boolean entity = mates.stream().anyMatch(m -> mc.level.getPlayerByUUID(m.id()) != null);
            Pergament.LOG.info("SELFTEST team alice at={},{} mates={} first={} entityTracked={} drawnOnMap={}",
                    mc.player.getBlockX(), mc.player.getBlockZ(), mates.size(), where, entity, drawn);
            if (drawn && !wasNear && shots < 1) {
                wasNear = true;
                shots++;
                Screenshot.grab(mc.gameDirectory, "pergament_team_mate.png", mc.getMainRenderTarget(), msg -> { });
            }
        }
    }
}
