package ru.stef.pergament.client.xaero;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Регион карты Xaero's World Map ({@code <rx>_<rz>.zip} → {@code region.xaero}) — только чтение, без классов игры.
 * Регион 512×512 блоков, как наш. Внутри: 8×8 «плиток» по 64 блока, в каждой 4×4 чанка, в чанке 16×16 пикселей.
 * Пиксель — состояние блока (палитра или NBT), высота, свет, биом, «оверлеи» (вода и прозрачное над дном).
 * Понимаем формат 6.x (Xaero's World Map 1.3x–1.4x); другой — честный отказ.
 */
public final class XaeroRegion {
    public static final int SIZE = 512;
    /** Состояние «травы» (Xaero хранит его без NBT). */
    public static final int GRASS = -1;
    public static final int NONE = -2;
    public static final int WATER = -3;
    private static final long MAX_UNZIPPED = 64L << 20;        // регион.xaero обычно 1–3 МБ
    private static final int MAX_PALETTE = 1 << 16;

    /** Состояние блока из палитры: имя и свойства (как в NBT игры). */
    public record BlockNbt(String name, Map<String, String> props) {}

    public final int rx, rz;
    /** Индекс в {@link #palette}, {@link #GRASS} или {@link #NONE} (пиксель не сохранён). */
    public final int[] state = new int[SIZE * SIZE];
    public final short[] height = new short[SIZE * SIZE];
    /** Верх оверлея (вода, лёд), иначе = height. */
    public final short[] top = new short[SIZE * SIZE];
    /**
     * Первый оверлей над дном: индекс в палитре, {@link #WATER} (ванильная вода, Xaero пишет её без состояния)
     * или {@link #NONE}. Вода модов (TFC: tfc:fluid/salt_water…) — обычное состояние в палитре, жидкость ли это,
     * решает игра при импорте.
     */
    public final int[] over = new int[SIZE * SIZE];
    /** Индекс в {@link #biomes} или -1. */
    public final int[] biome = new int[SIZE * SIZE];
    public final List<BlockNbt> palette = new ArrayList<>();
    public final List<String> biomes = new ArrayList<>();
    /** Сколько чанков (16×16) в файле. */
    public int chunks;

    private XaeroRegion(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
        java.util.Arrays.fill(state, NONE);
        java.util.Arrays.fill(biome, -1);
        java.util.Arrays.fill(over, NONE);
    }

    /** Индекс пикселя в массивах: x, z — локальные в регионе. */
    public static int index(int x, int z) {
        return z * SIZE + x;
    }

    public static XaeroRegion read(InputStream zip, int rx, int rz) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(zip)) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (!"region.xaero".equals(e.getName())) continue;
                return parse(new DataInputStream(new Capped(zin, MAX_UNZIPPED)), rx, rz);
            }
        }
        throw new IOException("в архиве нет region.xaero");
    }

    static XaeroRegion parse(DataInputStream in, int rx, int rz) throws IOException {
        XaeroRegion r = new XaeroRegion(rx, rz);
        int first = in.read();
        if (first != 255) throw new IOException("старый формат Xaero без заголовка — не поддерживается");
        int ver = in.readInt();
        int major = ver >>> 16, minor = ver & 0xFFFF;
        if (major != 6 || minor < 8) throw new IOException("формат Xaero " + major + "." + minor + ", понимаю 6.8+");
        for (int tc; (tc = in.read()) != -1; ) {
            int tx = tc >> 4, tz = tc & 15;
            if (tx > 7 || tz > 7) throw new IOException("плитка " + tx + "," + tz + " вне региона");
            for (int cx = 0; cx < 4; cx++) {
                for (int cz = 0; cz < 4; cz++) {
                    int p0 = in.readInt();
                    if (p0 == -1) continue;                    // чанк не разведан
                    r.chunks++;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int param = x == 0 && z == 0 ? p0 : in.readInt();
                            r.pixel(in, param, tx * 64 + cx * 16 + x, tz * 64 + cz * 16 + z);
                        }
                    }
                    in.read();                                 // версия интерпретации мира
                    in.readInt();                              // начало пещерного слоя
                    in.read();                                 // глубина пещерного слоя
                }
            }
        }
        return r;
    }

    private void pixel(DataInputStream in, int p, int x, int z) throws IOException {
        int i = index(x, z);
        if ((p & 1) != 0) {
            if ((p & (1 << 21)) != 0) {
                if (palette.size() >= MAX_PALETTE) throw new IOException("палитра Xaero больше " + MAX_PALETTE);
                palette.add(Nbt.blockState(in));
                state[i] = palette.size() - 1;
            } else {
                int k = in.readInt();
                if (k < 0 || k >= palette.size()) throw new IOException("индекс палитры " + k + " из " + palette.size());
                state[i] = k;
            }
        } else {
            state[i] = GRASS;
        }
        int h;
        if ((p & 64) != 0) h = in.read();
        else h = (((p >> 12) & 255) | (((p >> 25) & 15) << 8)) << 20 >> 20;   // 12 бит со знаком
        height[i] = (short) h;
        int t = h;
        if ((p & (1 << 24)) != 0) {                            // верх хранится одним байтом — восстанавливаем
            int b = in.read();
            t = h + ((b - (h & 255)) & 255);
        }
        int first = NONE;
        if ((p & 2) != 0) {
            int n = in.read();
            for (int k = 0; k < n; k++) {
                int op = in.readInt(), st;
                if ((op & 1) != 0) {
                    if ((op & 1024) != 0) {
                        if (palette.size() >= MAX_PALETTE) throw new IOException("палитра Xaero больше " + MAX_PALETTE);
                        palette.add(Nbt.blockState(in));
                        st = palette.size() - 1;
                    } else {
                        st = in.readInt();
                        if (st < 0 || st >= palette.size()) throw new IOException("индекс палитры " + st + " из " + palette.size());
                    }
                } else {
                    st = WATER;
                }
                if ((op & 4) != 0) in.readInt();
                if (k == 0) first = st;
            }
        }
        top[i] = (short) t;
        over[i] = first;
        if ((p & (1 << 20)) != 0) {
            if ((p & (1 << 22)) != 0) {
                if ((p & (1 << 23)) != 0) throw new IOException("старый числовой id биома не поддерживается");
                biomes.add(in.readUTF());
                biome[i] = biomes.size() - 1;
            } else {
                int k = in.readInt();
                if (k < 0 || k >= biomes.size()) throw new IOException("индекс биома " + k + " из " + biomes.size());
                biome[i] = k;
            }
        }
    }

    /** Поток с потолком распаковки: zip-бомба упирается в исключение, а не в память. */
    private static final class Capped extends java.io.FilterInputStream {
        private long left;

        Capped(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("region.xaero больше " + MAX_UNZIPPED + " байт");
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("region.xaero больше " + MAX_UNZIPPED + " байт");
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** Минимальный NBT: состояние блока {Name, Properties{…}}; остальные теги пропускаются. */
    static final class Nbt {
        private static final int MAX_DEPTH = 16, MAX_LIST = 4096;

        static BlockNbt blockState(DataInputStream in) throws IOException {
            int type = in.readByte();
            if (type != 10) throw new IOException("NBT состояния блока: тег " + type + ", ждали compound");
            in.readUTF();                                      // имя корня (пустое)
            String name = null;
            Map<String, String> props = new LinkedHashMap<>();
            for (int t; (t = in.readByte()) != 0; ) {
                String key = in.readUTF();
                if (t == 8 && key.equals("Name")) {
                    name = in.readUTF();
                } else if (t == 10 && key.equals("Properties")) {
                    for (int pt; (pt = in.readByte()) != 0; ) {
                        String pk = in.readUTF();
                        if (pt == 8) props.put(pk, in.readUTF());
                        else skip(in, pt, 1);
                    }
                } else {
                    skip(in, t, 1);
                }
            }
            if (name == null) throw new IOException("NBT состояния блока без Name");
            return new BlockNbt(name, props);
        }

        static void skip(DataInputStream in, int t, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("NBT глубже " + MAX_DEPTH);
            switch (t) {
                case 1 -> in.skipNBytes(1);
                case 2 -> in.skipNBytes(2);
                case 3, 5 -> in.skipNBytes(4);
                case 4, 6 -> in.skipNBytes(8);
                case 7 -> in.skipNBytes(len(in, 1));
                case 8 -> in.skipNBytes(in.readUnsignedShort());
                case 9 -> {
                    int et = in.readByte(), n = in.readInt();
                    if (n < 0 || n > MAX_LIST) throw new IOException("NBT список " + n);
                    for (int k = 0; k < n; k++) skip(in, et, depth + 1);
                }
                case 10 -> {
                    for (int ct; (ct = in.readByte()) != 0; ) {
                        in.skipNBytes(in.readUnsignedShort());
                        skip(in, ct, depth + 1);
                    }
                }
                case 11 -> in.skipNBytes(len(in, 4));
                case 12 -> in.skipNBytes(len(in, 8));
                default -> throw new IOException("NBT тег " + t);
            }
        }

        private static long len(DataInputStream in, int unit) throws IOException {
            int n = in.readInt();
            if (n < 0 || n > MAX_LIST * 16) throw new IOException("NBT массив " + n);
            return (long) n * unit;
        }
    }
}
