package ru.stef.pergament.client.gt;

import com.gregtechceu.gtceu.api.block.MaterialBlock;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialStack;
import com.gregtechceu.gtceu.common.block.SurfaceRockBlock;
import net.minecraft.world.level.block.state.BlockState;
import ru.stef.pergament.client.map.Veins;

/**
 * Что это за блок с точки зрения GregTech: индикатор жилы (камешек на поверхности, «бутон»)
 * или рудный блок — и какого материала. Грузится лишь при установленном GTCEu.
 */
public final class GtInspector implements Veins.Inspector {
    @Override
    public Veins.MatInfo indicator(BlockState s) {
        if (s.getBlock() instanceof SurfaceRockBlock rock) return info(rock.getMaterial());
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id != null && id.getPath().endsWith("_indicator")) {        // «бутоны» TFG и прочие индикаторы-предметы
            MaterialStack ms = ChemicalHelper.getMaterialStack(s.getBlock());
            if (!ms.isEmpty()) return info(ms.material());
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
        return null;
    }

    private static Veins.MatInfo info(Material m) {
        if (m == null || m.isNull()) return null;
        return new Veins.MatInfo(m.getResourceLocation().toString(), m.getLocalizedName().getString(), m.getMaterialRGB() & 0xFFFFFF);
    }
}
