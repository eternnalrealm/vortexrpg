package io.vortexcore.world;

import java.util.Set;
import java.util.UUID;

public record ExpeditionBreachContext(
    UUID runId,
    UUID partyId,
    UUID expeditionId,
    ExpeditionContract contract,
    Set<UUID> participants,
    boolean leaderOnlyLaunch
) {

    public ExpeditionBreachContext {
        participants = Set.copyOf(participants);
    }
}
