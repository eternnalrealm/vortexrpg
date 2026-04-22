package io.vortexcore.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vortexcore.persistence.model.PlayerSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class PlayerDataRepository implements AutoCloseable {

    private final Logger logger;
    private final ObjectMapper objectMapper;
    private final Path dataFolder;
    private final Cache<UUID, PlayerSnapshot> localCache;
    private final ExecutorService ioExecutor;
    private final AtomicLong localCacheHits;
    private final AtomicLong fileLoads;
    private final AtomicLong saveAttempts;
    private final AtomicLong saveSuccesses;
    private final AtomicLong saveConflicts;
    private final AtomicLong fileFailures;
    private final AtomicReference<Instant> lastSuccessfulLoadAt;
    private final AtomicReference<Instant> lastSuccessfulSaveAt;
    private final AtomicReference<Instant> lastFailureAt;
    private final AtomicReference<String> lastFailureMessage;

    public PlayerDataRepository(Logger logger, Path dataFolder, Duration cacheTtl) {
        this.logger = logger;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules();
        this.dataFolder = dataFolder;
        this.localCache = Caffeine.newBuilder()
            .maximumSize(50_000L)
            .expireAfterAccess(cacheTtl)
            .build();
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vortex-file-io");
            thread.setDaemon(true);
            return thread;
        });
        this.localCacheHits = new AtomicLong();
        this.fileLoads = new AtomicLong();
        this.saveAttempts = new AtomicLong();
        this.saveSuccesses = new AtomicLong();
        this.saveConflicts = new AtomicLong();
        this.fileFailures = new AtomicLong();
        this.lastSuccessfulLoadAt = new AtomicReference<>();
        this.lastSuccessfulSaveAt = new AtomicReference<>();
        this.lastFailureAt = new AtomicReference<>();
        this.lastFailureMessage = new AtomicReference<>();

        try {
            Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create player data folder: " + dataFolder, exception);
        }
    }

    public CompletableFuture<PlayerSnapshot> loadPlayer(UUID playerId) {
        PlayerSnapshot cached = localCache.getIfPresent(playerId);
        if (cached != null) {
            localCacheHits.incrementAndGet();
            lastSuccessfulLoadAt.set(Instant.now());
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> loadFromFile(playerId), ioExecutor);
    }

    public CompletableFuture<PlayerSnapshot> saveAtomically(PlayerSnapshot snapshot) {
        saveAttempts.incrementAndGet();
        PlayerSnapshot nextRevision = snapshot.nextRevision();

        return CompletableFuture.supplyAsync(() -> {
            Path playerFile = playerFile(snapshot.playerId());

            if (Files.exists(playerFile)) {
                try {
                    PlayerSnapshot existing = readFile(playerFile);
                    if (existing.revision() != snapshot.revision()) {
                        saveConflicts.incrementAndGet();
                        recordFailure("Revision conflict while saving " + snapshot.playerId()
                            + " (disk=" + existing.revision() + ", expected=" + snapshot.revision() + ")");
                        throw new IllegalStateException("Revision conflict while saving " + snapshot.playerId());
                    }
                } catch (IOException exception) {
                    logger.warning("Unable to read existing player file for revision check: " + exception.getMessage());
                }
            }

            try {
                Path tempFile = dataFolder.resolve(snapshot.playerId() + ".tmp");
                String json = objectMapper.writeValueAsString(nextRevision);
                Files.writeString(tempFile, json, StandardCharsets.UTF_8);
                Files.move(tempFile, playerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                fileFailures.incrementAndGet();
                recordFailure("File save failed for " + snapshot.playerId() + ": " + exception.getMessage());
                throw new IllegalStateException("Unable to save player data for " + snapshot.playerId(), exception);
            }

            localCache.put(snapshot.playerId(), nextRevision);
            saveSuccesses.incrementAndGet();
            lastSuccessfulSaveAt.set(Instant.now());
            return nextRevision;
        }, ioExecutor);
    }

    public CompletableFuture<Void> invalidate(UUID playerId) {
        localCache.invalidate(playerId);
        return CompletableFuture.completedFuture(null);
    }

    public PersistenceStatusSnapshot statusSnapshot() {
        return new PersistenceStatusSnapshot(
            "FILE",
            localCache.estimatedSize(),
            localCacheHits.get(),
            0L,
            0L,
            fileLoads.get(),
            saveAttempts.get(),
            saveSuccesses.get(),
            saveConflicts.get(),
            0L,
            fileFailures.get(),
            lastSuccessfulLoadAt.get(),
            lastSuccessfulSaveAt.get(),
            lastFailureAt.get(),
            lastFailureMessage.get()
        );
    }

    private PlayerSnapshot loadFromFile(UUID playerId) {
        Path playerFile = playerFile(playerId);
        if (!Files.exists(playerFile)) {
            PlayerSnapshot fresh = PlayerSnapshot.create(playerId);
            localCache.put(playerId, fresh);
            fileLoads.incrementAndGet();
            lastSuccessfulLoadAt.set(Instant.now());
            return fresh;
        }

        try {
            PlayerSnapshot snapshot = readFile(playerFile);
            localCache.put(playerId, snapshot);
            fileLoads.incrementAndGet();
            lastSuccessfulLoadAt.set(Instant.now());
            return snapshot;
        } catch (IOException exception) {
            fileFailures.incrementAndGet();
            recordFailure("File load failed for " + playerId + ": " + exception.getMessage());
            PlayerSnapshot fresh = PlayerSnapshot.create(playerId);
            localCache.put(playerId, fresh);
            return fresh;
        }
    }

    private PlayerSnapshot readFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return objectMapper.readValue(json, PlayerSnapshot.class);
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

    public record PersistenceStatusSnapshot(
        String mode,
        long localCacheEntries,
        long localCacheHits,
        long redisCacheHits,
        long redisCacheMisses,
        long fileLoads,
        long saveAttempts,
        long saveSuccesses,
        long saveConflicts,
        long redisFailures,
        long fileFailures,
        Instant lastSuccessfulLoadAt,
        Instant lastSuccessfulSaveAt,
        Instant lastFailureAt,
        String lastFailureMessage
    ) {

        public List<String> describeLines() {
            List<String> lines = new ArrayList<>();
            lines.add("Persistence mode: " + mode + " | Local cache entries: " + localCacheEntries);
            lines.add(
                "Loads -> local hits: " + localCacheHits
                    + ", file reads: " + fileLoads
            );
            lines.add(
                "Saves -> successful: " + saveSuccesses
                    + "/" + saveAttempts
                    + ", conflicts: " + saveConflicts
                    + ", file failures: " + fileFailures
            );
            lines.add("Last successful load: " + formatInstant(lastSuccessfulLoadAt));
            lines.add("Last successful save: " + formatInstant(lastSuccessfulSaveAt));
            if (lastFailureMessage != null && !lastFailureMessage.isBlank()) {
                lines.add("Last failure: " + lastFailureMessage + " @ " + formatInstant(lastFailureAt));
            }
            return List.copyOf(lines);
        }

        private static String formatInstant(Instant instant) {
            return instant == null ? "never" : instant.toString();
        }
    }
}
