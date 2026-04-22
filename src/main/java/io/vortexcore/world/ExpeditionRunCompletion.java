package io.vortexcore.world;

import java.util.Set;
import java.util.UUID;

public record ExpeditionRunCompletion(
    ExpeditionCompletion completion,
    Set<UUID> participants
) {

    public ExpeditionRunCompletion {
        participants = Set.copyOf(participants);
    }
}
