package ru.stef.pergament.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import ru.stef.pergament.Pergament;

/**
 * Командный самотест, сторона сервера ({@code -Dpergament.teamtest=1}): ждёт Alice и Bob с модом,
 * Alice до команды разведывает +200 (её архив); затем party настоящими командами FTB Teams; Alice в команде
 * разведывает −200 (живые сканы). Bob там не был — его клиент пишет, знает ли он оба места. Потом Bob встаёт у +200
 * (разведано, вне прорисовки — Alice его видит) и у +600 (не разведано — не видит). В конце сервер выключается.
 */
public final class TeamSelfTest {
    private static int t, phase;
    private static int ax, az;

    private TeamSelfTest() {}

    public static void init() {
        if (System.getProperty("pergament.teamtest") == null) return;
        MinecraftForge.EVENT_BUS.addListener(TeamSelfTest::tick);
    }

    private static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        ServerPlayer a = server.getPlayerList().getPlayerByName("Alice"), b = server.getPlayerList().getPlayerByName("Bob");
        TeamServer ts = TeamServer.get();
        if (phase == 0) {
            if (a == null || b == null || !ts.hasMod(a.getUUID()) || !ts.hasMod(b.getUUID())) return;
            if (++t < 60) return;
            ax = a.getBlockX();
            az = a.getBlockZ();
            b.setGameMode(GameType.CREATIVE);
            a.setGameMode(GameType.CREATIVE);
            place(a, ax + 200, az);                                   // до команды: её архив
            Pergament.LOG.info("SELFTEST team alice scouts before team (+200)");
            phase = 10;
            t = 0;
        } else if (phase == 10 && ++t == 240) {
            place(a, ax, az);
            Pergament.LOG.info("SELFTEST team alice home");
        } else if (phase == 10 && t == 300) {
            run(server, a, "ftbteams party create pergtest");
            phase = 1;
            t = 0;
        } else if (phase == 1 && ++t == 20) {
            run(server, a, "ftbteams party invite Bob");
        } else if (phase == 1 && t == 40) {
            // FTB ищет команду по «короткому имени» (как кнопка [Accept]), не по имени party и не по UUID
            String shortName = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().getTeamForPlayer(a)
                    .map(dev.ftb.mods.ftbteams.api.Team::getShortName).orElse("?");
            run(server, b, "ftbteams party join " + shortName);
        } else if (phase == 1 && t == 60) {
            var pa = ts.teams().party(a);
            Pergament.LOG.info("SELFTEST team party alice={} bob={} same={}", pa, ts.teams().party(b),
                    pa != null && pa.equals(ts.teams().party(b)));
            place(a, ax - 200, az);                                   // в команде: живые сканы
            Pergament.LOG.info("SELFTEST team alice scouts in team (-200)");
            phase = 2;
            t = 0;
        } else if (phase == 2 && ++t == 240) {
            place(a, ax, az);                                         // и возвращается домой
            Pergament.LOG.info("SELFTEST team alice home");
        } else if (phase == 2 && t == 360) {
            Pergament.LOG.info("SELFTEST team bob check maps now");
            place(b, ax + 200, az);                                   // Bob там: разведано, вне прорисовки — виден
            Pergament.LOG.info("SELFTEST team bob explored (+200)");
        } else if (phase == 2 && t == 660) {
            place(b, ax + 600, az);                                   // Bob в неразведанном — не виден
            Pergament.LOG.info("SELFTEST team bob unexplored (+600)");
        } else if (phase == 2 && t == 960) {
            Pergament.LOG.info("SELFTEST team server done");
            server.halt(false);
        }
    }

    private static void place(ServerPlayer p, int x, int z) {
        var lvl = p.serverLevel();
        lvl.getChunk(x >> 4, z >> 4);                              // прогрузить, чтобы знать высоту
        int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        p.teleportTo(lvl, x + 0.5, y, z + 0.5, p.getYRot(), p.getXRot());
    }

    private static void run(MinecraftServer server, ServerPlayer as, String cmd) {
        int r = server.getCommands().performPrefixedCommand(as.createCommandSourceStack().withPermission(4), cmd);
        Pergament.LOG.info("SELFTEST team cmd '{}' as {} → {}", cmd, as.getGameProfile().getName(), r);
    }
}
