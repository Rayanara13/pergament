package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.gui.MapScreen;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.store.RegionData;
import ru.stef.pergament.store.RegionFile;
import ru.stef.pergament.client.store.WorldProfile;

import java.nio.file.Files;

/**
 * Самотест в живом клиенте, без рук: {@code gradlew runClient -Ppergament_selftest}.
 * Титульный экран → «Создать мир» (новый одиночный мир) → ждём, пока обзор отсканирует окрестности
 * → открываем карту → скриншот {@code screenshots/pergament_selftest.png} → регионы на диск,
 * проверка файлов → строка SELFTEST в логе → выход. Без флага не делает ничего.
 * {@code -Ppergament_dims=nether,end}: потом наблюдателем в каждое измерение и кадр
 * {@code screenshots/pergament_<dim>.png} (наблюдатель — чтобы не сгореть в лаве и не застрять в стене).
 */
public final class SelfTest {
    public static final boolean ON = "1".equals(System.getProperty("pergament.selftest"));
    private static final int TIMEOUT_TICKS = 20 * 240;

    private enum Step { TITLE, CREATE, PLAYING, MAP, GT_WAIT, GT_MAP, TRAVEL, DIM_MAP, DONE }

    private static Step step = Step.TITLE;
    private static int ticks, inWorld, onMap, stable, lastScanned = -1;
    private static int dimIdx, travelTicks;
    private static volatile int NEAR_ID = -1, DEEP_ID = -1;
    private static net.minecraft.core.BlockPos DEATH_AT;

    private SelfTest() {}

    public static void init() {
        if (!ON) return;
        Pergament.LOG.info("SELFTEST включён");
        MinecraftForge.EVENT_BUS.addListener(SelfTest::onScreen);
        MinecraftForge.EVENT_BUS.addListener(SelfTest::onTick);
    }

    private static float zoom() {
        return Float.parseFloat(System.getProperty("pergament.selftest.zoom", "1"));
    }

    private static void onScreen(ScreenEvent.Init.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (step == Step.TITLE && e.getScreen() instanceof TitleScreen) {
            step = Step.CREATE;
            CreateWorldScreen.openFresh(mc, e.getScreen());
        } else if (step == Step.CREATE && e.getScreen() instanceof CreateWorldScreen cws) {
            if (Showcase.enabled()) {                               // витрина: свой сид, творческий режим
                String seed = System.getProperty("pergament.selftest.seed");
                if (seed != null) cws.getUiState().setSeed(seed);
                cws.getUiState().setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
            }
            for (var l : e.getListenersList()) {
                if (l instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents tc
                        && "selectWorld.create".equals(tc.getKey())) {
                    step = Step.PLAYING;
                    Pergament.LOG.info("SELFTEST создаю мир");
                    mc.execute(b::onPress);
                    return;
                }
            }
            Pergament.LOG.warn("SELFTEST кнопка «Создать мир» не найдена");
        }
    }

