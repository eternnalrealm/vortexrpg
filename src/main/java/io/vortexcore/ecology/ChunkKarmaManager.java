package io.vortexcore.ecology;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.time.Duration;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class ChunkKarmaManager {

    private static final long MAX_TRACKED_CHUNKS = 50_000L;

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final NexusBus nexusBus;
    private final long treeCutThreshold;
    private final long mobKillThreshold;
    private final long oreMineThreshold;
    private final Cache<ChunkKarmaKey, ChunkKarmaSnapshot> chunkMemory;

    public ChunkKarmaManager(
        Logger logger,
        FoliaExecutionFacade scheduler,
        NexusBus nexusBus,
        long treeCutThreshold,
        long mobKillThreshold,
        long oreMineThreshold
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.nexusBus = nexusBus;
        this.treeCutThreshold = treeCutThreshold;
        this.mobKillThreshold = mobKillThreshold;
        this.oreMineThreshold = oreMineThreshold;
        this.chunkMemory = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CHUNKS)
            .expireAfterWrite(Duration.ofDays(7L))
            .build();
    }

    public void recordTreeCut(Location location) {
        mutate(location, ChunkKarmaSnapshot::recordTreeCut);
    }

    public void recordMobKill(Location location) {
        mutate(location, ChunkKarmaSnapshot::recordMobKill);
    }

    public void recordOreMined(Location location) {
        mutate(location, ChunkKarmaSnapshot::recordOreMined);
    }

    public boolean isExtinct(Location location) {
        ChunkKarmaSnapshot snapshot = chunkMemory.getIfPresent(ChunkKarmaKey.from(location));
        return snapshot != null && snapshot.extinct();
    }

    private void mutate(Location location, java.util.function.Consumer<ChunkKarmaSnapshot> mutation) {
        ChunkKarmaKey key = ChunkKarmaKey.from(location);
        ChunkKarmaSnapshot snapshot = chunkMemory.get(key, ignored -> new ChunkKarmaSnapshot());
        mutation.accept(snapshot);
        evaluate(key, snapshot);
    }

    private void evaluate(ChunkKarmaKey key, ChunkKarmaSnapshot snapshot) {
        if (snapshot.extinct()) {
            return;
        }

        boolean thresholdExceeded = snapshot.treesCut() >= treeCutThreshold
            || snapshot.mobsKilled() >= mobKillThreshold
            || snapshot.oresMined() >= oreMineThreshold;

        if (!thresholdExceeded || !snapshot.markExtinct()) {
            return;
        }

        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            logger.warning("Unable to resolve world for extinction event " + key);
            return;
        }

        scheduler.runRegion(world, key.chunkX(), key.chunkZ(), "ecology-extinction-event", () -> {
            nexusBus.publish(new ExtinctionEventMessage(key.worldId(), key.chunkX(), key.chunkZ(), snapshot.snapshotView()));
            logger.info("Extinction event triggered for chunk " + key.chunkX() + "," + key.chunkZ() + " in " + world.getName());
        });
    }
}
