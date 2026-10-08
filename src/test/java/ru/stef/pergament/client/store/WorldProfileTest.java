package ru.stef.pergament.client.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Имена профилей: русские миры не сливаются в один «___», двоеточие адреса не ломает путь. */
class WorldProfileTest {
    @Test
    void cyrillicWorldNamesStayDistinct() {
        assertEquals("sp_новый_мир", "sp_" + WorldProfile.safe("Новый мир"));
        assertNotEquals(WorldProfile.safe("Новый мир"), WorldProfile.safe("Мир 2"));
    }

    @Test
    void serverAddressIsPathSafe() {
        assertEquals("203.0.113.5_25565", WorldProfile.safe("203.0.113.5:25565"));
        assertEquals("minecraft_the_nether", WorldProfile.safe("minecraft:the_nether"));
    }
}
