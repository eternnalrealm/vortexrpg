package io.vortexcore.world;

import java.util.Set;

public record FractureRegionSnapshot(
    FractureRegionKey key,
    String name,
    FractureProfile profile,
    ThreatSource source,
    FractureState state,
    Set<ThreatModifier> modifiers,
    double threatScore,
    double totalSuppression,
    long createdAtEpochMillis,
    long lastTransitionAtEpochMillis
) {

    public FractureRegionSnapshot {
        name = name == null ? "" : name;
        modifiers = Set.copyOf(modifiers);
    }
}
