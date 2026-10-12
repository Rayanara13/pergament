package ru.stef.pergament.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.server.TeamServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Канал командной части {@code pergament:team}. Необязателен с обеих сторон: сервер без мода, клиент без мода —
 * всё работает как раньше, просто без обмена. Всё, что приходит, проверяется на размеры при чтении.
 */
public final class TeamNet {
    public static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Pergament.MOD_ID, "team"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));
    static final int MAX_MATES = 64, MAX_NAME = 64, MAX_DIM = 128;
    /** Чанков в пакете клиента и сервера; сжатый чанк — не больше MAX_Z. Итоговый размер пакета держит отправитель. */
    public static final int MAX_UP = 48, MAX_PUSH = 256, MAX_Z = 8192, MAX_FINDS = 32, MAX_FINDS_PUSH = 256;
    /** Сколько байт сжатых чанков класть в пакет: клиент → сервер (лимит игры 32767), сервер → клиент. */
    public static final int UP_BYTES = 22 * 1024, PUSH_BYTES = 160 * 1024;

    private TeamNet() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Hello.class, Hello::write, Hello::read, Hello::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, Mates.class, Mates::write, Mates::read, Mates::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, TeamInfo.class, TeamInfo::write, TeamInfo::read, TeamInfo::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncFrom.class, SyncFrom::write, SyncFrom::read, SyncFrom::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, Upload.class, Upload::write, Upload::read, Upload::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, Push.class, Push::write, Push::read, Push::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, Mark.class, Mark::write, Mark::read, Mark::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, FindUp.class, FindUp::write, FindUp::read, FindUp::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, FindPush.class, FindPush::write, FindPush::read, FindPush::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // протокол 2 — имена местности. Только в конец: номера прежних пакетов не сдвигаются. Сервер шлёт их лишь
        // клиентам с протоколом ≥ 2 (Hello), клиент — лишь получив TopoReady (старый сервер его не шлёт).
        CHANNEL.registerMessage(id++, TopoReady.class, TopoReady::write, TopoReady::read, TopoReady::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, TopoUp.class, TopoUp::write, TopoUp::read, TopoUp::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, TopoPush.class, TopoPush::write, TopoPush::read, TopoPush::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Протокол клиента, с которого он понимает имена местности. */
    public static final int TOPO_PROTOCOL = 2;
    public static final int MAX_TOPO_UP = 64, MAX_TOPO_PUSH = 256, MAX_TOPO_REJECT = 64;
    private static final java.util.Set<String> TOPO_TYPES = java.util.Set.of("lake", "river", "sea", "range", "volcano",
            "forest", "desert", "swamp", "island", "peak");

    /**
     * Имя местности команды. id — UUID записи; anchor — ключ чанка; nameId — «тип:номер[:повтор]»; custom — своё имя
     * ("" — выданное). teamSeq (кто раньше), rev (ревизия) и author — назначает сервер; от клиента не читаются.
     */
    public record TopoRec(String id, String type, long anchor, String nameId, String custom, long teamSeq, long rev, UUID author) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(id, 40);
            b.writeUtf(type, 12);
            b.writeLong(anchor);
            b.writeUtf(nameId, 24);
            b.writeUtf(custom, 40);
            b.writeVarLong(teamSeq);
            b.writeVarLong(rev);
            b.writeBoolean(author != null);
            if (author != null) b.writeUUID(author);
        }

        static TopoRec read(FriendlyByteBuf b) {
            String id = b.readUtf(40), type = b.readUtf(12);
            long anchor = b.readLong();
            String nameId = b.readUtf(24), custom = b.readUtf(40);
            long seq = b.readVarLong(), rev = b.readVarLong();
            UUID author = b.readBoolean() ? b.readUUID() : null;
            return new TopoRec(id, type, anchor, nameId, custom, seq, rev, author);
        }

        /** Правдоподобна ли запись (сервер проверяет всё, что прислал клиент). */
        public boolean sane() {
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                return false;
            }
            int cx = (int) anchor, cz = (int) (anchor >>> 32);
            return TOPO_TYPES.contains(type) && nameId.matches("[a-z]{1,10}:\\d{1,5}(:\\d{1,4})?")
                    && custom.length() <= 40 && custom.chars().noneMatch(Character::isISOControl)
                    && Math.abs(cx) <= 1_875_000 && Math.abs(cz) <= 1_875_000;
        }
    }

    private static void writeTopos(FriendlyByteBuf b, List<TopoRec> l) {
        b.writeVarInt(l.size());
        for (TopoRec r : l) r.write(b);
    }

    private static List<TopoRec> readTopos(FriendlyByteBuf b, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("имён в пакете " + n);
        List<TopoRec> l = new ArrayList<>(n);
        for (int k = 0; k < n; k++) l.add(TopoRec.read(b));
        return l;
    }

    /** Сервер → клиент: «я храню имена команды» (только протоколу ≥ 2, после TeamInfo). */
    public record TopoReady(UUID team) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
        }

        static TopoReady read(FriendlyByteBuf b) {
            return new TopoReady(b.readUUID());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onTopoReady(team)));
            c.setPacketHandled(true);
        }
    }

    /** Клиент → сервер: новые имена и переименования (req — номер запроса, сервер отвечает тем же номером). */
    public record TopoUp(int req, String dim, List<TopoRec> recs) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(req);
            b.writeUtf(dim, MAX_DIM);
            writeTopos(b, recs);
        }

        static TopoUp read(FriendlyByteBuf b) {
            return new TopoUp(b.readVarInt(), b.readUtf(MAX_DIM), readTopos(b, MAX_TOPO_UP));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            var sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) ru.stef.pergament.server.TeamSync.get().topoUp(sender, this);
            });
            c.setPacketHandled(true);
        }
    }

    /**
     * Сервер → клиент: имена команды (докачка, свежее от товарища или ответ на свой запрос req; -1 — не ответ).
     * rejected — id записей запроса, которые сервер не принял (квота, проверка): их больше не слать.
     */
    public record TopoPush(UUID team, String dim, int req, List<TopoRec> recs, List<String> rejected) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeUtf(dim, MAX_DIM);
            b.writeVarInt(req + 1);
            writeTopos(b, recs);
            b.writeVarInt(rejected.size());
            for (String id : rejected) b.writeUtf(id, 40);
        }

        static TopoPush read(FriendlyByteBuf b) {
            UUID team = b.readUUID();
            String dim = b.readUtf(MAX_DIM);
            int req = b.readVarInt() - 1;
            List<TopoRec> recs = readTopos(b, MAX_TOPO_PUSH);
            int n = b.readVarInt();
            if (n < 0 || n > MAX_TOPO_REJECT) throw new IllegalArgumentException("отказов в пакете " + n);
            List<String> rej = new ArrayList<>(n);
            for (int k = 0; k < n; k++) rej.add(b.readUtf(40));
            return new TopoPush(team, dim, req, recs, rej);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onTopoPush(this)));
            c.setPacketHandled(true);
        }
    }

    /** Клиент → сервер: «у меня есть Пергамент». */
    public record Hello(int protocol) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(protocol);
        }

        static Hello read(FriendlyByteBuf b) {
            return new Hello(b.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            var sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) TeamServer.get().hello(sender, protocol);
            });
            c.setPacketHandled(true);
        }
    }

    /** Онлайн-сокомандник: где он сейчас. */
    public record Mate(UUID id, String name, String dim, int x, int y, int z, float yaw) {}

    /** Сервер → клиент: все онлайн-сокомандники по party (с модом или без). Пустой список — команды нет. */
    public record Mates(List<Mate> mates) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(Math.min(mates.size(), MAX_MATES));
            for (int k = 0; k < Math.min(mates.size(), MAX_MATES); k++) {
                Mate m = mates.get(k);
                b.writeUUID(m.id());
                b.writeUtf(m.name(), MAX_NAME);
                b.writeUtf(m.dim(), MAX_DIM);
                b.writeInt(m.x());
                b.writeInt(m.y());
                b.writeInt(m.z());
                b.writeFloat(m.yaw());
            }
        }

        static Mates read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            if (n < 0 || n > MAX_MATES) throw new IllegalArgumentException("сокомандников " + n);
            List<Mate> l = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                l.add(new Mate(b.readUUID(), b.readUtf(MAX_NAME), b.readUtf(MAX_DIM), b.readInt(), b.readInt(), b.readInt(), b.readFloat()));
            }
            return new Mates(l);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamClient.onMates(mates)));
            c.setPacketHandled(true);
        }
    }

    /** Чанк в пакете: координаты и сжатая запись {@link ru.stef.pergament.store.ChunkCodec}. */
    public record Rec(int cx, int cz, byte[] z) {}

    private static void writeRecs(FriendlyByteBuf b, List<Rec> recs) {
        b.writeVarInt(recs.size());
        for (Rec r : recs) {
            b.writeInt(r.cx());
            b.writeInt(r.cz());
            b.writeByteArray(r.z());
        }
    }

    private static List<Rec> readRecs(FriendlyByteBuf b, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("чанков в пакете " + n);
        List<Rec> l = new ArrayList<>(n);
        for (int k = 0; k < n; k++) l.add(new Rec(b.readInt(), b.readInt(), b.readByteArray(MAX_Z)));
        return l;
    }

    /** Сервер → клиент: твоя party (null — не в команде). Клиент отвечает {@link SyncFrom}. */
    public record TeamInfo(UUID team) {
        void write(FriendlyByteBuf b) {
            b.writeBoolean(team != null);
            if (team != null) b.writeUUID(team);
        }

        static TeamInfo read(FriendlyByteBuf b) {
            return new TeamInfo(b.readBoolean() ? b.readUUID() : null);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onTeam(team)));
            c.setPacketHandled(true);
        }
    }

    /** Клиент → сервер: «у меня всё до watermark этого воплощения карты команды, дошли остальное». */
    public record SyncFrom(UUID team, UUID incarnation, long watermark) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeBoolean(incarnation != null);
            if (incarnation != null) b.writeUUID(incarnation);
            b.writeLong(watermark);
        }

        static SyncFrom read(FriendlyByteBuf b) {
            return new SyncFrom(b.readUUID(), b.readBoolean() ? b.readUUID() : null, b.readLong());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            var sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) ru.stef.pergament.server.TeamSync.get().syncFrom(sender, team, incarnation, watermark);
            });
            c.setPacketHandled(true);
        }
    }

    /** Клиент → сервер: разведанные чанки. live — свежий скан, иначе архив (только в пустое). id — номер для подтверждения. */
    public record Upload(int id, String dim, boolean live, List<Rec> recs) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeUtf(dim, MAX_DIM);
            b.writeBoolean(live);
            writeRecs(b, recs);
        }

        static Upload read(FriendlyByteBuf b) {
            return new Upload(b.readVarInt(), b.readUtf(MAX_DIM), b.readBoolean(), readRecs(b, MAX_UP));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            var sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) ru.stef.pergament.server.TeamSync.get().upload(sender, this);
            });
            c.setPacketHandled(true);
        }
    }

    /** Сервер → клиент: чанки команды. catchup — докачка при входе, иначе свежая пересылка. */
    public record Push(UUID team, String dim, boolean live, boolean catchup, List<Rec> recs) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeUtf(dim, MAX_DIM);
            b.writeBoolean(live);
            b.writeBoolean(catchup);
            writeRecs(b, recs);
        }

        static Push read(FriendlyByteBuf b) {
            return new Push(b.readUUID(), b.readUtf(MAX_DIM), b.readBoolean(), b.readBoolean(), readRecs(b, MAX_PUSH));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onPush(this)));
            c.setPacketHandled(true);
        }
    }

    /**
     * Сервер → клиент, только после записи карты команды на диск: всё до seq у тебя есть (seq &lt; 0 — докачка ещё идёт),
     * выгрузки до ack включительно легли на диск (ack &lt; 0 — нечего подтвердить). incarnation сменилось — сервер
     * потерял карту: начинай с нуля и отдай архив заново.
     */
    public record Mark(UUID team, UUID incarnation, long seq, int ack) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeUUID(incarnation);
            b.writeLong(seq);
            b.writeVarInt(ack + 1);
        }

        static Mark read(FriendlyByteBuf b) {
            return new Mark(b.readUUID(), b.readUUID(), b.readLong(), b.readVarInt() - 1);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onMark(this)));
            c.setPacketHandled(true);
        }
    }

    /**
     * Событие находки руды (не жила целиком): id события, материал, где и сколько. flags: 1 — индикатор,
     * 2 — руками, 4 — проспектор. Получатель вливает событие в свои жилы ровно один раз (по id).
     */
    public record FindRec(String eid, String mat, String name, int rgb, int x, int y, int z, int minY, int maxY,
                          int count, int spread, int flags, long time) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(eid, 40);
            b.writeUtf(mat, 64);
            b.writeUtf(name, 64);
            b.writeInt(rgb);
            b.writeInt(x);
            b.writeInt(y);
            b.writeInt(z);
            b.writeInt(minY);
            b.writeInt(maxY);
            b.writeVarInt(count);
            b.writeVarInt(spread);
            b.writeByte(flags);
            b.writeLong(time);
        }

        static FindRec read(FriendlyByteBuf b) {
            return new FindRec(b.readUtf(40), b.readUtf(64), b.readUtf(64), b.readInt(), b.readInt(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readVarInt(), b.readVarInt(), b.readByte(), b.readLong());
        }

        /** Правдоподобна ли находка (сервер проверяет всё, что прислал клиент). */
        public boolean sane() {
            return !eid.isEmpty() && mat.indexOf(':') > 0 && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000
                    && y >= -2048 && y <= 4096 && minY <= maxY && minY >= -2048 && maxY <= 4096
                    && count >= 1 && count <= 100_000 && spread >= 0 && spread <= 1024;
        }
    }

    private static void writeFinds(FriendlyByteBuf b, List<FindRec> l) {
        b.writeVarInt(l.size());
        for (FindRec f : l) f.write(b);
    }

    private static List<FindRec> readFinds(FriendlyByteBuf b, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("находок в пакете " + n);
        List<FindRec> l = new ArrayList<>(n);
        for (int k = 0; k < n; k++) l.add(FindRec.read(b));
        return l;
    }

    /** Клиент → сервер: события находок (id — номер выгрузки для подтверждения, общий с картой). */
    public record FindUp(int id, String dim, List<FindRec> finds) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeUtf(dim, MAX_DIM);
            writeFinds(b, finds);
        }

        static FindUp read(FriendlyByteBuf b) {
            return new FindUp(b.readVarInt(), b.readUtf(MAX_DIM), readFinds(b, MAX_FINDS));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            var sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) ru.stef.pergament.server.TeamSync.get().findUp(sender, this);
            });
            c.setPacketHandled(true);
        }
    }

    /** Сервер → клиент: события находок команды (свежие или докачкой). */
    public record FindPush(UUID team, String dim, List<FindRec> finds) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeUtf(dim, MAX_DIM);
            writeFinds(b, finds);
        }

        static FindPush read(FriendlyByteBuf b) {
            return new FindPush(b.readUUID(), b.readUtf(MAX_DIM), readFinds(b, MAX_FINDS_PUSH));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ru.stef.pergament.client.TeamSyncClient.onFindPush(this)));
            c.setPacketHandled(true);
        }
    }
}
