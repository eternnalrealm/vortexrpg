package io.vortexcore.world;

import java.util.UUID;

public record ExpeditionRunSnapshot(
    UUID runId,
    UUID partyId,
    UUID contractId,
    String title,
    String objective,
    String rotationId,
    int participantCount,
    boolean partyRun,
    boolean breachFinale,
    boolean breachLaunched,
    int killCount,
    int targetKills,
    long elapsedSeconds,
    long timeLimitSeconds,
    double score,
    double multiplier
) {
}
