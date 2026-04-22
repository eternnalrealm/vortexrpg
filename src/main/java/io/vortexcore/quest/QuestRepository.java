package io.vortexcore.quest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class QuestRepository implements AutoCloseable {

    private final Logger logger;
    private final ObjectMapper objectMapper;
    private final Path dataFolder;
    private final Cache<UUID, PlayerQuestData> localCache;
    private final ExecutorService ioExecutor;
    private final AtomicLong cacheHits;
    private final AtomicLong fileLoads;
    private final AtomicLong saveAttempts;
    private final AtomicLong saveSuccesses;
    private final AtomicLong saveConflicts;
    private final AtomicLong fileFailures;
    private final AtomicReference<Instant> lastFailureAt;
    private final AtomicReference<String> lastFailureMessage;

    public QuestRepository(Logger logger, Path dataFolder, Duration cacheTtl) {
        this.logger = logger;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules();
        this.dataFolder = dataFolder;
        this.localCache = Caffeine.newBuilder()
            .maximumSize(10_000L)
            .expireAfterAccess(cacheTtl)
            .build();
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vortex-quest-io");
            thread.setDaemon(true);
            return thread;
        });
        this.cacheHits = new AtomicLong();
        this.fileLoads = new AtomicLong();
        this.saveAttempts = new AtomicLong();
        this.saveSuccesses = new AtomicLong();
        this.saveConflicts = new AtomicLong();
        this.fileFailures = new AtomicLong();
        this.lastFailureAt = new AtomicReference<>();
        this.lastFailureMessage = new AtomicReference<>();

        try {
            Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create quest data folder: " + dataFolder, exception);
        }
    }

    public CompletableFuture<PlayerQuestData> load(UUID playerId) {
        PlayerQuestData cached = localCache.getIfPresent(playerId);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> loadFromFile(playerId), ioExecutor);
    }

    public CompletableFuture<PlayerQuestData> saveAtomically(PlayerQuestData data) {
        saveAttempts.incrementAndGet();
        PlayerQuestData nextRevision = data.nextRevision();

        return CompletableFuture.supplyAsync(() -> {
            Path file = playerFile(data.playerId());

            if (Files.exists(file)) {
                try {
                    PlayerQuestData existing = readFile(file);
                    if (existing.revision() != data.revision()) {
                        saveConflicts.incrementAndGet();
                        recordFailure("Quest revision conflict for " + data.playerId()
                            + " (disk=" + existing.revision() + ", expected=" + data.revision() + ")");
                        throw new IllegalStateException("Quest revision conflict for " + data.playerId());
                    }
                } catch (IOException exception) {
                    logger.warning("Unable to read existing quest file for revision check: " + exception.getMessage());
                }
            }

            try {
                Path tempFile = dataFolder.resolve(data.playerId() + ".tmp");
                String json = objectMapper.writeValueAsString(nextRevision);
                Files.writeString(tempFile, json, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                fileFailures.incrementAndGet();
                recordFailure("Quest file save failed for " + data.playerId() + ": " + exception.getMessage());
                throw new IllegalStateException("Unable to save quest data for " + data.playerId(), exception);
            }

            localCache.put(data.playerId(), nextRevision);
            saveSuccesses.incrementAndGet();
            return nextRevision;
        }, ioExecutor);
    }

    public void invalidate(UUID playerId) {
        localCache.invalidate(playerId);
    }

    private PlayerQuestData loadFromFile(UUID playerId) {
        Path file = playerFile(playerId);
        if (!Files.exists(file)) {
            PlayerQuestData fresh = PlayerQuestData.create(playerId);
            localCache.put(playerId, fresh);
            fileLoads.incrementAndGet();
            return fresh;
        }

        try {
            PlayerQuestData data = readFile(file);
            localCache.put(playerId, data);
            fileLoads.incrementAndGet();
            return data;
        } catch (IOException exception) {
            fileFailures.incrementAndGet();
            recordFailure("Quest file load failed for " + playerId + ": " + exception.getMessage());
            PlayerQuestData fresh = PlayerQuestData.create(playerId);
            localCache.put(playerId, fresh);
            return fresh;
        }
    }

    private PlayerQuestData readFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return objectMapper.readValue(json, PlayerQuestData.class);
    }

    private Path playerFile(UUID playerId) {
        return dataFolder.resolve(playerId + ".json");
    }

    private void recordFailure(String message) {
        lastFailureAt.set(Instant.now());
        lastFailureMessage.set(message);
        logger.warning(message);
    }

    @Override
    public void close() {
        localCache.invalidateAll();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
