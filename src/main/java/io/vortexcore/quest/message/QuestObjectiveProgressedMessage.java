package io.vortexcore.quest.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record QuestObjectiveProgressedMessage(
    UUID playerId,
    String questId,
    String objectiveId,
    int current,
    int target
) implements NexusMessage {
}
