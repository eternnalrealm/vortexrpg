package io.vortexcore.world;

import java.util.UUID;

public record ExpeditionLeaderboardEntry(
    UUID playerId,
    String playerName,
    String rotationId,
    double bestScore,
    long clearSeconds,
    ExpeditionScoreTier scoreTier,
    long completedAtEpochMillis
) {
}
