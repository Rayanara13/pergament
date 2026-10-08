package ru.stef.pergament;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа. Карта — клиентская (пакет {@code client}, на сервере его классы не загружаются).
 * Серверная часть ({@code server}, {@code net}) — только обмен в команде FTB Teams; без FTB Teams
 * или без мода на сервере всё работает автономно, как раньше.
 */
@Mod(Pergament.MOD_ID)
public final class Pergament {
    public static final String MOD_ID = "pergament";
    public static final Logger LOG = LoggerFactory.getLogger("Pergament");

    public Pergament() {
        // сервер без мода и клиент с модом совместимы в обе стороны
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, fromServer) -> true));
        ru.stef.pergament.net.TeamNet.register();
        ru.stef.pergament.server.TeamServer.init();               // и выделенный, и встроенный сервер
        ru.stef.pergament.server.TeamSync.init();
        ru.stef.pergament.server.TeamSelfTest.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ru.stef.pergament.client.ClientSetup.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
