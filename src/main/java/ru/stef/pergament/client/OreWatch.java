package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.map.Finds;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Veins;

/**
 * Честное подтверждение жил (только при GTCEu). Следим за блоком под прицелом: если это индикатор
 * или руда и он вскоре исчез, значит игрок его собрал / добыл.
 * <ul>
 *   <li>Индикатор: смотрим ПОД ним (±{@value #REACH} вбок, до {@value #DEPTH} вниз) рудные блоки того же
 *       материала — по правилам TFC индикатор стоит над жилой не глубже 70 блоков. Нашли — жила на карту
 *       с числом блоков и высотами; нет — так и говорим, на карту ничего.</li>
 *   <li>Руда, добытая руками, — сразу находка в этой точке.</li>
 * </ul>
 * Видно только то, что лежит в загруженных клиентом чанках, — то есть то, что под игроком.
 */
public final class OreWatch {
    static final int REACH = 8, DEPTH = 80, FORGET_TICKS = 40;

    private static BlockPos watchPos;
    private static BlockState watchState;
    private static Veins.MatInfo watchMat;
    private static boolean watchIsIndicator;
    private static int watchAge;

    private OreWatch() {}

    /**
     * Проспектор TFG ответил «нашёл руду» (системное сообщение tfg.toast.ore_prospector_message):
     * смотрим ТОТ ЖЕ объём впереди по взгляду — сервер уже подтвердил, что руда там есть, — и раскладываем
     * найденное по материалам. Объём — как у кирки, не больше (полувысоту TFG не задаёт — берём = полуширине).
     */
    public static void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent e) {
        if (!e.isSystem() || !(e.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc)) return;
        if (!"tfg.toast.ore_prospector_message".equals(tc.getKey())) return;
        Minecraft mc = Minecraft.getInstance();
        Veins.Inspector ins = Veins.inspector();
        if (ins == null || mc.player == null || mc.level == null || LocalMap.get().live() == null) return;
        int[] box = null;
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(mc.player.getItemInHand(hand).getItem());
            if (id != null && id.getPath().startsWith("metal/propick/")) {
                box = TfgCompat.propick(id.getPath().substring("metal/propick/".length()));   // из конфига TFG
            }
            if (box != null) break;
        }
        if (box == null) return;
        var found = prospect(mc, ins, box[0], box[1], box[1]);
        Finds finds = LocalMap.get().live().finds();
        String dim = LocalMap.get().live().dimension();
        for (Finds.Find f : found) {
            f.byProspector = true;
            TeamSyncClient.foundLocal(dim, finds, f.copy());          // событием — до слияния в жилу
            finds.addOrMerge(f);
        }
        Pergament.LOG.info("Пергамент: проспектор — {} материал(ов) на карту", found.size());
    }

    /** Руда в ящике впереди по взгляду: длина len, полуширина hw, полувысота hh. По материалам. */
    static java.util.List<Finds.Find> prospect(Minecraft mc, Veins.Inspector ins, int len, int hw, int hh) {
        var p = mc.player;
        var eye = p.getEyePosition();
        var look = p.getLookAngle().normalize();
        var right = look.cross(new net.minecraft.world.phys.Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new net.minecraft.world.phys.Vec3(1, 0, 0) : right.normalize();
        var up = right.cross(look).normalize();
        java.util.Map<String, Finds.Find> by = new java.util.LinkedHashMap<>();
        java.util.Map<String, long[]> sums = new java.util.HashMap<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int t = 0; t <= len; t++) {
            for (int s = -hw; s <= hw; s++) {
                for (int u = -hh; u <= hh; u++) {
                    var q = eye.add(look.scale(t)).add(right.scale(s)).add(up.scale(u));
                    bp.set(q.x, q.y, q.z);
                    if (!seen.add(bp.asLong())) continue;
                    if (!mc.level.hasChunkAt(bp)) continue;
                    Veins.MatInfo m = ins.ore(mc.level.getBlockState(bp));
                    if (m == null) continue;
                    Finds.Find f = by.computeIfAbsent(m.id(), k -> {
                        Finds.Find n = new Finds.Find();
                        n.mat = m.id(); n.name = m.name(); n.rgb = m.rgb();
                        n.minY = Integer.MAX_VALUE; n.maxY = Integer.MIN_VALUE;
                        return n;
                    });
                    long[] sum = sums.computeIfAbsent(m.id(), k -> new long[3]);
                    sum[0] += bp.getX(); sum[1] += bp.getY(); sum[2] += bp.getZ();
                    f.count++;
                    f.minY = Math.min(f.minY, bp.getY());
                    f.maxY = Math.max(f.maxY, bp.getY());
                }
            }
        }
        for (Finds.Find f : by.values()) {
            long[] sum = sums.get(f.mat);
            f.x = (int) Math.round((double) sum[0] / f.count);
            f.y = (int) Math.round((double) sum[1] / f.count);
            f.z = (int) Math.round((double) sum[2] / f.count);
            f.spread = hw;
        }
        return new java.util.ArrayList<>(by.values());
    }

    /** Самотест: «навестись» на блок, как будто он под прицелом. */
    public static boolean debugWatch(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Veins.Inspector ins = Veins.inspector();
        if (ins == null || mc.level == null) return false;
        BlockState s = mc.level.getBlockState(pos);
        Veins.MatInfo ind = ins.indicator(s), ore = ind == null ? ins.ore(s) : null;
        if (ind == null && ore == null) return false;
        watchPos = pos.immutable();
        watchState = s;
        watchMat = ind != null ? ind : ore;
        watchIsIndicator = ind != null;
        watchAge = -1000;                                  // самотест: не забывать, пока сервер убирает блок
        return true;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Veins.Inspector ins = Veins.inspector();
        if (ins == null || mc.level == null || mc.player == null || LocalMap.get().live() == null) return;

        // 1) что было под прицелом и исчезло — собрано/добыто
        if (watchPos != null) {
            watchAge++;
            BlockState now = mc.level.getBlockState(watchPos);
            if (now.getBlock() != watchState.getBlock()) {
                boolean debug = watchAge < 0;                      // самотест «навёлся» издалека
                if (debug || mc.player.blockPosition().distSqr(watchPos) <= 8 * 8) onGone(mc, ins);
                watchPos = null;
            } else if (watchAge > FORGET_TICKS) {
                watchPos = null;
            }
        }
        // 2) под прицелом индикатор или руда — запомнить
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
            BlockState s = mc.level.getBlockState(bh.getBlockPos());
            Veins.MatInfo ind = ins.indicator(s), ore = ind == null ? ins.ore(s) : null;
            if (ind != null || ore != null) {
                watchPos = bh.getBlockPos().immutable();
                watchState = s;
                watchMat = ind != null ? ind : ore;
                watchIsIndicator = ind != null;
                watchAge = 0;
            }
        }
    }

    private static void onGone(Minecraft mc, Veins.Inspector ins) {
        Finds finds = LocalMap.get().live().finds();
        if (watchIsIndicator) {
            Finds.Find f = searchBelow(mc, ins, watchPos, watchMat);
            if (f == null) {
                mc.player.displayClientMessage(T.c("ore.none", watchMat.name()), true);
                Pergament.LOG.info("Пергамент: индикатор {} в {} — руды под ним нет", watchMat.id(), watchPos);
                return;
            }
            f.byIndicator = true;
            TeamSyncClient.foundLocal(LocalMap.get().live().dimension(), finds, f.copy());
            Finds.Find m = finds.addOrMerge(f);
            mc.player.displayClientMessage(T.c("ore.confirmed", watchMat.name(), f.count, f.minY, f.maxY), true);
            Pergament.LOG.info("Пергамент: жила {} подтверждена индикатором: {} блоков, центр {} {} {}", watchMat.id(),
                    f.count, m.x, m.y, m.z);
        } else {
            Finds.Find f = new Finds.Find();
            f.mat = watchMat.id();
            f.name = watchMat.name();
            f.rgb = watchMat.rgb();
            f.x = watchPos.getX(); f.y = watchPos.getY(); f.z = watchPos.getZ();
            f.minY = f.maxY = f.y;
            f.count = 1;
            f.byHand = true;
            TeamSyncClient.foundLocal(LocalMap.get().live().dimension(), finds, f.copy());
            finds.addOrMerge(f);
        }
    }

    /** Руда того же материала под индикатором: сколько блоков, центр, высоты; null — нет ни одного. */
    static Finds.Find searchBelow(Minecraft mc, Veins.Inspector ins, BlockPos at, Veins.MatInfo mat) {
        var level = mc.level;
        int minY = Math.max(level.getMinBuildHeight(), at.getY() - DEPTH);
        long sx = 0, sy = 0, sz = 0;
        int n = 0, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE, maxD = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dz = -REACH; dz <= REACH; dz++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                LevelChunk ch = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
                if (ch == null) continue;
                for (int y = at.getY(); y >= minY; y--) {
                    p.set(x, y, z);
                    Veins.MatInfo m = ins.ore(ch.getBlockState(p));
                    if (m == null || !m.id().equals(mat.id())) continue;
                    sx += x; sy += y; sz += z; n++;
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                    maxD = Math.max(maxD, Math.max(Math.abs(dx), Math.abs(dz)));
                }
            }
        }
        if (n == 0) return null;
        Finds.Find f = new Finds.Find();
        f.mat = mat.id();
        f.name = mat.name();
        f.rgb = mat.rgb();
        f.x = (int) Math.round((double) sx / n);
        f.y = (int) Math.round((double) sy / n);
        f.z = (int) Math.round((double) sz / n);
        f.minY = y0;
        f.maxY = y1;
        f.count = n;
        f.spread = maxD;
        return f;
    }
}
