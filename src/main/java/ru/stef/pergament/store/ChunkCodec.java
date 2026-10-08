package ru.stef.pergament.store;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Один чанк карты (16×16 колонок) как запись обмена: сырьё фиксированной длины и его сжатие.
 * Запись всегда покрывает чанк целиком — применение заменяет все 256 колонок, частичных записей нет.
 * Распаковка с жёстким потолком: пришедшее по сети не может развернуться больше, чем один чанк.
 */
public final class ChunkCodec {
    public static final int COLS = 256;
    /** color 4 + height 2 + ground 2 + water 1 + cls 1 + known 1 байт на колонку. */
    public static final int RAW = COLS * 11;

    private ChunkCodec() {}

    private static int base(RegionData r, int cx, int cz) {
        if (RegionData.regionOf(cx << 4) != r.rx || RegionData.regionOf(cz << 4) != r.rz) {
            throw new IllegalArgumentException("чанк " + cx + "," + cz + " не в регионе " + r.rx + "," + r.rz);
        }
        return (((cz & 31) << 4) << RegionData.SHIFT) | ((cx & 31) << 4);
    }

    /** Есть ли в чанке хоть одна разведанная колонка. */
    public static boolean known(RegionData r, int cx, int cz) {
        int b = base(r, cx, cz);
        for (int z = 0; z < 16; z++) {
            int row = b + (z << RegionData.SHIFT);
            for (int x = 0; x < 16; x++) if (r.known[row + x] != 0) return true;
        }
        return false;
    }

    public static byte[] extract(RegionData r, int cx, int cz) {
        int b = base(r, cx, cz);
        byte[] out = new byte[RAW];
        int o = 0;
        for (int z = 0; z < 16; z++) {
            int row = b + (z << RegionData.SHIFT);
            for (int x = 0; x < 16; x++) {
                int i = row + x, c = r.color[i];
                out[o++] = (byte) (c >>> 24); out[o++] = (byte) (c >>> 16); out[o++] = (byte) (c >>> 8); out[o++] = (byte) c;
                out[o++] = (byte) (r.height[i] >>> 8); out[o++] = (byte) r.height[i];
                out[o++] = (byte) (r.ground[i] >>> 8); out[o++] = (byte) r.ground[i];
                out[o++] = r.water[i];
                out[o++] = r.cls[i];
                out[o++] = r.known[i];
            }
        }
        return out;
    }

    /** Заменить все 256 колонок чанка записью; регион помечается изменённым. */
    public static void apply(RegionData r, int cx, int cz, byte[] raw) {
        if (raw.length != RAW) throw new IllegalArgumentException("запись чанка " + raw.length + " байт, ждали " + RAW);
        int b = base(r, cx, cz), o = 0;
        for (int z = 0; z < 16; z++) {
            int row = b + (z << RegionData.SHIFT);
            for (int x = 0; x < 16; x++) {
                int i = row + x;
                r.color[i] = (raw[o] & 0xFF) << 24 | (raw[o + 1] & 0xFF) << 16 | (raw[o + 2] & 0xFF) << 8 | raw[o + 3] & 0xFF;
                r.height[i] = (short) ((raw[o + 4] & 0xFF) << 8 | raw[o + 5] & 0xFF);
                r.ground[i] = (short) ((raw[o + 6] & 0xFF) << 8 | raw[o + 7] & 0xFF);
                r.water[i] = raw[o + 8];
                r.cls[i] = raw[o + 9];
                r.known[i] = raw[o + 10] != 0 ? (byte) 1 : 0;
                o += 11;
            }
        }
        r.touch();
    }

    public static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            byte[] buf = new byte[4096];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** Распаковать ровно RAW байт; больше, меньше или мусор — исключение (запись отбрасывается). */
    public static byte[] inflate(byte[] z) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            byte[] out = new byte[RAW];
            int n = 0;
            while (n < RAW) {
                int k = inf.inflate(out, n, RAW - n);
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                n += k;
            }
            if (n != RAW) throw new IllegalArgumentException("запись чанка: " + n + " байт вместо " + RAW);
            if (!inf.finished() && inf.inflate(new byte[1]) > 0) throw new IllegalArgumentException("запись чанка длиннее " + RAW);
            if (!inf.finished()) throw new IllegalArgumentException("запись чанка оборвана");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("запись чанка не распаковалась: " + e.getMessage());
        } finally {
            inf.end();
        }
    }
}
