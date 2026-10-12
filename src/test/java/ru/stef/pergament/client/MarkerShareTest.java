package ru.stef.pergament.client;

import org.junit.jupiter.api.Test;
import ru.stef.pergament.client.map.Markers;

import static org.junit.jupiter.api.Assertions.*;

class MarkerShareTest {
    @Test
    void ourMessageInsideChatLine() {
        var s = MarkerShare.parse("<Alice> Метка «Шахта у реки» — 120 64 -300 [pgm ore_copper minecraft:overworld]", "Alice");
        assertNotNull(s);
        assertEquals("Шахта у реки", s.name());
        assertEquals(120, s.x());
        assertEquals(64, s.y());
        assertEquals(-300, s.z());
        assertEquals("ore_copper", s.icon());
        assertEquals("minecraft:overworld", s.dim());
        assertEquals("Alice", s.from());
    }

    @Test
    void englishSenderAndModdedDimension() {
        var s = MarkerShare.parse("Marker «Base» — -5 70 9 [pgm house twilightforest:twilight_forest]", "");
        assertNotNull(s);
        assertEquals("Base", s.name());
        assertEquals("twilightforest:twilight_forest", s.dim());
    }

    @Test
    void unknownIconFallsBackToDefault() {
        var s = MarkerShare.parse("Метка «X» — 1 2 3 [pgm no_such_icon minecraft:overworld]", "");
        assertNotNull(s);
        assertEquals(Markers.DEFAULT_ICON, s.icon());
    }

    @Test
    void xaeroWaypoint() {
        var s = MarkerShare.parse("<Bob> xaero-waypoint:Home§§1:H:100:64:-200:6:false:0:Internal-overworld-waypoints", "Bob");
        assertNotNull(s);
        assertEquals("Home:1", s.name());
        assertEquals(100, s.x());
        assertEquals(64, s.y());
        assertEquals(-200, s.z());
        assertEquals("minecraft:overworld", s.dim());
    }

    @Test
    void xaeroNetherWithoutHeight() {
        var s = MarkerShare.parse("xaero-waypoint:Fort:F:-40:~:12:4:false:0:Internal-the-nether-waypoints", "");
        assertNotNull(s);
        assertNull(s.y());
        assertEquals("minecraft:the_nether", s.dim());
    }

    @Test
    void plainChatIsIgnored() {
        assertNull(MarkerShare.parse("<Bob> пошли в шахту 120 64 -300", ""));
        assertNull(MarkerShare.parse("Метка «A» — 1 2 3", ""));            // без хвоста [pgm …] — не наше
    }
}
