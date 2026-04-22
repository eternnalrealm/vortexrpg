package io.vortexcore.rpg.effects;

import java.util.List;
import java.util.Set;

public record VortexEffectType(
    String id,
    String displayName,
    EffectCategory category,
    int maxStacks,
    int defaultDurationTicks,
    int tickIntervalTicks,
    Set<String> tags,
    List<EffectOperation> onApply,
    List<EffectOperation> onTick,
    List<EffectOperation> onRemove
) {
}

