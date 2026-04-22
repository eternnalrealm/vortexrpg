package io.vortexcore.quest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlayerQuestData(
    UUID playerId,
    long revision,
    Map<String, QuestProgress> quests,
    Map<String, Instant> cooldowns,
    List<String> completedQuestLog,
    Instant updatedAt
) {

    public static PlayerQuestData create(UUID playerId) {
        return new PlayerQuestData(playerId, 0L, Map.of(), Map.of(), List.of(), Instant.now());
    }

    public PlayerQuestData nextRevision() {
        return new PlayerQuestData(
            playerId,
            revision + 1L,
            Map.copyOf(quests),
            Map.copyOf(cooldowns),
            List.copyOf(completedQuestLog),
            Instant.now()
        );
    }
}
