package ru.stef.pergament.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Файл региона {@code r.<rx>.<rz>.pgmt}: заголовок PGMT + версия формата + размер + координаты,
 * дальше сжатые колонки. Пишется через временный файл и атомарную подмену — обрыв игры
 * не оставит половину файла. Чужая версия формата — честный отказ, а не мусор на карте.
 */
public final class RegionFile {
    static final int MAGIC = 0x50474D54;    // "PGMT"
    static final int VERSION = 2;                // v2: + высота земли; v1 читается (земля = верх)

    private RegionFile() {}

    public static Path path(Path dir, int rx, int rz) {
        return dir.resolve("r." + rx + "." + rz + ".pgmt");
    }

    public static void write(Path dir, RegionData d) throws IOException {
        Files.createDirectories(dir);
        Path f = path(dir, d.rx, d.rz);
        Path tmp = dir.resolve(f.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(os), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(RegionData.SIZE);
            out.writeInt(d.rx);
            out.writeInt(d.rz);
            for (int v : d.color) out.writeInt(v);
            for (short v : d.height) out.writeShort(v);
            for (short v : d.ground) out.writeShort(v);
            out.write(d.water);
            out.write(d.cls);
            out.write(d.known);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Регион с диска или null, если файла нет. */
    public static RegionData read(Path dir, int rx, int rz) throws IOException {
        Path f = path(dir, rx, rz);
        if (!Files.isRegularFile(f)) return null;
        try (InputStream is = Files.newInputStream(f);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(is), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("не файл пергамента: " + f);
            int ver = in.readInt();
            if (ver < 1 || ver > VERSION) throw new IOException("версия формата " + ver + ", умею 1…" + VERSION + ": " + f);
            int size = in.readInt();
            if (size != RegionData.SIZE) throw new IOException("размер региона " + size + ": " + f);
            int frx = in.readInt(), frz = in.readInt();
            if (frx != rx || frz != rz) throw new IOException("в файле регион " + frx + "," + frz + ": " + f);
            int n = size * size;
            RegionData d = new RegionData(rx, rz);
            for (int i = 0; i < n; i++) d.color[i] = in.readInt();
            for (int i = 0; i < n; i++) d.height[i] = in.readShort();
            if (ver >= 2) for (int i = 0; i < n; i++) d.ground[i] = in.readShort();
            else System.arraycopy(d.height, 0, d.ground, 0, n);
            in.readFully(d.water);
            in.readFully(d.cls);
            in.readFully(d.known);
            return d;
        }
    }
}
