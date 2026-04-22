package io.vortexcore.nexus.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record PlayerLevelUpMessage(UUID playerId, int previousLevel, int newLevel) implements NexusMessage {
}
