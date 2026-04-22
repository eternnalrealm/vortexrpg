package io.vortexcore.world;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.EntityType;

public record FractureProfile(
    String id,
    String displayName,
    Set<EntityType> mobTypes,
    EntityType bossType,
    double mobHealthMultiplier,
    double mobDamageMultiplier,
    double mobArmorBonus,
    double bossHealthMultiplier,
    double bossDamageMultiplier,
    List<String> mobEffects,
    List<String> bossEffects
) {

    public FractureProfile {
        mobTypes = Set.copyOf(mobTypes);
        mobEffects = List.copyOf(mobEffects);
        bossEffects = List.copyOf(bossEffects);
    }

    public boolean allowsMob(EntityType entityType) {
        return mobTypes.isEmpty() || mobTypes.contains(entityType);
    }

    public static FractureProfile fallback() {
        return new FractureProfile(
            "wild",
            "Wild Fracture",
            Set.of(),
            EntityType.WITHER_SKELETON,
            1.0D,
            1.0D,
            0.0D,
            1.0D,
            1.0D,
            List.of(),
            List.of()
        );
    }

    public String normalizedDisplayName() {
        return displayName.toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
