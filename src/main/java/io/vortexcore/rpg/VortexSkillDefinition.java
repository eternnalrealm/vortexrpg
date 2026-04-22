package io.vortexcore.rpg;

import io.vortexcore.progression.VortexAttribute;
import java.util.Map;
import org.bukkit.Material;

public record VortexSkillDefinition(
    String id,
    VortexClass archetype,
    String displayName,
    String description,
    Material icon,
    int requiredLevel,
    int cost,
    Map<VortexAttribute, Double> permanentBonuses,
    String grantedSpellId
) {

    public VortexSkillDefinition {
        permanentBonuses = Map.copyOf(permanentBonuses);
    }
}
