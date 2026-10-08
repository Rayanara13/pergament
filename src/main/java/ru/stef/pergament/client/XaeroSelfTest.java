package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.gui.MapScreen;
import ru.stef.pergament.client.gui.XaeroImportScreen;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.xaero.XaeroImport;
import ru.stef.pergament.store.ChunkCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Самотест импорта Xaero: пишет мир Xaero формата 6.8 (как MapSaveLoad) рядом с игроком — трава, каменная гряда,
 * озеро ванильной водой поверх песка, — плюс чанк прямо под игроком (камень на 200: своё не должно перетереться).
 * Импортирует через окно, проверяет счётчики и что свой чанк цел, снимает окно и карту.
 */
final class XaeroSelfTest {
    private static int[] at;
    private static int shotAt;
    private static byte[] homeBefore;

    private XaeroSelfTest() {}

    static void begin(Minecraft mc) {
        at = new int[]{mc.player.getBlockX() + 700, mc.player.getBlockZ() + 100};
        homeBefore = home(mc);
        try {
            write(mc, at[0], at[1], mc.player.getBlockX(), mc.player.getBlockZ());
        } catch (Exception e) {
            Pergament.LOG.warn("SELFTEST xaero write failed: {}", e.toString());
        }
        var worlds = XaeroImport.worlds();
        var mine = worlds.stream().filter(XaeroImport.World::matchesCurrent).findFirst();
        Pergament.LOG.info("SELFTEST xaero worlds={} matched={}", worlds.size(), mine.map(XaeroImport.World::name).orElse("-"));
        mine.ifPresent(w -> XaeroImport.start(w.sources()));
        mc.setScreen(new XaeroImportScreen(new MapScreen()));
    }

    /** true — закончили. */
    static boolean tick(Minecraft mc, int onMap) {
        XaeroImport job = XaeroImport.current();
        if (shotAt == 0) {
            if (job == null || !job.done) return onMap > 600;
            Pergament.LOG.info("SELFTEST xaero status={} regions={}/{} added={} kept={} errors={} homeKept={}",
                    job.status, job.merged, job.total, job.chunksAdded, job.chunksKept, job.errors,
                    Arrays.equals(homeBefore, home(mc)));
            Screenshot.grab(mc.gameDirectory, "pergament_xaero_screen.png", mc.getMainRenderTarget(), msg -> { });
            MapScreen.setZoom(2);
            mc.setScreen(new MapScreen());
            MapScreen.debugCenter(at[0] + 48, at[1] + 48);
            shotAt = onMap;
            return false;
        }
        if (onMap == shotAt + 40) {
            Screenshot.grab(mc.gameDirectory, "pergament_xaero.png", mc.getMainRenderTarget(), msg -> { });
            return true;
        }
        return false;
    }

    private static byte[] home(Minecraft mc) {
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        var r = LocalMap.get().live().inMemory(cx >> 5, cz >> 5);
        return r == null ? null : ChunkCodec.extract(r, cx, cz);
    }

    private static void write(Minecraft mc, int x0, int z0, int px, int pz) throws Exception {
        String folder = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                .getFileName().toString();
        Path dir = mc.gameDirectory.toPath().resolve("xaero").resolve("world-map").resolve(folder)
                .resolve("null").resolve("mw$default");
        Files.createDirectories(dir);
        Map<Long, Set<Long>> byRegion = new HashMap<>();
        for (int dx = 0; dx < 6; dx++) {
            for (int dz = 0; dz < 6; dz++) add(byRegion, (x0 >> 4) + dx, (z0 >> 4) + dz);
        }
        int hx = px >> 4, hz = pz >> 4;
        add(byRegion, hx, hz);
        for (var e : byRegion.entrySet()) {
            int rx = ChunkPos.getX(e.getKey()), rz = ChunkPos.getZ(e.getKey());
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(raw);
            Map<String, Integer> pal = new HashMap<>();
            boolean biomeSeen = false;
            o.write(255);
            o.writeInt(6 << 16 | 8);
            for (int tx = 0; tx < 8; tx++) {
                for (int tz = 0; tz < 8; tz++) {
                    boolean any = false;
                    for (int a = 0; a < 4; a++) {
                        for (int b = 0; b < 4; b++) {
                            any |= e.getValue().contains(ChunkPos.asLong((rx << 5) + tx * 4 + a, (rz << 5) + tz * 4 + b));
                        }
                    }
                    if (!any) continue;
                    o.write(tx << 4 | tz);
                    for (int a = 0; a < 4; a++) {
                        for (int b = 0; b < 4; b++) {
                            int cx = (rx << 5) + tx * 4 + a, cz = (rz << 5) + tz * 4 + b;
                            if (!e.getValue().contains(ChunkPos.asLong(cx, cz))) {
                                o.writeInt(-1);
                                continue;
                            }
                            boolean home = cx == hx && cz == hz;
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    int wx = (cx << 4) + x, wz = (cz << 4) + z;
                                    String block = null;                       // null — трава Xaero, без состояния
                                    int h = 70;
                                    boolean water = false;
                                    if (home) {
                                        block = "minecraft:stone";
                                        h = 200;
                                    } else if (Math.hypot(wx - (x0 + 60), wz - (z0 + 50)) < 22) {
                                        block = "minecraft:sand";
                                        h = 58;
                                        water = true;
                                    } else if (Math.abs((wx - x0) - (wz - z0) * 0.5 - 20) < 4) {
                                        block = "minecraft:stone";
                                        h = 80;
                                    }
                                    Integer idx = block == null ? null : pal.get(block);
                                    int p = (block == null ? 0 : 1) | (water ? 2 : 0) | (h & 255) << 12 | 1 << 20
                                            | (block != null && idx == null ? 1 << 21 : 0) | (biomeSeen ? 0 : 1 << 22)
                                            | (water ? 1 << 24 : 0) | ((h >> 8) & 15) << 25;
                                    o.writeInt(p);
                                    if (block != null) {
                                        if (idx == null) {
                                            o.writeByte(10);
                                            o.writeUTF("");
                                            o.writeByte(8);
                                            o.writeUTF("Name");
                                            o.writeUTF(block);
                                            o.writeByte(0);
                                            pal.put(block, pal.size());
                                        } else {
                                            o.writeInt(idx);
                                        }
                                    }
                                    if (water) {
                                        o.write(63);                           // верх воды
                                        o.write(1);
                                        o.writeInt(0);                         // оверлей: ванильная вода
                                    }
                                    if (biomeSeen) {
                                        o.writeInt(0);
                                    } else {
                                        o.writeUTF("minecraft:plains");
                                        biomeSeen = true;
                                    }
                                }
                            }
                            o.write(2);
                            o.writeInt(0);
                            o.write(1);
                        }
                    }
                }
            }
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dir.resolve(rx + "_" + rz + ".zip")))) {
                zos.putNextEntry(new ZipEntry("region.xaero"));
                zos.write(raw.toByteArray());
                zos.closeEntry();
            }
        }
    }

    private static void add(Map<Long, Set<Long>> m, int cx, int cz) {
        m.computeIfAbsent(ChunkPos.asLong(cx >> 5, cz >> 5), k -> new HashSet<>()).add(ChunkPos.asLong(cx, cz));
    }
}
