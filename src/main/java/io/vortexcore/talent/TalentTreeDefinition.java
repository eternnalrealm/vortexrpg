package io.vortexcore.talent;

import io.vortexcore.rpg.VortexClass;
import java.util.Map;
import java.util.Optional;

public record TalentTreeDefinition(
    VortexClass vortexClass,
    Map<String, TalentNodeDefinition> nodes
) {

    public TalentTreeDefinition {
        nodes = Map.copyOf(nodes);
    }

    public Optional<TalentNodeDefinition> find(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }
}
