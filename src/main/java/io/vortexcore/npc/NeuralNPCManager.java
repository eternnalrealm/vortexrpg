package io.vortexcore.npc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class NeuralNPCManager implements Listener, AutoCloseable {

    private static final long REQUEST_COOLDOWN_MS = 3_000L;
    private static final int MAX_MESSAGE_LENGTH = 256;

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final LLMEndpoint endpoint;
    private final int maxMemoryTurns;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, NeuralNpcProfile> profiles;
    private final ConcurrentMap<ConversationKey, NpcConversationMemory> conversations;
    private final ConcurrentMap<UUID, Long> lastRequestAt;

    public NeuralNPCManager(Logger logger, FoliaExecutionFacade scheduler, LLMEndpoint endpoint, int maxMemoryTurns) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.endpoint = endpoint;
        this.maxMemoryTurns = maxMemoryTurns;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(endpoint.timeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.profiles = new ConcurrentHashMap<>();
        this.conversations = new ConcurrentHashMap<>();
        this.lastRequestAt = new ConcurrentHashMap<>();
    }

    public void registerNpc(NeuralNpcProfile profile) {
        profiles.put(profile.npcId(), profile);
    }

    public CompletableFuture<String> requestReply(UUID npcId, UUID playerId, String playerMessage) {
        NeuralNpcProfile profile = profiles.get(npcId);
        if (profile == null) {
            return failedFuture(new IllegalArgumentException("Unknown NPC profile " + npcId));
        }

        long now = System.currentTimeMillis();
        Long last = lastRequestAt.get(playerId);
        if (last != null && now - last < REQUEST_COOLDOWN_MS) {
            return CompletableFuture.completedFuture("...");
        }
        lastRequestAt.put(playerId, now);

        String sanitizedMessage = playerMessage.length() > MAX_MESSAGE_LENGTH
            ? playerMessage.substring(0, MAX_MESSAGE_LENGTH)
            : playerMessage;

        ConversationKey key = new ConversationKey(npcId, playerId);
        NpcConversationMemory memory = conversations.computeIfAbsent(key, ignored -> new NpcConversationMemory(maxMemoryTurns));
        memory.append("user", sanitizedMessage);

        CompletableFuture<String> replyFuture = new CompletableFuture<>();
        scheduler.runAsync("npc-llm-request-" + npcId, () -> {
            try {
                HttpRequest request = buildRequest(profile, memory);
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            logger.warning("NPC LLM request failed: " + error.getMessage());
                            replyFuture.completeExceptionally(error);
                            return;
                        }

                        try {
                            String content = parseResponse(response.body());
                            memory.append("assistant", content);
                            replyFuture.complete(content);
                        } catch (IOException exception) {
                            replyFuture.completeExceptionally(exception);
                        }
                    });
            } catch (Exception exception) {
                replyFuture.completeExceptionally(exception);
            }
        });

        return replyFuture;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastRequestAt.remove(playerId);
        conversations.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    @Override
    public void close() {
        httpClient.close();
    }

    public void clearConversation(UUID npcId, UUID playerId) {
        conversations.remove(new ConversationKey(npcId, playerId));
    }

    private HttpRequest buildRequest(NeuralNpcProfile profile, NpcConversationMemory memory) throws JsonProcessingException {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
            "role", "system",
            "content", buildSystemPrompt(profile)
        ));

        for (NpcConversationMemory.ConversationTurn turn : memory.snapshot()) {
            messages.add(Map.of(
                "role", turn.role(),
                "content", turn.content()
            ));
        }

        String payload = objectMapper.writeValueAsString(Map.of(
            "model", endpoint.model(),
            "stream", false,
            "messages", messages
        ));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint.baseUrl()))
            .timeout(endpoint.timeout())
            .header("Content-Type", "application/json");

        if (endpoint.providerType() == LLMProviderType.OPENAI && endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }

        return builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private String parseResponse(String rawBody) throws IOException {
        JsonNode root = objectMapper.readTree(rawBody);
        if (endpoint.providerType() == LLMProviderType.OLLAMA) {
            String content = root.path("message").path("content").asText("");
            if (!content.isBlank()) {
                return content;
            }
            return root.path("response").asText("");
        }

        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (!content.isBlank()) {
                return content;
            }
        }
        return rawBody;
    }

    private String buildSystemPrompt(NeuralNpcProfile profile) {
        return """
            You are %s.
            Persona: %s
            Character stats: %s
            Stay in character, keep continuity with the prior conversation, and answer as an MMO NPC.
            """.formatted(profile.name(), profile.personaPrompt(), profile.characterStats());
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private record ConversationKey(UUID npcId, UUID playerId) {
    }
}
