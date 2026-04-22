package io.vortexcore.talent;

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class TalentRepository implements AutoCloseable {

    private final Logger logger;
    private final ObjectMapper objectMapper;
    private final Path dataFolder;
    private final Cache<UUID, PlayerTalentData> localCache;
    private final ExecutorService ioExecutor;

    public TalentRepository(Logger logger, Path dataFolder, Duration cacheTtl) {
        this.logger = logger;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules();
        this.dataFolder = dataFolder;
        this.localCache = Caffeine.newBuilder()
            .maximumSize(10_000L)
            .expireAfterAccess(cacheTtl)
            .build();
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vortex-talent-io");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create talent data folder: " + dataFolder, exception);
        }
    }

    public CompletableFuture<PlayerTalentData> load(UUID playerId) {
        PlayerTalentData cached = localCache.getIfPresent(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> loadFromFile(playerId), ioExecutor);
    }

    public CompletableFuture<PlayerTalentData> saveAtomically(PlayerTalentData data) {
        PlayerTalentData nextRevision = data.nextRevision();

        return CompletableFuture.supplyAsync(() -> {
            Path file = playerFile(data.playerId());

            if (Files.exists(file)) {
                try {
                    PlayerTalentData existing = readFile(file);
                    if (existing.revision() != data.revision()) {
                        throw new IllegalStateException("Talent revision conflict for " + data.playerId());
                    }
                } catch (IOException exception) {
                    logger.warning("Unable to read existing talent file for revision check: " + exception.getMessage());
                }
            }

            try {
                Path tempFile = dataFolder.resolve(data.playerId() + ".tmp");
                String json = objectMapper.writeValueAsString(nextRevision);
                Files.writeString(tempFile, json, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to save talent data for " + data.playerId(), exception);
            }

            localCache.put(data.playerId(), nextRevision);
            return nextRevision;
        }, ioExecutor);
    }

    public void invalidate(UUID playerId) {
        localCache.invalidate(playerId);
    }

    private PlayerTalentData loadFromFile(UUID playerId) {
        Path file = playerFile(playerId);
        if (!Files.exists(file)) {
            PlayerTalentData fresh = PlayerTalentData.create(playerId);
            localCache.put(playerId, fresh);
            return fresh;
        }

        try {
            PlayerTalentData data = readFile(file);
            localCache.put(playerId, data);
            return data;
        } catch (IOException exception) {
            logger.warning("Talent file load failed for " + playerId + ": " + exception.getMessage());
            PlayerTalentData fresh = PlayerTalentData.create(playerId);
            localCache.put(playerId, fresh);
            return fresh;
        }
    }

    private PlayerTalentData readFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return objectMapper.readValue(json, PlayerTalentData.class);
    }

    private Path playerFile(UUID playerId) {
        return dataFolder.resolve(playerId + ".json");
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
