package ru.stef.pergament.client.xaero;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Файл пишется здесь же по формату Xaero 6.8 (как его пишет MapSaveLoad.saveRegion/savePixel). */
class XaeroRegionTest {
    private static int param(boolean grass, boolean overlays, int height, boolean biome, boolean newState,
                             boolean newBiome, boolean topDiffers) {
        int p = grass ? 0 : 1;
        if (overlays) p |= 2;
        p |= (height & 255) << 12;
        if (biome) p |= 1 << 20;
        if (newState) p |= 1 << 21;
        if (newBiome) p |= 1 << 22;
        if (topDiffers) p |= 1 << 24;
        p |= ((height >> 8) & 15) << 25;
        return p;
    }

    private static void stateNbt(DataOutputStream o, String name, String k, String v) throws IOException {
        o.writeByte(10); o.writeUTF("");
        o.writeByte(8); o.writeUTF("Name"); o.writeUTF(name);
        if (k != null) {
            o.writeByte(10); o.writeUTF("Properties");
            o.writeByte(8); o.writeUTF(k); o.writeUTF(v);
            o.writeByte(0);
        }
        o.writeByte(3); o.writeUTF("junk"); o.writeInt(7);  // лишний тег — пропускается
        o.writeByte(0);
    }

    private static byte[] sample() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(raw);
        o.write(255);
        o.writeInt(6 << 16 | 8);
        o.write(1 << 4 | 2);                                   // плитка x=1, z=2
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                if (cx != 3 || cz != 1) { o.writeInt(-1); continue; }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (x == 0 && z == 0) {                // новый блок + новый биом
                            o.writeInt(param(false, false, 70, true, true, true, false));
                            stateNbt(o, "tfc:rock/raw/shale", "axis", "y");
                            o.writeUTF("tfc:plains");
                        } else if (x == 1 && z == 0) {         // трава Xaero, отрицательная высота
                            o.writeInt(param(true, false, -40, true, false, false, false));
                            o.writeInt(0);
                        } else if (x == 2 && z == 5) {         // дно на 50, вода до 63
                            o.writeInt(param(false, true, 50, true, false, false, true));
                            o.writeInt(0);
                            o.write(63);
                            o.write(1);
                            o.writeInt(0);                     // оверлей: вода (бит 0 = 0)
                            o.writeInt(0);
                        } else if (x == 3 && z == 3) {         // высота 300: старшие биты
                            o.writeInt(param(false, false, 300, false, true, false, false));
                            stateNbt(o, "minecraft:snow_block", null, null);
                        } else {
                            o.writeInt(param(false, false, 70, true, false, false, false));
                            o.writeInt(0);
                            o.writeInt(0);
                        }
                    }
                }
                o.write(2); o.writeInt(0); o.write(1);
            }
        }
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("region.xaero"));
            z.write(raw.toByteArray());
            z.closeEntry();
        }
        return zip.toByteArray();
    }

    @Test
    void parsesPixelsPaletteBiomesWater() throws IOException {
        XaeroRegion r = XaeroRegion.read(new ByteArrayInputStream(sample()), -3, 4);
        assertEquals(1, r.chunks);
        int bx = 64 + 3 * 16, bz = 128 + 16;                    // плитка 1,2 → чанк 3,1
        int i0 = XaeroRegion.index(bx, bz);
        assertEquals(0, r.state[i0]);
        assertEquals("tfc:rock/raw/shale", r.palette.get(0).name());
        assertEquals("y", r.palette.get(0).props().get("axis"));
        assertEquals(70, r.height[i0]);
        assertEquals("tfc:plains", r.biomes.get(r.biome[i0]));

        int ig = XaeroRegion.index(bx + 1, bz);
        assertEquals(XaeroRegion.GRASS, r.state[ig]);
        assertEquals(-40, r.height[ig]);

        int iw = XaeroRegion.index(bx + 2, bz + 5);
        assertEquals(XaeroRegion.WATER, r.over[iw]);
        assertEquals(50, r.height[iw]);
        assertEquals(63, r.top[iw]);

        int ih = XaeroRegion.index(bx + 3, bz + 3);
        assertEquals(300, r.height[ih]);
        assertEquals("minecraft:snow_block", r.palette.get(r.state[ih]).name());
        assertEquals(-1, r.biome[ih]);
        assertEquals(XaeroRegion.NONE, r.over[ih]);

        assertEquals(XaeroRegion.NONE, r.state[XaeroRegion.index(0, 0)]);   // неразведанное
    }

    @Test
    void foreignVersionAndGarbageRefused() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(raw);
        o.write(255);
        o.writeInt(5 << 16 | 2);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("region.xaero"));
            z.write(raw.toByteArray());
        }
        IOException e = assertThrows(IOException.class, () -> XaeroRegion.read(new ByteArrayInputStream(zip.toByteArray()), 0, 0));
        assertTrue(e.getMessage().contains("5.2"));
        assertThrows(IOException.class, () -> XaeroRegion.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), 0, 0));
    }

    @Test
    void truncatedFileFailsLoudly() throws IOException {
        byte[] full = sample();
        // обрезать сырой region.xaero посередине
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var zin = new java.util.zip.ZipInputStream(new ByteArrayInputStream(full))) {
            zin.getNextEntry();
            raw.write(zin.readAllBytes());
        }
        byte[] cut = java.util.Arrays.copyOf(raw.toByteArray(), raw.size() / 2);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("region.xaero"));
            z.write(cut);
        }
        assertThrows(IOException.class, () -> XaeroRegion.read(new ByteArrayInputStream(zip.toByteArray()), 0, 0));
    }
}
