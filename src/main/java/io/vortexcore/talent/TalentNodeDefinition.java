package io.vortexcore.talent;

import io.vortexcore.progression.VortexAttribute;
import java.util.List;
import java.util.Map;

public record TalentNodeDefinition(
    String id,
    String displayName,
    String description,
    int row,
    int column,
    List<String> prerequisites,
    int cost,
    Map<VortexAttribute, Double> statBonuses,
    String grantedAbility
) {

    public TalentNodeDefinition {
        prerequisites = List.copyOf(prerequisites);
        statBonuses = Map.copyOf(statBonuses);
    }

    public boolean hasPrerequisites() {
        return !prerequisites.isEmpty();
    }

    public boolean hasGrantedAbility() {
        return grantedAbility != null && !grantedAbility.isBlank();
    }
}
