package ru.stef.pergament.server;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FTB Teams 2001.3.x. Загружается только через {@link Teams#detect()}, когда мод установлен. */
public final class FtbTeams implements Teams {
    private Optional<Team> partyOf(ServerPlayer p) {
        var api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) return Optional.empty();
        return api.getManager().getTeamForPlayer(p).filter(Team::isPartyTeam);
    }

    @Override
    public UUID party(ServerPlayer p) {
        return partyOf(p).map(Team::getId).orElse(null);
    }

    @Override
    public List<ServerPlayer> onlineMates(ServerPlayer p) {
        return partyOf(p).map(t -> t.getOnlineMembers().stream().filter(m -> m != p).toList()).orElse(List.of());
    }
}
