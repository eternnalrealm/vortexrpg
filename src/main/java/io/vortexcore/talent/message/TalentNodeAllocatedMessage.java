package io.vortexcore.talent.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record TalentNodeAllocatedMessage(UUID playerId, String nodeId, String className) implements NexusMessage {
}
