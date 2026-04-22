package io.vortexcore.boss;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

public record BossDefinition(
    String id,
    String displayName,
    EntityType entityType,
    double maxHealth,
    double damageMultiplier,
    double speedMultiplier,
    int minLevel,
    long spawnCooldownSeconds,
    long despawnAfterSeconds,
    List<BossPhase> phases,
    List<BossLootProfile> lootProfiles,
    Material summonItem,
    int arenaRadius
) {

    public record BossPhase(
        double threshold,
        List<String> abilities,
        double speedMultiplier,
        double damageMultiplier
    ) {
    }

    public record BossLootProfile(
        String itemId,
        double baseValue,
        double scalePerLevel,
        double spread
    ) {
    }
}
