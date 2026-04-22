package io.vortexcore.rpg.effects;

import org.bukkit.Particle;
import org.bukkit.Sound;

public record EffectOperation(
    EffectOperationType type,
    double base,
    double stackScale,
    double potencyScale,
    String message,
    Particle particle,
    int particleCount,
    double offsetX,
    double offsetY,
    double offsetZ,
    double extra,
    Sound sound,
    float volume,
    float pitch
) {
}

