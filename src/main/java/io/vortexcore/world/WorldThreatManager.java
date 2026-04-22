package io.vortexcore.world;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.ecology.ChunkKarmaSnapshot;
import io.vortexcore.ecology.ExtinctionEventMessage;
import io.vortexcore.economy.DynamicMarketManager;
import io.vortexcore.instances.InstanceController;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class WorldThreatManager implements AutoCloseable {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final NexusBus nexusBus;
    private final DynamicMarketManager marketManager;
    private final InstanceController instanceController;
    private final FractureProfileService fractureProfileService;
    private final Duration tickPeriod;
    private final double baseIntensity;
    private final double ecologyIntensityMultiplier;
    private final double passiveEscalation;
    private final double corruptedThreshold;
    private final double overrunThreshold;
    private final double bossThreshold;
    private final String bossTemplate;
    private final List<String> scarcityItems;
    private final List<String> recoveryItems;
    private final long marketPressureUnits;
    private final Map<FractureRegionKey, FractureRegion> activeFractures;

    private VortexTask tickTask;
    private AutoCloseable extinctionSubscription;

    public WorldThreatManager(
        Logger logger,
        FoliaExecutionFacade scheduler,
        NexusBus nexusBus,
        DynamicMarketManager marketManager,
        InstanceController instanceController,
        FractureProfileService fractureProfileService,
        Duration tickPeriod,
        double baseIntensity,
        double ecologyIntensityMultiplier,
        double passiveEscalation,
        double corruptedThreshold,
        double overrunThreshold,
        double bossThreshold,
        String bossTemplate,
        List<String> scarcityItems,
        List<String> recoveryItems,
        long marketPressureUnits
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.nexusBus = nexusBus;
        this.marketManager = marketManager;
        this.instanceController = instanceController;
        this.fractureProfileService = fractureProfileService;
        this.tickPeriod = tickPeriod;
        this.baseIntensity = baseIntensity;
        this.ecologyIntensityMultiplier = ecologyIntensityMultiplier;
        this.passiveEscalation = passiveEscalation;
        this.corruptedThreshold = corruptedThreshold;
        this.overrunThreshold = overrunThreshold;
        this.bossThreshold = bossThreshold;
        this.bossTemplate = bossTemplate;
        this.scarcityItems = List.copyOf(scarcityItems);
        this.recoveryItems = List.copyOf(recoveryItems);
        this.marketPressureUnits = marketPressureUnits;
        this.activeFractures = new ConcurrentHashMap<>();
    }

    public void start() {
        if (tickTask != null) {
            return;
        }

        this.extinctionSubscription = nexusBus.subscribe(ExtinctionEventMessage.class, this::handleExtinctionEvent);
        long periodTicks = Math.max(20L, tickPeriod.toMillis() / 50L);
        this.tickTask = scheduler.runGlobalTimer("world-threat-tick", periodTicks, periodTicks, task -> tickActiveFractures());
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        if (extinctionSubscription != null) {
            try {
                extinctionSubscription.close();
            } catch (Exception exception) {
                logger.warning("Unable to close world threat subscription: " + exception.getMessage());
            }
            extinctionSubscription = null;
        }

        activeFractures.clear();
    }

    public CompletableFuture<FractureRegionSnapshot> seedFracture(Location location, ThreatSource source, double intensity) {
        return seedFracture(location, source, intensity, null, null);
    }

    public CompletableFuture<FractureRegionSnapshot> seedFracture(
        Location location,
        ThreatSource source,
        double intensity,
        String profileId,
        String fractureName
    ) {
        CompletableFuture<FractureRegionSnapshot> future = new CompletableFuture<>();
        World world = location.getWorld();
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("Location must belong to a world."));
            return future;
        }

        FractureProfile profile = profileId == null ? fractureProfileService.defaultProfile() : fractureProfileService.resolve(profileId);
        scheduler.runRegion(location, "fracture-seed", () ->
            future.complete(intensify(FractureRegionKey.from(location), source, intensity, profile, fractureName))
        );
        return future;
    }

    public CompletableFuture<FractureRegionSnapshot> contributeSuppression(Location location, double effort) {
        CompletableFuture<FractureRegionSnapshot> future = new CompletableFuture<>();
        World world = location.getWorld();
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("Location must belong to a world."));
            return future;
        }

        scheduler.runRegion(location, "fracture-suppress", () -> future.complete(suppress(FractureRegionKey.from(location), effort)));
        return future;
    }

    public CompletableFuture<FractureBossInstance> openBossBreach(Location location, UUID expeditionId, Collection<? extends Player> players) {
        World world = location.getWorld();
        if (world == null) {
            return failedFuture(new IllegalArgumentException("Location must belong to a world."));
        }

        FractureRegionKey key = FractureRegionKey.from(location);
        FractureRegion region = activeFractures.get(key);
        if (region == null) {
            return failedFuture(new IllegalStateException("No active fracture exists at this location."));
        }

        if (!region.tryClaimBossExpedition(expeditionId)) {
            return failedFuture(new IllegalStateException("Fracture is not ready for a boss expedition."));
        }

        FractureRegionSnapshot snapshot = region.snapshot();
        return instanceController.createInstance(bossTemplate, expeditionId, players)
            .thenApply(instance -> new FractureBossInstance(expeditionId, snapshot, instance))
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    region.releaseBossExpedition(expeditionId);
                }
            });
    }

    public CompletableFuture<FractureRegionSnapshot> resolveBossBreach(Location location, UUID expeditionId, boolean purified) {
        CompletableFuture<FractureRegionSnapshot> future = new CompletableFuture<>();
        World world = location.getWorld();
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("Location must belong to a world."));
            return future;
        }

        scheduler.runRegion(location, "fracture-resolve-boss", () -> {
            FractureRegionKey key = FractureRegionKey.from(location);
            FractureRegion region = activeFractures.get(key);
            if (region == null) {
                future.completeExceptionally(new IllegalStateException("No active fracture exists at this location."));
                return;
            }

            if (purified) {
                FractureRegionSnapshot snapshot = purify(key, region);
                future.complete(snapshot);
                return;
            }

            region.releaseBossExpedition(expeditionId);
            region.intensify(passiveEscalation, corruptedThreshold, overrunThreshold, bossThreshold);
            future.complete(region.snapshot());
        });
        return future;
    }

    public List<FractureRegionSnapshot> activeFractures() {
        return activeFractures.values().stream()
            .map(FractureRegion::snapshot)
            .sorted((left, right) -> {
                int worldCompare = left.key().worldId().compareTo(right.key().worldId());
                if (worldCompare != 0) {
                    return worldCompare;
                }
                int chunkXCompare = Integer.compare(left.key().chunkX(), right.key().chunkX());
                if (chunkXCompare != 0) {
                    return chunkXCompare;
                }
                return Integer.compare(left.key().chunkZ(), right.key().chunkZ());
            })
            .toList();
    }

    public Optional<FractureRegionSnapshot> fracture(FractureRegionKey key) {
        FractureRegion region = activeFractures.get(key);
        return region == null ? Optional.empty() : Optional.of(region.snapshot());
    }

    public Optional<FractureRegionSnapshot> fractureAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return Optional.empty();
        }

        return fracture(new FractureRegionKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
    }

    @Override
    public void close() {
        stop();
    }

    private CompletionStage<Void> handleExtinctionEvent(ExtinctionEventMessage message) {
        double intensity = intensityFromEcology(message.snapshot());
        intensify(
            new FractureRegionKey(message.worldId(), message.chunkX(), message.chunkZ()),
            ThreatSource.ECOLOGICAL_COLLAPSE,
            intensity,
            fractureProfileService.defaultProfile(),
            null
        );
        return CompletableFuture.completedFuture(null);
    }

    private void tickActiveFractures() {
        List<FractureRegionKey> keys = new ArrayList<>(activeFractures.keySet());
        for (FractureRegionKey key : keys) {
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }

            scheduler.runRegion(world, key.chunkX(), key.chunkZ(), "fracture-tick", () -> tickRegion(key));
        }
    }

    private void tickRegion(FractureRegionKey key) {
        FractureRegion region = activeFractures.get(key);
        if (region == null) {
            return;
        }

        FractureState previousState = region.state();
        FractureState currentState = region.tick(passiveEscalation, corruptedThreshold, overrunThreshold, bossThreshold);
        FractureRegionSnapshot snapshot = region.snapshot();
        handleStateOutcome(key, region, previousState, currentState, snapshot);
    }

    private FractureRegionSnapshot intensify(
        FractureRegionKey key,
        ThreatSource source,
        double intensity,
        FractureProfile profile,
        String requestedName
    ) {
        FractureRegion region = activeFractures.computeIfAbsent(
            key,
            ignored -> new FractureRegion(
                key,
                source,
                selectModifiers(key, source),
                System.currentTimeMillis(),
                profile == null ? fractureProfileService.defaultProfile() : profile,
                normalizeFractureName(requestedName, key, profile == null ? fractureProfileService.defaultProfile() : profile)
            )
        );
        region.updateProfileAndName(profile == null ? fractureProfileService.defaultProfile() : profile, normalizeFractureName(requestedName, key, profile));

        FractureState previousState = region.state();
        FractureState currentState = region.intensify(intensity, corruptedThreshold, overrunThreshold, bossThreshold);
        FractureRegionSnapshot snapshot = region.snapshot();
        handleStateOutcome(key, region, previousState, currentState, snapshot);
        return snapshot;
    }

    private FractureRegionSnapshot suppress(FractureRegionKey key, double effort) {
        FractureRegion region = activeFractures.get(key);
        if (region == null) {
            FractureProfile profile = fractureProfileService.defaultProfile();
            return new FractureRegionSnapshot(
                key,
                normalizeFractureName(null, key, profile),
                profile,
                ThreatSource.ADMIN_FORCED,
                FractureState.PURIFIED,
                Set.of(),
                0.0D,
                0.0D,
                0L,
                0L
            );
        }

        FractureState previousState = region.state();
        FractureState currentState = region.suppress(effort, corruptedThreshold, overrunThreshold, bossThreshold);
        FractureRegionSnapshot snapshot = region.snapshot();
        handleStateOutcome(key, region, previousState, currentState, snapshot);
        return snapshot;
    }

    private FractureRegionSnapshot purify(FractureRegionKey key, FractureRegion region) {
        FractureState previousState = region.state();
        region.purify();
        FractureRegionSnapshot snapshot = region.snapshot();
        handleStateOutcome(key, region, previousState, snapshot.state(), snapshot);
        return snapshot;
    }

    private void handleStateOutcome(
        FractureRegionKey key,
        FractureRegion region,
        FractureState previousState,
        FractureState currentState,
        FractureRegionSnapshot snapshot
    ) {
        if (currentState == FractureState.PURIFIED) {
            activeFractures.remove(key, region);
            applyRecoveryPressure();
        } else if (currentState.severity() > previousState.severity()) {
            applyEscalationPressure(currentState);
        }

        if (currentState != previousState) {
            nexusBus.publish(new FractureStateChangedMessage(snapshot, previousState, currentState));
            logger.info(
                "Fracture " + key.worldId() + ":" + key.chunkX() + "," + key.chunkZ()
                    + " transitioned from " + previousState + " to " + currentState
                    + " using profile " + snapshot.profile().id()
            );
        }
    }

    private String normalizeFractureName(String rawName, FractureRegionKey key, FractureProfile profile) {
        String token = rawName == null ? "" : rawName.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '-').replace('_', '-');
        token = token.replaceAll("[^a-z0-9-]", "");
        while (token.contains("--")) {
            token = token.replace("--", "-");
        }
        if (token.startsWith("-")) {
            token = token.substring(1);
        }
        if (token.endsWith("-")) {
            token = token.substring(0, token.length() - 1);
        }
        if (token.isBlank()) {
            String profileToken = profile == null ? "wild" : profile.id();
            token = "fracture-" + profileToken + "-x" + key.chunkX() + "-z" + key.chunkZ();
        }
        return token;
    }

    private double intensityFromEcology(ChunkKarmaSnapshot.SnapshotView snapshot) {
        double ecologicalScore = (snapshot.treesCut() * 0.35D) + (snapshot.mobsKilled() * 0.20D) + (snapshot.oresMined() * 0.45D);
        return baseIntensity + (ecologicalScore * ecologyIntensityMultiplier / 32.0D);
    }

    private void applyEscalationPressure(FractureState state) {
        long pressure = switch (state) {
            case UNSTABLE -> 0L;
            case CORRUPTED -> marketPressureUnits;
            case OVERRUN -> marketPressureUnits * 2L;
            case BOSS_BREACH -> marketPressureUnits * 3L;
            case PURIFIED -> 0L;
        };

        if (pressure <= 0L) {
            return;
        }

        scarcityItems.forEach(itemId -> marketManager.recordPurchase(itemId, pressure));
    }

    private void applyRecoveryPressure() {
        recoveryItems.forEach(itemId -> marketManager.recordSale(itemId, marketPressureUnits));
    }

    private static Set<ThreatModifier> selectModifiers(FractureRegionKey key, ThreatSource source) {
        ThreatModifier[] values = ThreatModifier.values();
        long seed = key.worldId().getMostSignificantBits()
            ^ key.worldId().getLeastSignificantBits()
            ^ (((long) key.chunkX()) << 32)
            ^ key.chunkZ()
            ^ ((long) source.ordinal() * 0x9E3779B97F4A7C15L);

        LinkedHashSet<ThreatModifier> selected = new LinkedHashSet<>();
        int firstIndex = Math.floorMod(Long.hashCode(seed), values.length);
        int secondIndex = Math.floorMod(Long.hashCode(seed ^ 0x5DEECE66DL), values.length);
        selected.add(values[firstIndex]);
        selected.add(values[secondIndex]);

        if (selected.size() < 2) {
            selected.add(values[(firstIndex + 1) % values.length]);
        }

        return Set.copyOf(selected);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private static final class FractureRegion {

        private final FractureRegionKey key;
        private final ThreatSource source;
        private final Set<ThreatModifier> modifiers;
        private final long createdAtEpochMillis;
        private FractureProfile profile;
        private String name;
        private FractureState state;
        private double threatScore;
        private double totalSuppression;
        private long lastTransitionAtEpochMillis;
        private UUID activeBossExpeditionId;

        private FractureRegion(
            FractureRegionKey key,
            ThreatSource source,
            Set<ThreatModifier> modifiers,
            long now,
            FractureProfile profile,
            String name
        ) {
            this.key = key;
            this.source = source;
            this.modifiers = Set.copyOf(modifiers);
            this.createdAtEpochMillis = now;
            this.profile = profile;
            this.name = name;
            this.state = FractureState.PURIFIED;
            this.threatScore = 0.0D;
            this.totalSuppression = 0.0D;
            this.lastTransitionAtEpochMillis = now;
        }

        private synchronized FractureState state() {
            return state;
        }

        private synchronized FractureState intensify(double amount, double corruptedThreshold, double overrunThreshold, double bossThreshold) {
            threatScore = Math.max(0.0D, threatScore + amount);
            return recalculateState(corruptedThreshold, overrunThreshold, bossThreshold);
        }

        private synchronized FractureState suppress(double effort, double corruptedThreshold, double overrunThreshold, double bossThreshold) {
            totalSuppression += Math.max(0.0D, effort);
            threatScore = Math.max(0.0D, threatScore - Math.max(0.0D, effort));
            return recalculateState(corruptedThreshold, overrunThreshold, bossThreshold);
        }

        private synchronized FractureState tick(double passiveEscalation, double corruptedThreshold, double overrunThreshold, double bossThreshold) {
            threatScore = Math.max(0.0D, threatScore + passiveEscalation);
            return recalculateState(corruptedThreshold, overrunThreshold, bossThreshold);
        }

        private synchronized void purify() {
            threatScore = 0.0D;
            activeBossExpeditionId = null;
            transitionTo(FractureState.PURIFIED);
        }

        private synchronized boolean tryClaimBossExpedition(UUID expeditionId) {
            if (state != FractureState.BOSS_BREACH || activeBossExpeditionId != null) {
                return false;
            }
            activeBossExpeditionId = expeditionId;
            return true;
        }

        private synchronized void releaseBossExpedition(UUID expeditionId) {
            if (expeditionId.equals(activeBossExpeditionId)) {
                activeBossExpeditionId = null;
            }
        }

        private synchronized void updateProfileAndName(FractureProfile nextProfile, String nextName) {
            if (nextProfile != null) {
                this.profile = nextProfile;
            }
            if (nextName != null && !nextName.isBlank()) {
                this.name = nextName;
            }
        }

        private synchronized FractureRegionSnapshot snapshot() {
            return new FractureRegionSnapshot(
                key,
                name,
                profile,
                source,
                state,
                modifiers,
                threatScore,
                totalSuppression,
                createdAtEpochMillis,
                lastTransitionAtEpochMillis
            );
        }

        private FractureState recalculateState(double corruptedThreshold, double overrunThreshold, double bossThreshold) {
            FractureState nextState;
            if (threatScore <= 0.0D) {
                nextState = FractureState.PURIFIED;
            } else if (threatScore >= bossThreshold) {
                nextState = FractureState.BOSS_BREACH;
            } else if (threatScore >= overrunThreshold) {
                nextState = FractureState.OVERRUN;
            } else if (threatScore >= corruptedThreshold) {
                nextState = FractureState.CORRUPTED;
            } else {
                nextState = FractureState.UNSTABLE;
            }

            transitionTo(nextState);
            return state;
        }

        private synchronized void transitionTo(FractureState nextState) {
            if (state == nextState) {
                return;
            }
            state = nextState;
            lastTransitionAtEpochMillis = System.currentTimeMillis();
            if (nextState != FractureState.BOSS_BREACH) {
                activeBossExpeditionId = null;
            }
        }
    }
}
