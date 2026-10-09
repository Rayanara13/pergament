package ru.stef.pergament.client.gt;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.block.MaterialBlock;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialStack;
import com.gregtechceu.gtceu.common.block.SurfaceRockBlock;
import net.minecraft.world.level.block.state.BlockState;
import ru.stef.pergament.client.map.Veins;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что это за блок с точки зрения GregTech: индикатор жилы (камешек на поверхности, «бутон», малая руда TFC)
 * или рудный блок — и какого материала. Грузится лишь при установленном GTCEu.
 * Руды TerraFirmaCraft (tfc:ore/small_*, tfc:ore/{poor,normal,rich}_*) сводятся к материалу GregTech
 * того же имени, если он есть (в TFG — есть), иначе к собственному «tfc:<материал>».
 */
public final class GtInspector implements Veins.Inspector {
    private static final Pattern TFC_ORE = Pattern.compile("ore/(?:poor|normal|rich)_([a-z_]+)/[a-z_]+");
    /** Самородные TFC — у GregTech это просто металл. */
    private static final Map<String, String> TFC_TO_GT = Map.of(
            "native_copper", "copper", "native_gold", "gold", "native_silver", "silver");

    @Override
    public Veins.MatInfo indicator(BlockState s) {
        if (s.getBlock() instanceof SurfaceRockBlock rock) return info(rock.getMaterial());
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id == null) return null;
        if (id.getPath().endsWith("_indicator")) {                        // «бутоны» TFG и прочие индикаторы-предметы
            MaterialStack ms = ChemicalHelper.getMaterialStack(s.getBlock());
            if (!ms.isEmpty()) return info(ms.material());
        }
        if ("tfc".equals(id.getNamespace()) && id.getPath().startsWith("ore/small_")) {   // кусочек руды TFC на земле
            return tfc(id.getPath().substring("ore/small_".length()));
        }
        return null;
    }

    @Override
    public Veins.MatInfo ore(BlockState s) {
        // в TFG префикс руды назван по породе («shale»), поэтому — по реестру рудных префиксов GT
        if (s.getBlock() instanceof MaterialBlock mb && mb.tagPrefix != null
                && com.gregtechceu.gtceu.api.data.tag.TagPrefix.ORES.containsKey(mb.tagPrefix)) {
            return info(mb.material);
        }
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id != null && "tfc".equals(id.getNamespace())) {
            Matcher m = TFC_ORE.matcher(id.getPath());
            if (m.matches()) return tfc(m.group(1));
        }
        return null;
    }

    /** Материал TFC → материал GregTech того же имени или свой «tfc:<имя>». */
    static Veins.MatInfo tfc(String mat) {
        String gt = TFC_TO_GT.getOrDefault(mat, mat);
        try {
            Material m = GTCEuAPI.materialManager.getMaterial("gtceu:" + gt);
            Veins.MatInfo info = info(m);
            if (info != null) return info;
        } catch (RuntimeException ignored) {
            // реестр материалов не готов или имя чужое — ниже свой
        }
        String[] w = mat.split("_");
        StringBuilder name = new StringBuilder();
        for (String p : w) if (!p.isEmpty()) name.append(name.length() > 0 ? " " : "").append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        return new Veins.MatInfo("tfc:" + mat, name.toString(), 0x9A9A9A);
    }

    private static Veins.MatInfo info(Material m) {
        if (m == null || m.isNull()) return null;
        return new Veins.MatInfo(m.getResourceLocation().toString(), m.getLocalizedName().getString(), m.getMaterialRGB() & 0xFFFFFF);
    }
}
