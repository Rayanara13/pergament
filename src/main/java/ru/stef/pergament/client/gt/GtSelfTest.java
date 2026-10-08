package ru.stef.pergament.client.gt;

import com.gregtechceu.gtceu.integration.map.cache.server.ServerCache;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import ru.stef.pergament.Pergament;

/**
 * Только для самотеста: на встроенном сервере «проспектор» GT по чанкам вокруг игрока —
 * тот же вызов, что делает предмет-проспектор. GT сам решает, какие жилы там есть, и шлёт их клиенту.
 * Зовётся рефлексией из SelfTest, когда GTCEu установлен.
 */
public final class GtSelfTest {
    private GtSelfTest() {}

    public static void prospectAround(Integer radius) {
        Minecraft mc = Minecraft.getInstance();
        var uuid = mc.player.getUUID();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            ChunkPos c = sp.chunkPosition();
            int n = 0;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    ServerCache.instance.prospectAllInChunk(sp.level().dimension(), new ChunkPos(c.x + dx, c.z + dz), sp);
                    n++;
                }
            }
            Pergament.LOG.info("SELFTEST GT проспектор: {} чанков вокруг {}", n, c);
        });
    }
}
