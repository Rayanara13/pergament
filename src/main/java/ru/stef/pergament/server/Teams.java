package ru.stef.pergament.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import ru.stef.pergament.Pergament;

import java.util.List;
import java.util.UUID;

/**
 * Кто с кем в команде. Учитываются только настоящие party-команды: личная «команда из одного» FTB — не команда.
 * Реализация FTB грузится отражением и только при установленном FTB Teams — без него классы FTB не трогаются.
 */
public interface Teams {
    /** UUID party-команды игрока или null. */
    UUID party(ServerPlayer p);

    /** Онлайн-сокомандники по party, без него самого; не в party — пусто. */
    List<ServerPlayer> onlineMates(ServerPlayer p);

    Teams NONE = new Teams() {
        @Override
        public UUID party(ServerPlayer p) {
            return null;
        }

        @Override
        public List<ServerPlayer> onlineMates(ServerPlayer p) {
            return List.of();
        }
    };

    static Teams detect() {
        if (!ModList.get().isLoaded("ftbteams")) return NONE;
        try {
            return (Teams) Class.forName("ru.stef.pergament.server.FtbTeams").getConstructor().newInstance();
        } catch (Throwable t) {
            Pergament.LOG.warn("Пергамент: FTB Teams есть, но API не подошло: {}", t.toString());
            return NONE;
        }
    }
}
