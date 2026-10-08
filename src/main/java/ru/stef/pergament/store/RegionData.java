package ru.stef.pergament.store;

/**
 * Разведанное в одном регионе 512×512 блоков: по колонке — цвет верхнего блока (ABGR, как у NativeImage),
 * высота верха (крона, крыша — для отмывки), высота земли (под листвой — для горизонталей и троп),
 * глубина воды, класс и флаг «разведано». Не разведано — {@code known == 0}:
 * это не «пусто», а «не знаем» (пустота Энда — отдельный класс {@link #CLS_VOID}).
 * Мутирует только клиентский поток; для фона берётся {@link #snapshot()}.
 */
public final class RegionData {
    public static final int SIZE = 512, SHIFT = 9;
    public static final byte CLS_LAND = 0, CLS_WALL = 5, CLS_LAVA = 7, CLS_VOID = 9;

    public final int rx, rz;
    public final int[] color;
    public final short[] height;
    public final short[] ground;
    public final byte[] water;
    public final byte[] cls;
    public final byte[] known;
    /** Растёт при каждой правке — по ней рендер понимает, что текстуру пора перекрасить. */
    private int revision;
    private boolean dirty;

    public RegionData(int rx, int rz) {
        this(rx, rz, new int[SIZE * SIZE], new short[SIZE * SIZE], new short[SIZE * SIZE], new byte[SIZE * SIZE],
                new byte[SIZE * SIZE], new byte[SIZE * SIZE]);
    }

    public RegionData(int rx, int rz, int[] color, short[] height, short[] ground, byte[] water, byte[] cls, byte[] known) {
        this.rx = rx;
        this.rz = rz;
        this.color = color;
        this.height = height;
        this.ground = ground;
        this.water = water;
        this.cls = cls;
        this.known = known;
    }

    public void set(int lx, int lz, int abgr, int y, int waterDepth, byte klass) {
        set(lx, lz, abgr, y, y, waterDepth, klass);
    }

    public void set(int lx, int lz, int abgr, int top, int groundY, int waterDepth, byte klass) {
        int i = (lz << SHIFT) | lx;
        color[i] = abgr;
        height[i] = (short) top;
        ground[i] = (short) groundY;
        water[i] = (byte) Math.min(255, waterDepth);
        cls[i] = klass;
        known[i] = 1;
    }

    public void touch() {
        revision++;
        dirty = true;
    }

    public int revision() { return revision; }
    public boolean dirty() { return dirty; }
    public void clean() { dirty = false; }

    public RegionData snapshot() {
        RegionData s = new RegionData(rx, rz, color.clone(), height.clone(), ground.clone(), water.clone(), cls.clone(), known.clone());
        s.revision = revision;
        return s;
    }

    public static int regionOf(int block) {
        return block >> SHIFT;
    }
}
