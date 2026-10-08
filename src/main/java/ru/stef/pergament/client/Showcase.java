package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.gui.MapScreen;
import ru.stef.pergament.client.map.Drawings;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Markers;
import ru.stef.pergament.client.route.RoutePlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Витрина для страницы мода (-Ppergament_showcase, папка run_showcase): облететь округу, разложить метки,
 * тропу, рисунок, мобов — и снять чистые кадры (без подсказок, тостов и чата).
 */
final class Showcase {
    private static final int SPAN_X = 240, SPAN_Z = 200, COLS = 5, ROWS = 3;   // облёт сеткой 5×3 — под широкий экран
    private static int phase, t, point, quiet, cx, cz;
    private static final List<Markers.Marker> marks = new ArrayList<>();
    private static int shot;

    private Showcase() {}

    static boolean enabled() {
        return System.getProperty("pergament.selftest.showcase") != null;
    }

    /** true — витрина снята. */
    static boolean tick(Minecraft mc) {
        t++;
        var server = mc.getSingleplayerServer();
        var map = LocalMap.get();
        switch (phase) {
            case 0 -> {                                       // старт: шире обзор, творческий полёт
                cx = mc.player.getBlockX();
                cz = mc.player.getBlockZ();
                PergamentConfig.REVEAL_FRONT.set(12);
                PergamentConfig.REVEAL_BACK.set(12);
                PergamentConfig.SHOW_MOBS.set(false);             // дикие стада рябят на обзоре — только на крупном
                mc.setScreen(null);
                phase = 1;
                t = 0;
                point = 0;
                fly(mc, 0);
            }
            case 1 -> {                                       // облёт по сетке 3×3, на каждой точке — пока скан не стихнет
                quiet = map.queued() == 0 ? quiet + 1 : 0;
                if ((t > 80 && quiet > 30) || t > 500) {
                    if (++point >= COLS * ROWS) {
                        phase = 2;
                        t = 0;
                        fly(mc, COLS * ROWS / 2);             // в центр
                    } else {
                        fly(mc, point);
                        t = 0;
                        quiet = 0;
                    }
                }
            }
            case 2 -> {
                if (t == 60) setup(mc);
                if (t > 120 && map.queued() == 0) {
                    phase = 3;
                    t = 0;
                }
            }
            case 3 -> {
                return shots(mc);
            }
            default -> { }
        }
        return false;
    }

