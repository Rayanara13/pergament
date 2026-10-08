package ru.stef.pergament.client.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import ru.stef.pergament.Pergament;

import java.util.List;

/**
 * Рудные жилы, которые игрок честно подтвердил. Источник — только GregTech: сервер GT сам проверяет
 * настоящую жилу, когда игрок собрал поверхностный камешек, добыл руду руками или воспользовался
 * проспектором, и присылает её в клиентский кэш. Мы этот кэш только читаем. Нет GTCEu — нет и жил.
 *
 * GT-код лежит в пакете {@code gt} и грузится только через Class.forName — на чистом Forge
 * ни один GT-класс не тронут.
 */
public final class Veins {
    /** Материал жилы: название (как в игре) и его настоящий цвет 0xRRGGBB. */
    public record Mat(String name, int rgb) {}

    /** Жила: центр, радиус (по размеру скопления), название, материалы, истощена ли, пояснение. */
    public record Vein(int x, int y, int z, int radius, String name, List<Mat> mats, boolean depleted, String note) {}

    public interface Source {
        List<Vein> inArea(ResourceKey<Level> dim, int x, int z, int w, int h);
    }

    /** Материал блока: id, название как в игре, настоящий цвет 0xRRGGBB. */
    public record MatInfo(String id, String name, int rgb) {}

    /** Распознаёт индикаторы жил и рудные блоки (реализация — в пакете gt). */
    public interface Inspector {
        MatInfo indicator(net.minecraft.world.level.block.state.BlockState s);
        MatInfo ore(net.minecraft.world.level.block.state.BlockState s);
    }

    private static Source source;
    private static Inspector inspector;
    private static boolean resolved;

    private Veins() {}

    /** Распознавание индикаторов и руды или null без GTCEu. */
    public static Inspector inspector() {
        source();
        return inspector;
    }

    /** Источник жил или null, если GTCEu не установлен (или его API не тот). */
    public static Source source() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("gtceu")) {
                try {
                    source = (Source) Class.forName("ru.stef.pergament.client.gt.GtVeinSource")
                            .getDeclaredConstructor().newInstance();
                    inspector = (Inspector) Class.forName("ru.stef.pergament.client.gt.GtInspector")
                            .getDeclaredConstructor().newInstance();
                    Pergament.LOG.info("Пергамент: жилы GregTech подключены");
                } catch (Throwable t) {
                    Pergament.LOG.warn("Пергамент: GTCEu есть, но его кэш жил не читается: {}", t.toString());
                }
            }
        }
        return source;
    }
}
