package io.vortexcore.dungeon;

import java.util.List;
import org.bukkit.entity.EntityType;

public record DungeonDefinition(
    String id,
    String displayName,
    String templateName,
    int minLevel,
    int maxPartySize,
    long timeLimitSeconds,
    List<Wave> waves,
    String bossId,
    List<String> rewardItemIds,
    double completionReward
) {

    public record Wave(
        int waveNumber,
        EntityType mobType,
        int mobCount,
        double mobHealth,
        double mobDamageMultiplier,
        long spawnDelayTicks
    ) {
    }
}
