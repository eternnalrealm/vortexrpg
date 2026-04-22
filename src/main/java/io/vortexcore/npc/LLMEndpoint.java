package io.vortexcore.npc;

import java.time.Duration;

public record LLMEndpoint(
    LLMProviderType providerType,
    String baseUrl,
    String apiKey,
    String model,
    Duration timeout
) {
}
