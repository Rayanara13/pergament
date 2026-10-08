package ru.stef.pergament.client;

import net.minecraftforge.fml.ModList;
import ru.stef.pergament.Pergament;

import java.util.Map;

/**
 * Подстройка под TerraFirmaGreg — сама, без правки конфигов руками: размеры объёма кирок-пробников
 * читаются из конфига TFG (на сервере — серверного, Forge присылает его клиенту при входе).
 * Без TFG — значения по умолчанию из его defaultconfigs. Только рефлексия: от TFG мы не зависим.
 */
public final class TfgCompat {
    /** Металл кирки → поле конфига TFG. */
    private static final Map<String, String> FIELD = Map.of(
            "copper", "copperPropickConfig", "bronze", "bronzePropickConfig", "bismuth_bronze", "bronzePropickConfig",
            "black_bronze", "bronzePropickConfig", "wrought_iron", "wroughtIronPropickConfig", "steel", "steelPropickConfig",
            "black_steel", "blackSteelPropickConfig", "blue_steel", "blueSteelPropickConfig", "red_steel", "redSteelPropickConfig");
    /** Значения по умолчанию TFG 0.9.23 (defaultconfigs/tfg-server.toml): длина, полуширина. */
    private static final Map<String, int[]> DEFAULTS = Map.of(
            "copper", new int[]{15, 5}, "bronze", new int[]{20, 8}, "bismuth_bronze", new int[]{20, 8},
            "black_bronze", new int[]{20, 8}, "wrought_iron", new int[]{30, 10}, "steel", new int[]{40, 12},
            "black_steel", new int[]{50, 15}, "blue_steel", new int[]{75, 15}, "red_steel", new int[]{50, 25});

    private TfgCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded("tfg");
    }

    /** Длина и полуширина объёма кирки из металла metal, или null — это не кирка TFG. */
    public static int[] propick(String metal) {
        int[] def = DEFAULTS.get(metal);
        if (def == null) return null;
        if (!present()) return def;
        try {
            Class<?> cfg = Class.forName("su.terrafirmagreg.core.config.TFGConfig");
            Object server = cfg.getField("SERVER").get(null);
            Object pick = server.getClass().getField(FIELD.get(metal)).get(server);
            int len = intValue(pick, "searchLength"), width = intValue(pick, "searchWidth");
            return new int[]{len, width};
        } catch (Throwable t) {
            Pergament.LOG.debug("Пергамент: конфиг кирок TFG не прочитан ({}), беру умолчания", t.toString());
            return def;
        }
    }

    private static int intValue(Object rec, String accessor) throws ReflectiveOperationException {
        Object v = rec.getClass().getMethod(accessor).invoke(rec);           // ForgeConfigSpec.IntValue
        return (Integer) v.getClass().getMethod("get").invoke(v);
    }
}
