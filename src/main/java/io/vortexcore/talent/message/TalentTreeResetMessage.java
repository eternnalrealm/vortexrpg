package io.vortexcore.talent.message;

import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record TalentTreeResetMessage(UUID playerId, String className) implements NexusMessage {
}
