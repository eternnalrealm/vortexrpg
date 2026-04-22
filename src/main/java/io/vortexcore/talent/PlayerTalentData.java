package io.vortexcore.talent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record PlayerTalentData(
    UUID playerId,
    long revision,
    Map<String, Set<String>> allocatedNodes,
    int talentPoints,
    Instant updatedAt
) {

    public static PlayerTalentData create(UUID playerId) {
        return new PlayerTalentData(playerId, 0L, Map.of(), 0, Instant.now());
    }

    public PlayerTalentData nextRevision() {
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        allocatedNodes.forEach((classId, nodes) -> copied.put(classId, Set.copyOf(nodes)));
        return new PlayerTalentData(
            playerId,
            revision + 1L,
            Map.copyOf(copied),
            talentPoints,
            Instant.now()
        );
    }
}
