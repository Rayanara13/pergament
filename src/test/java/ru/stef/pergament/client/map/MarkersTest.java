package ru.stef.pergament.client.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Метки: создание, правка по id без дублей, удаление, кириллица на диске, битая иконка → по умолчанию. */
class MarkersTest {
    @Test
    void putEditRemoveSurviveReload(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("markers.json");
        Markers s = Markers.load(f);
        Markers.Marker m = new Markers.Marker();
        m.name = "Ангар";
        m.desc = "пол из шиферного сланца";
        m.icon = "house";
        m.x = 1450; m.y = 70; m.z = 1390;
        s.put(m);
        Markers.Marker edit = m.copy();
        edit.name = "Новый ангар";
        s.put(edit);
        Markers r = Markers.load(f);
        assertEquals(1, r.all().size(), "правка не плодит дубль");
        assertEquals("Новый ангар", r.all().get(0).name);
        assertEquals("house", r.all().get(0).icon);
        assertTrue(Files.readString(f).contains("шиферного"), "кириллица как есть, без \\u-экранов");
        r.remove(m.id);
        assertTrue(Markers.load(f).all().isEmpty());
    }

    @Test
    void unknownIconFallsBack(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("markers.json");
        Files.writeString(f, "[{\"id\":\"a\",\"name\":\"x\",\"icon\":\"нет_такой\",\"x\":1,\"y\":2,\"z\":3}]");
        assertEquals(Markers.DEFAULT_ICON, Markers.load(f).all().get(0).icon);
    }

    @Test
    void iconsHaveRussianNamesAndOldIdsSurvive() {
        assertTrue(Markers.ICONS.size() >= 50, "значков много: " + Markers.ICONS.size());
        for (String old : new String[]{"pin_red", "pin_blue", "pin_green", "pin_gold", "house", "flag", "star", "cave",
                "tree", "chest", "boat", "skull"}) {
            assertTrue(Markers.knownIcon(old), "старые метки не теряют значок: " + old);
        }
        assertEquals("Дом", Markers.iconName("house"));
        for (var i : Markers.ICONS) assertTrue(i.ru().matches(".*[А-Яа-яЁё].*"), "по-русски: " + i.id());
    }
}
