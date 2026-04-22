package io.vortexcore.persistence.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PlayerSnapshot(
    UUID playerId,
    long revision,
    int level,
    long experience,
    Map<String, Double> stats,
    Map<String, String> metadata,
    Instant updatedAt
) {

    public static PlayerSnapshot create(UUID playerId) {
        return new PlayerSnapshot(playerId, 0L, 1, 0L, Map.of(), Map.of(), Instant.now());
    }

    public PlayerSnapshot nextRevision() {
        return new PlayerSnapshot(
            playerId,
            revision + 1L,
            level,
            experience,
            Map.copyOf(stats),
            Map.copyOf(metadata),
            Instant.now()
        );
    }
}
