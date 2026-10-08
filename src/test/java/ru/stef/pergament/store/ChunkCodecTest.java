package ru.stef.pergament.store;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecTest {
    private static RegionData filled(int rx, int rz, long seed) {
        RegionData r = new RegionData(rx, rz);
        Random rnd = new Random(seed);
        for (int z = 0; z < RegionData.SIZE; z++) {
            for (int x = 0; x < RegionData.SIZE; x++) {
                if (rnd.nextInt(4) == 0) continue;
                r.set(x, z, rnd.nextInt(), rnd.nextInt(600) - 64, rnd.nextInt(600) - 64, rnd.nextInt(40), (byte) rnd.nextInt(10));
            }
        }
        return r;
    }

    @Test
    void roundTripThroughDeflateReplacesWholeChunk() {
        RegionData src = filled(-1, 2, 1), dst = filled(-1, 2, 2);
        int cx = -17, cz = 67;                                // регион -1,2: чанки -32…-1 × 64…95
        byte[] z = ChunkCodec.deflate(ChunkCodec.extract(src, cx, cz));
        ChunkCodec.apply(dst, cx, cz, ChunkCodec.inflate(z));
        assertArrayEquals(ChunkCodec.extract(src, cx, cz), ChunkCodec.extract(dst, cx, cz));
        assertFalse(Arrays.equals(ChunkCodec.extract(src, cx + 1, cz), ChunkCodec.extract(dst, cx + 1, cz)),
                "соседний чанк не должен меняться");
    }

    @Test
    void negativeHeightsSurvive() {
        RegionData r = new RegionData(0, 0);
        r.set(3, 4, 0xFF112233, -60, -64, 0, RegionData.CLS_LAND);
        RegionData d = new RegionData(0, 0);
        ChunkCodec.apply(d, 0, 0, ChunkCodec.extract(r, 0, 0));
        int i = (4 << RegionData.SHIFT) | 3;
        assertEquals(-60, d.height[i]);
        assertEquals(-64, d.ground[i]);
        assertEquals(0xFF112233, d.color[i]);
        assertTrue(ChunkCodec.known(d, 0, 0));
        assertFalse(ChunkCodec.known(d, 1, 0));
    }

    @Test
    void chunkOutsideRegionRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.extract(new RegionData(0, 0), 32, 0));
    }

    @Test
    void bombShortAndGarbageRejected() {
        byte[] big = ChunkCodec.deflate(new byte[ChunkCodec.RAW * 50]);        // маленький пакет, огромная распаковка
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.inflate(big));
        byte[] small = ChunkCodec.deflate(new byte[ChunkCodec.RAW - 1]);
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.inflate(small));
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.inflate(new byte[]{1, 2, 3, 4, 5}));
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.apply(new RegionData(0, 0), 0, 0, new byte[10]));
    }

    @Test
    void typicalChunkCompressesWell() {
        RegionData r = new RegionData(0, 0);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) r.set(x, z, 0xFF3E6B2E + (x & 3), 64 + (x + z) / 8, 63, 0, RegionData.CLS_LAND);
        int n = ChunkCodec.deflate(ChunkCodec.extract(r, 0, 0)).length;
        assertTrue(n < 1200, "сжатый чанк " + n + " байт");
    }
}
