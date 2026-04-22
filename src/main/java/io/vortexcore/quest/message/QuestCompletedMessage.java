package io.vortexcore.quest.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record QuestCompletedMessage(UUID playerId, String questId) implements NexusMessage {
}
