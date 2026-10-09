package ru.stef.pergament.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Клиентский конфиг: config/pergament-client.toml. */
public final class PergamentConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue REVEAL_FRONT;
    public static final ForgeConfigSpec.IntValue REVEAL_BACK;
    public static final ForgeConfigSpec.DoubleValue SCAN_BUDGET_MS;
    public static final ForgeConfigSpec.DoubleValue COLOR_KEEP;
    public static final ForgeConfigSpec.BooleanValue SHOW_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue SHOW_MOBS;
    public static final ForgeConfigSpec.BooleanValue ENTITIES_ON_MINIMAP;
    public static final ForgeConfigSpec.IntValue MOB_VERTICAL;
    public static final ForgeConfigSpec.IntValue DEATH_KEEP;
    public static final ForgeConfigSpec.IntValue MARKER_SCALE;
    public static final ForgeConfigSpec.BooleanValue WAYPOINTS;
    public static final ForgeConfigSpec.BooleanValue MINIMAP;
    public static final ForgeConfigSpec.IntValue MINIMAP_SIZE;
    public static final ForgeConfigSpec.BooleanValue MINIMAP_CLOCK;
    public static final ForgeConfigSpec.IntValue MINIMAP_CORNER;
    public static final ForgeConfigSpec.DoubleValue MINIMAP_ZOOM;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("reveal");
        REVEAL_FRONT = b.comment("Chunks revealed ahead along your view (like server view distance)", "Сколько чанков открывается по взгляду (как прогрузка сервера)")
                .defineInRange("front", 8, 1, 32);
        REVEAL_BACK = b.comment("Chunks revealed behind you", "Сколько чанков открывается за спиной")
                .defineInRange("back", 4, 1, 32);
        SCAN_BUDGET_MS = b.comment("Milliseconds per tick allowed for chunk scanning", "Сколько миллисекунд за тик можно тратить на скан чанков")
                .defineInRange("scanBudgetMs", 2.0, 0.2, 20.0);
        b.pop();
        b.push("style");
        COLOR_KEEP = b.comment("How much real block color shows through the parchment: 0 — paper only, 1 — as in game", "Сколько настоящего цвета блоков проступает сквозь пергамент: 0 — одна бумага, 1 — как в игре")
                .defineInRange("colorKeep", 0.8, 0.2, 1.0);
        b.pop();
        b.push("entities");
        SHOW_PLAYERS = b.comment("Other players (within render distance) — skin head", "Другие игроки (в пределах прорисовки) — головой со скина").define("players", true);
        SHOW_MOBS = b.comment("Mobs (within render distance) — head or a dot by type", "Мобы (в пределах прорисовки) — лицом или кружком по типу").define("mobs", false);
        ENTITIES_ON_MINIMAP = b.comment("Show players and mobs on the minimap too", "Показывать игроков и мобов и на миникарте").define("onMinimap", true);
        MOB_VERTICAL = b.comment("Mobs are shown only within this many blocks of your height", "Мобы видны, только если они не дальше стольких блоков по высоте от игрока")
                .defineInRange("mobVerticalRange", 20, 2, 384);
        b.pop();
        b.push("markers");
        MARKER_SCALE = b.comment("Marker icon size, percent", "Размер значков меток, проценты").defineInRange("iconScale", 100, 50, 300);
        WAYPOINTS = b.comment("Show markers marked “in world” in the game world (through walls)",
                "Показывать в игровом мире метки с галочкой «в мире» (сквозь стены)").define("inWorld", true);
        b.pop();
        b.push("deaths");
        DEATH_KEEP = b.comment("How many recent death markers to keep per dimension; 0 — off", "Сколько последних меток «здесь погиб» хранить в каждом измерении; 0 — не ставить")
                .defineInRange("keep", 5, 0, 50);
        b.pop();
        b.push("minimap");
        MINIMAP = b.comment("Show the minimap (key Y)", "Показывать миникарту (клавиша Y)").define("enabled", true);
        MINIMAP_CLOCK = b.comment("Game time, real time and date (TerraFirmaCraft calendar) under the minimap",
                "Время в игре, настоящее время и дата (календарь TerraFirmaCraft) под миникартой").define("clock", true);
        MINIMAP_SIZE = b.comment("Minimap side, GUI pixels", "Сторона миникарты, пикселей GUI").defineInRange("size", 110, 48, 256);
        MINIMAP_CORNER = b.comment("Corner: 0 top left, 1 top right, 2 bottom left, 3 bottom right", "Угол: 0 левый верхний, 1 правый верхний, 2 левый нижний, 3 правый нижний")
                .defineInRange("corner", 1, 0, 3);
        MINIMAP_ZOOM = b.comment("GUI pixels per block", "Пикселей GUI на блок").defineInRange("zoom", 1.0, 0.25, 4.0);
        b.pop();
        SPEC = b.build();
    }

    private PergamentConfig() {}
}
