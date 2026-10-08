package ru.stef.pergament.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.PacketDistributor;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.net.TeamNet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Серверная командная часть: работает и на выделенном сервере, и во встроенном (одиночка/LAN).
 * Раз в полсекунды каждому игроку с модом — где сейчас его онлайн-сокомандники по party.
 */
public final class TeamServer {
    private static final TeamServer INSTANCE = new TeamServer();
    private static final int PERIOD = 10;

    private final Set<UUID> modded = new HashSet<>();
    private final Set<UUID> hadMates = new HashSet<>();
    private Teams teams = Teams.NONE;
    private int ticks;

    private TeamServer() {}

    public static TeamServer get() {
        return INSTANCE;
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> INSTANCE.start());
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> INSTANCE.stop());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> INSTANCE.left(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::tick);
    }

    private void start() {
        teams = Teams.detect();
        Pergament.LOG.info("Пергамент: командная часть сервера {}", teams == Teams.NONE ? "без FTB Teams — спит" : "на FTB Teams");
    }

    private void stop() {
        modded.clear();
        hadMates.clear();
        teams = Teams.NONE;
    }

    private void left(UUID id) {
        modded.remove(id);
        hadMates.remove(id);
    }

    public void hello(ServerPlayer p, int protocol) {
        if (modded.add(p.getUUID())) Pergament.LOG.info("Пергамент: у {} есть мод (протокол {})", p.getGameProfile().getName(), protocol);
    }

    public boolean hasMod(UUID id) {
        return modded.contains(id);
    }

    public Teams teams() {
        return teams;
    }

    private void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || teams == Teams.NONE || modded.isEmpty() || ++ticks % PERIOD != 0) return;
        var server = e.getServer();
        for (UUID id : List.copyOf(modded)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            List<TeamNet.Mate> out = new ArrayList<>();
            for (ServerPlayer m : teams.onlineMates(p)) {
                out.add(new TeamNet.Mate(m.getUUID(), m.getGameProfile().getName(), m.level().dimension().location().toString(),
                        m.getBlockX(), m.getBlockY(), m.getBlockZ(), m.getYRot()));
            }
            if (out.isEmpty() && !hadMates.remove(id)) continue;  // пустой список — один раз, чтобы клиент забыл
            if (!out.isEmpty()) hadMates.add(id);
            TeamNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new TeamNet.Mates(out));
        }
    }
}
