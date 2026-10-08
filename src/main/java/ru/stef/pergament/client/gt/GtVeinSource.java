package ru.stef.pergament.client.gt;

import ru.stef.pergament.client.T;

import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.integration.map.cache.client.GTClientCache;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import ru.stef.pergament.client.map.Veins;

import java.util.ArrayList;
import java.util.List;

/**
 * Жилы из клиентского кэша GregTech ({@link GTClientCache}) — туда попадает только то,
 * что сервер GT подтвердил этому игроку. Грузится лишь при установленном GTCEu.
 */
public final class GtVeinSource implements Veins.Source {
    @Override
    public List<Veins.Vein> inArea(ResourceKey<Level> dim, int x, int z, int w, int h) {
        List<Veins.Vein> out = new ArrayList<>();
        for (GeneratedVeinMetadata m : GTClientCache.instance.getVeinsInArea(dim, new int[]{x, z, w, h})) {
            GTOreDefinition def = m.definition();
            List<Veins.Mat> mats = new ArrayList<>();
            int radius = 16;
            if (def != null) {
                for (Material mat : def.veinGenerator().getAllMaterials()) {
                    mats.add(new Veins.Mat(mat.getLocalizedName().getString(), mat.getMaterialRGB() & 0xFFFFFF));
                }
                radius = Math.max(4, def.clusterSize().getMaxValue() / 2);
            }
            String key = "gtceu.jei.ore_vein." + m.id().getPath();
            String name = I18n.exists(key) ? I18n.get(key) : m.id().getPath().replace('_', ' ');
            out.add(new Veins.Vein(m.center().getX(), m.center().getY(), m.center().getZ(), radius, name, mats, m.depleted(),
                    T.t("vein.gt_prospector")));
        }
        return out;
    }
}
