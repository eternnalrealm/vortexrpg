package io.vortexcore.world;

import java.util.UUID;

public record ExpeditionCompletion(
    UUID contractId,
    String title,
    String rotationId,
    ExpeditionScoreTier scoreTier,
    double finalScore,
    long clearSeconds,
    int experienceReward
) {
}
