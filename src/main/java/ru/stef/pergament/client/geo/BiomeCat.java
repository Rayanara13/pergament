package ru.stef.pergament.client.geo;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Подсказка биома для местности чанка: теги TerraFirmaCraft ({@code tfc:is_volcanic/is_lake/is_river/is_ocean}),
 * ванильные и Forge-теги, горы — ещё и по имени биома (у модов тегов часто нет). Ответ кэшируется на биом.
 */
public final class BiomeCat {
    private static final TagKey<Biome> TFC_VOLCANIC = tag("tfc", "is_volcanic"), TFC_LAKE = tag("tfc", "is_lake"),
            TFC_RIVER = tag("tfc", "is_river"), TFC_OCEAN = tag("tfc", "is_ocean"),
            FORGE_MOUNTAIN = tag("forge", "is_mountain"), FORGE_SWAMP = tag("forge", "is_swamp"),
            FORGE_BEACH = tag("forge", "is_beach");
    private static final Map<Biome, Byte> CACHE = new IdentityHashMap<>();

    private BiomeCat() {}

    private static TagKey<Biome> tag(String ns, String path) {
        return TagKey.create(Registries.BIOME, new ResourceLocation(ns, path));
    }

    /** Категория {@link Geo} по биому. Только главный поток (кэш без блокировок). */
    public static byte of(Holder<Biome> h) {
        Byte c = CACHE.get(h.value());
        if (c != null) return c;
        String id = h.unwrapKey().map(k -> k.location().getPath()).orElse("");
        byte r;
        if (h.is(TFC_VOLCANIC)) r = id.contains("lake") ? Geo.LAKE : Geo.VOLCANIC;
        else if (h.is(TFC_LAKE) || id.endsWith("lake")) r = Geo.LAKE;
        else if (h.is(TFC_RIVER) || h.is(BiomeTags.IS_RIVER)) r = Geo.RIVER;
        else if (h.is(TFC_OCEAN) || h.is(BiomeTags.IS_OCEAN) || h.is(BiomeTags.IS_DEEP_OCEAN)) r = Geo.OCEAN;
        else if (h.is(BiomeTags.IS_MOUNTAIN) || h.is(FORGE_MOUNTAIN) || id.contains("mountain") || id.contains("peaks")) r = Geo.MOUNTAINS;
        else if (h.is(FORGE_SWAMP) || id.contains("swamp") || id.contains("marsh") || id.equals("lowlands")) r = Geo.H_SWAMP;
        else if (h.is(BiomeTags.IS_BEACH) || h.is(FORGE_BEACH) || id.contains("beach") || id.equals("shore") || id.contains("tidal")) r = Geo.H_BEACH;
        else r = Geo.NONE;
        CACHE.put(h.value(), r);
        return r;
    }

    /** Теги перезагрузились (вход в мир) — кэш заново. */
    public static void clear() {
        CACHE.clear();
    }
}
