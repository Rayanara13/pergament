package ru.stef.pergament.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.lwjgl.glfw.GLFW;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.gui.MapScreen;
import ru.stef.pergament.client.gui.MinimapHud;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.scan.TextureColors;

/** Клиентская проводка: конфиг, клавиши, тик скана, выход из мира. M занята FTB Chunks/Xaero, поэтому карта на J. */
public final class ClientSetup {
    public static final String CATEGORY = "key.categories.pergament";
    public static final KeyMapping OPEN_MAP = new KeyMapping("key.pergament.open_map",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping("key.pergament.toggle_minimap",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);

    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PergamentConfig.SPEC);
        modBus.addListener(ClientSetup::onRegisterKeys);
        modBus.addListener(ClientSetup::onRegisterOverlays);
        modBus.addListener(ClientSetup::onReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLogout);
        MinecraftForge.EVENT_BUS.addListener(OreWatch::onChat);
        MinecraftForge.EVENT_BUS.addListener(Deaths::onScreen);
        TeamClient.init();
        TeamSyncClient.init();
        TeamSelfTest.init();
        SelfTest.init();
        Pergament.LOG.info("Пергамент: клиент поднят, карта на клавише J");
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent e) {
        e.register(OPEN_MAP);
        e.register(TOGGLE_MINIMAP);
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("minimap", MinimapHud.INSTANCE);
    }

    /** Сменились ресурспаки — цвета блоков пересчитываются по новым текстурам. */
    private static void onReloadListeners(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener((ResourceManagerReloadListener) rm -> TextureColors.clear());
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalMap.get().tick();
        ru.stef.pergament.client.route.RoutePlan.get().tick();
        OreWatch.tick();
        Deaths.tick();
        TeamSyncClient.tick();
        ru.stef.pergament.client.xaero.XaeroImport.tick();
        while (OPEN_MAP.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new MapScreen());
        }
        while (TOGGLE_MINIMAP.consumeClick()) {
            PergamentConfig.MINIMAP.set(!PergamentConfig.MINIMAP.get());
            PergamentConfig.MINIMAP.save();
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ru.stef.pergament.client.xaero.XaeroImport.cancel();
        LocalMap.get().close();
    }
}