    private static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || step == Step.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (++ticks > (Showcase.enabled() ? TIMEOUT_TICKS * 4 : TIMEOUT_TICKS)) {
            finish(mc, "timeout");
            return;
        }
        LocalMap map = LocalMap.get();
        if (step == Step.PLAYING && mc.player != null && mc.level != null && mc.screen == null) {
            inWorld++;
            int scanned = map.scannedTotal();
            stable = scanned == lastScanned && map.queued() == 0 && scanned > 0 ? stable + 1 : 0;
            lastScanned = scanned;
            if (inWorld == 1 && System.getProperty("pergament.selftest.keep") != null) {
                PergamentConfig.COLOR_KEEP.set(Double.parseDouble(System.getProperty("pergament.selftest.keep")));
            }
            if (inWorld > 100 && stable >= 40) {
                MapScreen.setZoom(zoom());
                mc.setScreen(new MapScreen());
                step = Step.MAP;
            }
        } else if (step == Step.MAP && Showcase.enabled()) {
            if (Showcase.tick(mc)) finish(mc, "ok");
        } else if (step == Step.MAP && System.getProperty("pergament.selftest.gallery") != null) {
            // -Ppergament_gallery: только галерея голов, постранично в скриншоты
            int t = ++onMap;
            if (t == 1) mc.setScreen(new ru.stef.pergament.client.gui.HeadGalleryScreen());
            if (mc.screen instanceof ru.stef.pergament.client.gui.HeadGalleryScreen gs && t % 10 == 0) {
                int pg = t / 10 - 1;
                if (pg >= gs.pages()) {
                    finish(mc, "ok");
                } else {
                    Screenshot.grab(mc.gameDirectory, "pergament_heads_" + pg + ".png", mc.getMainRenderTarget(), msg -> { });
                    gs.page(pg + 1);
                }
            }
        } else if (step == Step.MAP) {
            // -Ppergament_keeps=0.6,0.8,0.95: один и тот же вид с разной насыщенностью, кадр на каждую
            String keeps = System.getProperty("pergament.selftest.keeps");
            if (keeps != null) {
                String[] ks = keeps.split(",");
                int k = onMap / 60;
                if (onMap % 60 == 0 && k < ks.length) PergamentConfig.COLOR_KEEP.set(Double.parseDouble(ks[k].trim()));
                if (onMap % 60 == 59 && k < ks.length) {
                    Screenshot.grab(mc.gameDirectory, "pergament_keep_" + ks[k].trim() + ".png", mc.getMainRenderTarget(),
                            msg -> { });
                }
                if (++onMap >= 60 * ks.length) finish(mc, "ok");
            } else if (++onMap == 10 && mc.screen instanceof MapScreen ms && mc.player != null) {
                // метка: создать, сохранить, перечитать с диска, открыть в панели
                var m = new ru.stef.pergament.client.map.Markers.Marker();
                m.name = "Ангар";
                m.desc = "пол из шиферного сланца";
                m.icon = "house";
                m.x = mc.player.getBlockX() + 24; m.z = mc.player.getBlockZ() - 12; m.y = mc.player.getBlockY();
                LocalMap.get().markers().put(m);
                var back = ru.stef.pergament.client.map.Markers.load(
                        WorldProfile.dir(LocalMap.get().profile(), LocalMap.get().dimension()).resolve("markers.json"));
                Pergament.LOG.info("SELFTEST marker saved={} name={} icon={}", back.byId(m.id) != null,
                        back.byId(m.id) == null ? "-" : back.byId(m.id).name, back.byId(m.id) == null ? "-" : back.byId(m.id).icon);
                ms.debugOpenMarker(m.copy());
                // тропа: от игрока на 70 блоков к юго-востоку — по разведанному
                var rp = ru.stef.pergament.client.route.RoutePlan.get();
                rp.clear();
                rp.add(mc.player.getBlockX(), mc.player.getBlockZ());
                rp.add(mc.player.getBlockX() + 60, mc.player.getBlockZ() + 40);
                MapScreen.tool = MapScreen.Tool.ROUTE;
            } else if (onMap == 24 && mc.screen instanceof MapScreen) {
                Screenshot.grab(mc.gameDirectory, "pergament_marker.png", mc.getMainRenderTarget(), msg -> { });
                MapScreen.debugPicker(true);
            } else if (onMap == 30 && mc.screen instanceof MapScreen) {
                Screenshot.grab(mc.gameDirectory, "pergament_picker.png", mc.getMainRenderTarget(), msg -> { });
                MapScreen.debugPicker(false);
                mc.setScreen(new MapScreen());                      // заново: панель метки закрыта, режим тропы с кнопками
            } else if (onMap == 50) {
                var rp = ru.stef.pergament.client.route.RoutePlan.get();
                Pergament.LOG.info("SELFTEST route={} points={} pathLen={} stats={}", rp.status(), rp.points().size(),
                        rp.path().size(), rp.stats());
                Screenshot.grab(mc.gameDirectory, "pergament_route.png", mc.getMainRenderTarget(), msg -> { });
            } else if (onMap == 60 && mc.player != null) {
                // линейка, штрих пером, список меток — один кадр
                int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
                MapScreen.debugRuler(new int[]{px - 40, pz + 20}, new int[]{px + 10, pz + 50}, new int[]{px + 60, pz + 30});
                var st = new ru.stef.pergament.client.map.Drawings.Stroke();
                for (int k = 0; k <= 20; k++) st.pts.add(new int[]{px - 50 + k * 4, pz - 30 + (int) (8 * Math.sin(k / 2.0))});
                LocalMap.get().drawings().add(st);
                var back = ru.stef.pergament.client.map.Drawings.load(
                        WorldProfile.dir(LocalMap.get().profile(), LocalMap.get().dimension()).resolve("drawings.json"));
                Pergament.LOG.info("SELFTEST drawings saved={} rulerTotal={}", back.all().size(),
                        MapScreen.rulerTotalForTest());
                MapScreen.tool = MapScreen.Tool.RULER;
                MapScreen.listOpenForTest(true);
                mc.setScreen(new MapScreen());
            } else if (onMap == 70) {
                Screenshot.grab(mc.gameDirectory, "pergament_tools.png", mc.getMainRenderTarget(), msg -> { });
                MapScreen.listOpenForTest(false);
                mc.setScreen(new ru.stef.pergament.client.gui.SettingsScreen(new MapScreen()));
            } else if (onMap == 78) {
                Screenshot.grab(mc.gameDirectory, "pergament_settings.png", mc.getMainRenderTarget(), msg -> { });
                MapScreen.tool = MapScreen.Tool.NONE;
                mc.setScreen(new MapScreen());
            } else if (onMap == 80 && mc.player != null) {
                // зверинец с NoAI (стоят смирно) вокруг игрока — проверить лица мобов и запасной кружок (лошадь)
                PergamentConfig.SHOW_MOBS.set(true);
                var server = mc.getSingleplayerServer();
                var dim = mc.level.dimension();
                double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
                server.execute(() -> {
                    var lvl = server.getLevel(dim);
                    net.minecraft.world.entity.EntityType<?>[] types = {
                            net.minecraft.world.entity.EntityType.ZOMBIE, net.minecraft.world.entity.EntityType.SKELETON,
                            net.minecraft.world.entity.EntityType.CREEPER, net.minecraft.world.entity.EntityType.COW,
                            net.minecraft.world.entity.EntityType.PIG, net.minecraft.world.entity.EntityType.SHEEP,
                            net.minecraft.world.entity.EntityType.CHICKEN, net.minecraft.world.entity.EntityType.VILLAGER,
                            net.minecraft.world.entity.EntityType.SPIDER, net.minecraft.world.entity.EntityType.HORSE};
                    for (int k = 0; k < types.length; k++) {
                        var ent = types[k].create(lvl);
                        if (ent == null) continue;
                        double a = 2 * Math.PI * k / types.length;
                        ent.moveTo(px + Math.cos(a) * 9, py, pz + Math.sin(a) * 9, 0, 0);
                        if (ent instanceof net.minecraft.world.entity.Mob m) m.setNoAi(true);
                        ent.setInvulnerable(true);
                        lvl.addFreshEntity(ent);
                        if (k == 0) NEAR_ID = ent.getId();
                    }
                    var deep = net.minecraft.world.entity.EntityType.ZOMBIE.create(lvl);   // в 40 блоках ниже — не должен попасть
                    deep.moveTo(px + 3, py - 40, pz + 3, 0, 0);
                    deep.setNoAi(true);
                    deep.setInvulnerable(true);
                    deep.setNoGravity(true);
                    lvl.addFreshEntity(deep);
                    DEEP_ID = deep.getId();
                });
            } else if (onMap == 90) {
                MapScreen.setZoom(4);
                mc.setScreen(new MapScreen());
            } else if (onMap == 99) {
                Pergament.LOG.info("SELFTEST entities rendered={} nearDrawn={} deepDrawn={}", mc.level.getEntityCount(),
                        ru.stef.pergament.client.gui.EntityMarks.LAST_DRAWN.contains(NEAR_ID),
                        ru.stef.pergament.client.gui.EntityMarks.LAST_DRAWN.contains(DEEP_ID));
                Screenshot.grab(mc.gameDirectory, "pergament_entities.png", mc.getMainRenderTarget(), msg -> { });
            } else if (onMap == 100 && mc.player != null) {
                // гибель: убить игрока на сервере — метка-череп с причиной в точке смерти
                DEATH_AT = mc.player.blockPosition();
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) sp.hurt(sp.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
                });
            } else if (onMap == 125 && mc.player != null) {
                var m = Deaths.lastForTest();
                var back = ru.stef.pergament.client.map.Markers.load(
                        WorldProfile.dir(LocalMap.get().profile(), LocalMap.get().dimension()).resolve("markers.json"));
                boolean onDisk = m != null && back.byId(m.id) != null && Deaths.KIND.equals(back.byId(m.id).kind);
                Pergament.LOG.info("SELFTEST death screen={} marked={} onDisk={} at={} expected={} name={} cause={}",
                        mc.screen == null ? "-" : mc.screen.getClass().getSimpleName(), m != null, onDisk,
                        m == null ? "-" : m.x + "," + m.y + "," + m.z, DEATH_AT, m == null ? "-" : m.name,
                        m == null ? "-" : m.desc);
                mc.player.respawn();
                mc.setScreen(null);
            } else if (onMap == 145 && mc.player != null) {
                MapScreen.setZoom(2);
                mc.setScreen(new MapScreen());
            } else if (onMap == 155) {
                Screenshot.grab(mc.gameDirectory, "pergament_death.png", mc.getMainRenderTarget(), msg -> { });
            } else if (onMap == 156 && mc.player != null) {
                // метка в мире: в 20 блоках по взгляду, с галочкой «в мире»; кадр из игры без экранов
                var look = mc.player.getLookAngle();
                var wp = new ru.stef.pergament.client.map.Markers.Marker();
                wp.name = "Waypoint";
                wp.icon = "flag";
                wp.world = true;
                wp.x = (int) Math.floor(mc.player.getX() + look.x * 20);
                wp.z = (int) Math.floor(mc.player.getZ() + look.z * 20);
                wp.y = mc.player.getBlockY();
                LocalMap.get().markers().put(wp);
                mc.setScreen(null);
            } else if (onMap == 159) {
                Screenshot.grab(mc.gameDirectory, "pergament_waypoint.png", mc.getMainRenderTarget(), msg -> { });
            } else if (onMap == 160 && mc.player != null) {
                XaeroSelfTest.begin(mc);
            } else if (onMap > 160 && onMap < 1000) {
                if (XaeroSelfTest.tick(mc, onMap)) onMap = 999;
            } else if (onMap >= 1000) {
                if (net.minecraftforge.fml.ModList.get().isLoaded("gtceu")) startGt(mc);
                else afterOverworld(mc);
            }
        } else if (step == Step.GT_WAIT && travelTicks == 0 && mc.player != null) {
            travelTicks++;
            oreWatchProbe(mc, true);
        } else if (step == Step.GT_WAIT && travelTicks == 15) {
            travelTicks++;
            oreWatchProbe(mc, false);
        } else if (step == Step.GT_WAIT && ++travelTicks == 40) {
            var fs = LocalMap.get().live().finds().all();
            for (var f : fs) {
                Pergament.LOG.info("SELFTEST find mat={} count={} y={}..{} byIndicator={} byHand={} at {} {}", f.mat, f.count,
                        f.minY, f.maxY, f.byIndicator, f.byHand, f.x, f.z);
            }
            Pergament.LOG.info("SELFTEST finds total={}", fs.size());
        } else if (step == Step.GT_WAIT && travelTicks >= 80) {
            mc.setScreen(new MapScreen());
            step = Step.GT_MAP;
            onMap = 0;
        } else if (step == Step.GT_MAP && ++onMap == 20) {
            var src = ru.stef.pergament.client.map.Veins.source();
            if (src != null && mc.screen instanceof MapScreen ms && mc.player != null) {
                var vs = src.inArea(mc.level.dimension(), mc.player.getBlockX() - 128, mc.player.getBlockZ() - 128, 256, 256);
                vs.stream().min(java.util.Comparator.comparingDouble(v -> Math.hypot(v.x() - mc.player.getX(), v.z() - mc.player.getZ())))
                        .ifPresent(v -> ms.debugHover(v.x() + 0.5, v.z() + 0.5));
                // шахтёр: точка у игрока, центр выкопан, восточная клетка в работе, мышь на ней
                var plan = LocalMap.get().miner();
                plan.setOrigin(mc.player.getBlockX(), mc.player.getBlockZ());
                plan.cycle(0, 0);
                plan.cycle(0, 0);
                plan.cycle(1, 0);
                MapScreen.tool = MapScreen.Tool.MINER;
                ms.debugHover(plan.ox() + 33 + 0.5, plan.oz() + 0.5);
                var back = ru.stef.pergament.client.map.MinerPlan.load(
                        WorldProfile.dir(LocalMap.get().profile(), LocalMap.get().dimension()).resolve("miner.json"));
                Pergament.LOG.info("SELFTEST miner origin={},{} center={} east={} (с диска)", back.ox(), back.oz(),
                        back.state(0, 0), back.state(1, 0));
            }
        } else if (step == Step.GT_MAP && onMap >= 20 && ++onMap >= 60) {
            var src = ru.stef.pergament.client.map.Veins.source();
            int n = -1;
            if (src != null && mc.player != null) {
                n = src.inArea(mc.level.dimension(), mc.player.getBlockX() - 256, mc.player.getBlockZ() - 256, 512, 512).size();
            }
            Pergament.LOG.info("SELFTEST GT source={} veinsNearPlayer={}", src != null, n);
            Screenshot.grab(mc.gameDirectory, "pergament_gt.png", mc.getMainRenderTarget(), msg -> { });
            afterOverworld(mc);
        } else if (step == Step.TRAVEL && mc.player != null && mc.level != null) {
            travelTicks++;
            boolean arrived = mc.level.dimension().location().getPath().equals(dims()[dimIdx]);
            int scanned = map.scannedTotal();
            stable = arrived && scanned == lastScanned && map.queued() == 0 ? stable + 1 : 0;
            lastScanned = scanned;
            if (arrived && travelTicks > 100 && stable >= 40) {
                mc.setScreen(new MapScreen());
                step = Step.DIM_MAP;
                onMap = 0;
            }
        } else if (step == Step.DIM_MAP) {
            onMap++;
            String d = dims()[dimIdx];
            boolean nether = "the_nether".equals(d);
            if (onMap == 60) {
                Pergament.LOG.info("SELFTEST dim={} scanned={} regions={}", d, map.scannedTotal(), map.loadedRegions());
                Screenshot.grab(mc.gameDirectory, "pergament_" + d + ".png", mc.getMainRenderTarget(), msg -> { });
                if (nether && mc.screen instanceof MapScreen ms) {     // глобус: из Незера — верхний мир, как кнопкой
                    Pergament.LOG.info("SELFTEST globe known={}", map.knownDimensions());
                    ms.debugNextDimension();
                }
            } else if (nether && onMap == 100) {
                Pergament.LOG.info("SELFTEST globe shown={} regions={}", map.shownDimension(), map.loadedRegions());
                Screenshot.grab(mc.gameDirectory, "pergament_globe.png", mc.getMainRenderTarget(), msg -> { });
            } else if (onMap == (nether ? 101 : 61)) {
                mc.setScreen(null);
                if (++dimIdx < dims().length) startTravel(mc);
                else finish(mc, "ok");
            }
        }
    }

    private static void afterOverworld(Minecraft mc) {
        if (dims().length > 0) startTravel(mc);
        else finish(mc, "ok");
    }

    /** GT стоит: «проспектор» по чанкам вокруг — жилы должны прийти в кэш GT и появиться на карте. */
    private static void startGt(Minecraft mc) {
        mc.setScreen(null);
        step = Step.GT_WAIT;
        travelTicks = 0;
        try {
            Class.forName("ru.stef.pergament.client.gt.GtSelfTest").getMethod("prospectAround", Integer.class).invoke(null, 4);
        } catch (Exception e) {
            Pergament.LOG.warn("SELFTEST GT проспектор не запустился: {}", e.toString());
        }
    }

    /** Найти рядом индикатор (или рудный блок), «навестись» и убрать его сервером — как будто игрок собрал/добыл. */
    private static void oreWatchProbe(Minecraft mc, boolean indicator) {
        var ins = ru.stef.pergament.client.map.Veins.inspector();
        if (ins == null) return;
        net.minecraft.core.BlockPos found = null;
        var p = mc.player.blockPosition();
        var mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int r = 0; r <= 48 && found == null; r++) {
            for (int dx = -r; dx <= r && found == null; dx++) {
                for (int dz = -r; dz <= r && found == null; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int y = mc.level.getMaxBuildHeight() - 2; y > mc.level.getMinBuildHeight(); y--) {
                        mp.set(p.getX() + dx, y, p.getZ() + dz);
                        var st = mc.level.getBlockState(mp);
                        if (st.isAir()) continue;
                        if (indicator ? ins.indicator(st) != null : ins.ore(st) != null) {
                            found = mp.immutable();
                            break;
                        }
                    }
                }
            }
        }
        Pergament.LOG.info("SELFTEST orewatch {}={}", indicator ? "indicator" : "ore", found);
        if (found == null || !OreWatch.debugWatch(found)) return;
        var server = mc.getSingleplayerServer();
        var t = found;
        var dim = mc.level.dimension();
        server.execute(() -> server.getLevel(dim).removeBlock(t, false));   // сторож заметит на следующих тиках
    }

    private static String[] dims() {
        String d = System.getProperty("pergament.selftest.dims", "");
        return d.isBlank() ? new String[0] : normalized();
    }

    /** Наблюдателем в следующее измерение: Незер — та же x/z на y 64, Энд — над главным островом. */
    private static void startTravel(Minecraft mc) {
        mc.setScreen(null);
        step = Step.TRAVEL;
        travelTicks = 0;
        stable = 0;
        lastScanned = -1;
        String d = dims()[dimIdx].trim();
        ResourceKey<Level> key = "the_end".equals(d) || "end".equals(d) ? Level.END : Level.NETHER;
        var uuid = mc.player.getUUID();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            ServerLevel target = server.getLevel(key);
            if (sp == null || target == null) return;
            sp.setGameMode(GameType.SPECTATOR);
            if (key == Level.END) sp.teleportTo(target, 0.5, 80, 0.5, 0, 30);
            else sp.teleportTo(target, sp.getX() / 8, 64, sp.getZ() / 8, 0, 30);
        });
        Pergament.LOG.info("SELFTEST лечу в {}", key.location());
    }

    private static String[] normalized() {
        String[] ds = System.getProperty("pergament.selftest.dims", "").split(",");
        for (int i = 0; i < ds.length; i++) {
            String d = ds[i].trim();
            ds[i] = "end".equals(d) || "the_end".equals(d) ? "the_end" : "nether".equals(d) || "the_nether".equals(d) ? "the_nether" : d;
        }
        return ds;
    }

    private static void finish(Minecraft mc, String why) {
        step = Step.DONE;
        LocalMap map = LocalMap.get();
        String profile = map.profile(), dim = map.dimension();
        int scanned = map.scannedTotal(), regions = map.loadedRegions();
        Screenshot.grab(mc.gameDirectory, "pergament_selftest.png", mc.getMainRenderTarget(),
                msg -> Pergament.LOG.info("SELFTEST screenshot: {}", msg.getString()));
        boolean flushed = map.flush(10_000);
        long files = 0;
        if (profile != null) {
            try (var s = Files.list(WorldProfile.dir(profile, dim))) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".pgmt")).count();
            } catch (Exception ex) {
                files = -1;
            }
        }
        int px = mc.player == null ? 0 : mc.player.getBlockX(), pz = mc.player == null ? 0 : mc.player.getBlockZ();
        boolean readBack = false;
        if (profile != null) {
            try {
                RegionData r = RegionFile.read(WorldProfile.dir(profile, dim), RegionData.regionOf(px), RegionData.regionOf(pz));
                int i = ((pz & (RegionData.SIZE - 1)) << RegionData.SHIFT) | (px & (RegionData.SIZE - 1));
                readBack = r != null && r.known[i] == 1;
            } catch (Exception ex) {
                Pergament.LOG.warn("SELFTEST чтение региона: {}", ex.toString());
            }
        }
        Pergament.LOG.info("SELFTEST result={} profile={} dim={} scannedChunks={} regions={} flushed={} files={} "
                        + "underPlayerReadBack={} player={},{} ticks={} avgScanMs={}",
                why, profile, dim, scanned, regions, flushed, files, readBack, px, pz, ticks,
                String.format(java.util.Locale.ROOT, "%.3f", map.avgScanMs()));
        mc.execute(mc::stop);
    }
}
