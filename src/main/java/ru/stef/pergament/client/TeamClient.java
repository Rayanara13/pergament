package ru.stef.pergament.client;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.net.TeamNet;

import java.util.List;

/**
 * Клиентская командная часть: сказать серверу «у меня мод» и держать, где сейчас сокомандники.
 * Сервер без мода канала не держит — тогда молчим, сокомандников нет.
 */
public final class TeamClient {
    public static final int PROTOCOL = 1;
    private static final long STALE_MS = 5000;
    private static volatile List<TeamNet.Mate> mates = List.of();
    private static volatile long at;

    private TeamClient() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            if (TeamNet.CHANNEL.isRemotePresent(e.getConnection())) {
                TeamNet.CHANNEL.sendToServer(new TeamNet.Hello(PROTOCOL));
                Pergament.LOG.info("Пергамент: на сервере есть командная часть");
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> mates = List.of());
    }

    public static void onMates(List<TeamNet.Mate> m) {
        mates = List.copyOf(m);
        at = System.currentTimeMillis();
    }

    /** Онлайн-сокомандники; давно не было вестей от сервера — никого. */
    public static List<TeamNet.Mate> mates() {
        return System.currentTimeMillis() - at > STALE_MS ? List.of() : mates;
    }
}
