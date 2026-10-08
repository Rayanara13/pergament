package ru.stef.pergament.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Формат PGMT: запись → чтение один в один (и для отрицательных регионов), чужая версия — отказ, нет файла — null. */
class RegionFileTest {
    @Test
    void roundTripKeepsEveryColumn(@TempDir Path dir) throws IOException {
        RegionData d = new RegionData(-3, 7);
        d.set(0, 0, 0xFF112233, -64, 0, RegionData.CLS_LAND);
        d.set(511, 511, 0xFF445566, 319, 12, RegionData.CLS_LAND);
        d.set(7, 7, 0xFF2E6B2E, 80, 66, 0, RegionData.CLS_LAND);      // крона на 80, земля на 66
        d.set(100, 200, 0xFF000000, -64, 0, RegionData.CLS_VOID);
        RegionFile.write(dir, d);
        RegionData r = RegionFile.read(dir, -3, 7);
        assertNotNull(r);
        assertArrayEquals(d.color, r.color);
        assertArrayEquals(d.height, r.height);
        assertArrayEquals(d.ground, r.ground);
        assertEquals(66, r.ground[7 * 512 + 7], "земля под кроной сохраняется отдельно");
        assertArrayEquals(d.water, r.water);
        assertArrayEquals(d.cls, r.cls);
        assertArrayEquals(d.known, r.known);
        assertEquals(319, r.height[511 * 512 + 511], "горы выше 255 не обрезаются");
        assertEquals(0, r.known[5], "неразведанное остаётся неразведанным");
        assertFalse(Files.exists(dir.resolve("r.-3.7.pgmt.tmp")), "временный файл подменён атомарно");
    }

    @Test
    void missingFileIsNull(@TempDir Path dir) throws IOException {
        assertNull(RegionFile.read(dir, 0, 0));
    }

    @Test
    void version1StillReadsWithGroundEqualTop(@TempDir Path dir) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(
                new DeflaterOutputStream(Files.newOutputStream(RegionFile.path(dir, 1, 2)))))) {
            out.writeInt(RegionFile.MAGIC);
            out.writeInt(1);
            out.writeInt(RegionData.SIZE);
            out.writeInt(1);
            out.writeInt(2);
            int n = RegionData.SIZE * RegionData.SIZE;
            for (int i = 0; i < n; i++) out.writeInt(i == 3 ? 0xFF102030 : 0);
            for (int i = 0; i < n; i++) out.writeShort(i == 3 ? 77 : 0);
            out.write(new byte[n]);
            out.write(new byte[n]);
            byte[] known = new byte[n];
            known[3] = 1;
            out.write(known);
        }
        RegionData r = RegionFile.read(dir, 1, 2);
        assertEquals(77, r.height[3]);
        assertEquals(77, r.ground[3], "в v1 земли не было — берём верх");
        assertEquals(1, r.known[3]);
    }

    @Test
    void foreignVersionIsRefused(@TempDir Path dir) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(RegionFile.path(dir, 0, 0))))) {
            out.writeInt(RegionFile.MAGIC);
            out.writeInt(99);
        }
        IOException e = assertThrows(IOException.class, () -> RegionFile.read(dir, 0, 0));
        assertTrue(e.getMessage().contains("версия формата 99"), e.getMessage());
    }
}
