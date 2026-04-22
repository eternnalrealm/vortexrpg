package io.vortexcore.world;

import java.util.UUID;
import java.util.Set;

public record ExpeditionContract(
    UUID contractId,
    FractureRegionSnapshot fracture,
    ExpeditionContractTier tier,
    String title,
    String objective,
    int recommendedPartySize,
    String rewardPreview,
    int targetKills,
    long timeLimitSeconds,
    double scoreMultiplier,
    String rotationId,
    boolean breachFinale,
    Set<ThreatModifier> activeModifiers
) {

    public ExpeditionContract {
        activeModifiers = Set.copyOf(activeModifiers);
    }
}
