package io.vortexcore.quest.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record QuestStartedMessage(UUID playerId, String questId) implements NexusMessage {
}