    private static void fly(Minecraft mc, int p) {
        int x = cx + (p % COLS - COLS / 2) * SPAN_X, z = cz + (p / COLS - ROWS / 2) * SPAN_Z;
        float yaw = (p * 90) % 360;
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            var lvl = sp.serverLevel();
            sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            sp.getAbilities().flying = true;
            sp.onUpdateAbilities();
            lvl.getChunk(x >> 4, z >> 4);
            int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 24;
            sp.teleportTo(lvl, x + 0.5, y, z + 0.5, yaw, 30);
        });
    }

    private static int surface(Minecraft mc, int x, int z) {
        Integer h = LocalMap.get().heightAt(x, z);
        return h == null ? 64 : h;
    }

    /** Разведанная суша рядом (метка «шахта» посреди озера смотрится нелепо). */
    private static int[] landNear(int x, int z) {
        for (int r = 0; r <= 72; r += 3) {
            for (int a = 0; a < 24; a++) {
                int px = x + (int) Math.round(r * Math.cos(a * Math.PI / 12)), pz = z + (int) Math.round(r * Math.sin(a * Math.PI / 12));
                var rd = LocalMap.get().region(px >> 9, pz >> 9, false);
                if (rd == null) continue;
                int i = ((pz & 511) << 9) | (px & 511);
                if (rd.known[i] != 0 && rd.water[i] == 0 && rd.cls[i] == ru.stef.pergament.store.RegionData.CLS_LAND) return new int[]{px, pz};
            }
        }
        return new int[]{x, z};
    }

    private static void mark(Minecraft mc, String name, String desc, String ru, String ruDesc, String icon, int dx, int dz) {
        var m = new Markers.Marker();
        boolean rus = mc.getLanguageManager().getSelected().startsWith("ru");
        m.name = rus ? ru : name;
        m.desc = rus ? ruDesc : desc;
        m.icon = icon;
        int[] at = landNear(cx + dx, cz + dz);
        m.x = at[0];
        m.z = at[1];
        m.y = surface(mc, m.x, m.z);
        LocalMap.get().markers().put(m);
        marks.add(m);
    }

    /** Полдень, ясно, мобы у дома, метки, тропа, рисунок. */
    private static void setup(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        int hx = cx + 6, hz = cz + 4;
        server.execute(() -> {
            var lvl = server.overworld();
            lvl.setDayTime(6000);
            lvl.setWeatherParameters(120000, 0, false, false);
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) + 1;
            sp.teleportTo(lvl, cx + 0.5, y, cz + 0.5, 200, 10);
            sp.getAbilities().flying = false;
            sp.onUpdateAbilities();
            EntityType<?>[] herd = {EntityType.SHEEP, EntityType.SHEEP, EntityType.COW, EntityType.COW, EntityType.PIG,
                    EntityType.HORSE, EntityType.CHICKEN, EntityType.VILLAGER, EntityType.WOLF, EntityType.CAT};
            for (int k = 0; k < herd.length; k++) {
                var e = herd[k].create(lvl);
                if (e == null) continue;
                double a = 2 * Math.PI * k / herd.length;
                int ex = cx + (int) Math.round(Math.cos(a) * (10 + k % 3 * 4)), ez = cz + (int) Math.round(Math.sin(a) * (10 + k % 3 * 4));
                e.moveTo(ex + 0.5, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, ex, ez), ez + 0.5, k * 37, 0);
                if (e instanceof Mob mob) mob.setNoAi(true);
                e.setInvulnerable(true);
                lvl.addFreshEntity(e);
            }
            var zombie = EntityType.ZOMBIE.create(lvl);
            var creeper = EntityType.CREEPER.create(lvl);
            for (var e : new Mob[]{zombie, creeper}) {
                if (e == null) continue;
                int ex = cx + (e == zombie ? 30 : -26), ez = cz + (e == zombie ? -18 : 22);
                e.moveTo(ex + 0.5, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, ex, ez), ez + 0.5, 0, 0);
                e.setNoAi(true);
                e.setInvulnerable(true);
                e.setPersistenceRequired();
                lvl.addFreshEntity(e);
            }
        });
        mark(mc, "Home", "first base", "Дом", "первая база", "house", 6, 4);
        mark(mc, "Iron mine", "y 12–40", "Железная шахта", "y 12–40", "mine", 118, -64);
        mark(mc, "Lighthouse", "the tall one on the shore", "Маяк", "высокий, на берегу", "lighthouse", -150, 96);
        mark(mc, "Cave", "goes deep", "Пещера", "уходит глубоко", "cave", 92, 138);
        mark(mc, "Nether portal", "", "Портал в Незер", "", "portal", -86, -128);
        mark(mc, "Fishing", "", "Рыбалка", "", "fish", -40, 150);
        mark(mc, "Spiders!", "careful at night", "Пауки!", "ночью осторожно", "spider", 170, 40);
        mark(mc, "Farm", "wheat and carrots", "Ферма", "пшеница и морковь", "farm", 34, 58);
        mark(mc, "Camp", "", "Лагерь", "", "tent", -170, -40);
        mark(mc, "Gold?", "check later", "Золото?", "проверить", "question", 140, -150);
        var home = marks.get(0);
        var mine = marks.get(1);
        var light = marks.get(2);
        var rp = RoutePlan.get();                              // тропа: дом → маяк
        rp.clear();
        rp.add(home.x, home.z);
        rp.add(light.x, light.z);
        var st = new Drawings.Stroke();                        // пером — круг у шахты
        st.color = Drawings.COLORS[1];
        for (int k = 0; k <= 32; k++) {
            double a = 2 * Math.PI * k / 32;
            st.pts.add(new int[]{mine.x + (int) Math.round(Math.cos(a) * 26), mine.z + (int) Math.round(Math.sin(a) * 18)});
        }
        LocalMap.get().drawings().add(st);
        Pergament.LOG.info("SHOWCASE разложено: меток {}, тропа {}", marks.size(), rp.status());
    }

    /** Кадры: настроить → подождать перекраску → снять. */
    private static boolean shots(Minecraft mc) {
        int k = t / 60, sub = t % 60;
        if (sub >= 30 && sub < 55) {                                // тосты и чат — гасить заранее: кадр в буфере уже нарисован
            mc.getToasts().clear();
            mc.gui.getChat().clearMessages(false);
        }
        if (sub != 1 && sub != 55) return false;
        boolean take = sub == 55;
        if (take) {
            cursorAside(mc);
            Screenshot.grab(mc.gameDirectory, String.format("showcase_%02d.png", k + 1), mc.getMainRenderTarget(), msg -> { });
            Pergament.LOG.info("SHOWCASE кадр {}", k + 1);
            return k + 1 >= 9;
        }
        mc.getToasts().clear();
        cursorAside(mc);
        PergamentConfig.SHOW_MOBS.set(k == 2 || k == 8);
        MapScreen.tool = MapScreen.Tool.NONE;
        MapScreen.listOpenForTest(false);
        MapScreen.debugPicker(false);
        MapScreen.debugRuler();
        switch (k) {
            case 0 -> open(mc, 0.9f, cx, cz);                                   // обзор
            case 1 -> {                                                         // тропа: с краем пергамента слева
                MapScreen.tool = MapScreen.Tool.ROUTE;
                open(mc, 1.1f, (marks.get(0).x + marks.get(2).x) / 2.0 - 200, (marks.get(0).z + marks.get(2).z) / 2.0);
            }
            case 2 -> open(mc, 6f, marks.get(0).x, marks.get(0).z);             // крупно: головы мобов, рельеф
            case 3 -> {                                                         // метка в панели
                var ms = open(mc, 1.5f, marks.get(2).x, marks.get(2).z);
                ms.debugOpenMarker(marks.get(2).copy());
            }
            case 4 -> {                                                         // окно значков
                var ms = open(mc, 1.5f, marks.get(2).x, marks.get(2).z);
                ms.debugOpenMarker(marks.get(2).copy());
                MapScreen.debugPicker(true);
            }
            case 5 -> {                                                         // список и линейка
                MapScreen.tool = MapScreen.Tool.RULER;
                var a = marks.get(0);
                var b = marks.get(1);
                MapScreen.debugRuler(new int[]{a.x, a.z}, new int[]{(a.x + b.x) / 2 + 20, (a.z + b.z) / 2 - 25}, new int[]{b.x, b.z});
                MapScreen.listOpenForTest(true);
                open(mc, 0.9f, cx + 60, cz);
            }
            case 6 -> {                                                         // рисунок пером у шахты
                MapScreen.tool = MapScreen.Tool.PEN;
                open(mc, 2.5f, marks.get(1).x, marks.get(1).z);
            }
            case 7 -> {                                                         // настройки — как по умолчанию
                PergamentConfig.REVEAL_FRONT.set(8);
                PergamentConfig.REVEAL_BACK.set(4);
                mc.setScreen(new ru.stef.pergament.client.gui.SettingsScreen(new MapScreen()));
            }
            case 8 -> {                                                         // игра: в стороне от стада, лицом к нему
                mc.setScreen(null);
                PergamentConfig.MINIMAP_SIZE.set(150);
                var home = marks.get(0);
                int[] at = landNear(home.x + 14, home.z + 14);          // на суше, лицом к дому и стаду
                float yaw = (float) Math.toDegrees(Math.atan2(-(home.x - at[0]), home.z - at[1]));
                standAndLook(mc, at[0], at[1], yaw, 12);
            }
            default -> { }
        }
        return false;
    }

    /** Курсор — к правому краю: иначе под ним всплывает подсказка метки. */
    private static void cursorAside(Minecraft mc) {
        var w = mc.getWindow();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(w.getWindow(), w.getScreenWidth() - 4, w.getScreenHeight() / 2.0);
    }

    private static void standAndLook(Minecraft mc, int x, int z, float yaw, float pitch) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            var lvl = sp.serverLevel();
            int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            sp.teleportTo(lvl, x + 0.5, y, z + 0.5, yaw, pitch);
        });
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }

    private static MapScreen open(Minecraft mc, float zoom, double x, double z) {
        MapScreen.setZoom(zoom);
        MapScreen ms = new MapScreen();
        mc.setScreen(ms);
        MapScreen.debugCenter(x, z);
        ms.debugMouse(-1000, -1000);                          // «мышь» за краем — без подсказок
        return ms;
    }
}
