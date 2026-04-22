package io.vortexcore.npc;

import java.util.Map;
import java.util.UUID;

public record NeuralNpcProfile(UUID npcId, String name, String personaPrompt, Map<String, Double> characterStats) {
}
