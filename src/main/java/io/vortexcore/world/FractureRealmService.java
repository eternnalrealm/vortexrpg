package io.vortexcore.world;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

public final class FractureRealmService {

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final WorldThreatManager worldThreatManager;
    private final FractureProfileService fractureProfileService;
    private final Path templateRoot;
    private final Path liveWorldRoot;
    private final boolean enabled;
    private final String worldName;
    private final String templateName;
    private final World.Environment environment;
    private final long seed;
    private final boolean generateStructures;
    private final Difficulty difficulty;
    private final long fixedTime;
    private final boolean storm;
    private final boolean thundering;
    private final boolean autoSave;
    private final boolean spawnMonsters;
    private final boolean spawnAnimals;
    private final boolean pvp;
    private final double worldBorderRadius;
    private final boolean autoSeedEnabled;
    private final double autoSeedIntensity;
    private final int autoSeedAnchorCount;
    private final int autoSeedRingRadiusBlocks;
    private final List<String> autoSeedProfileCycle;

    private volatile World realmWorld;
    private volatile CompletableFuture<World> initializationFuture;
    private volatile boolean templateApplied;
    private volatile int seededAnchors;
    private volatile String lastFailure;

    public FractureRealmService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        WorldThreatManager worldThreatManager,
        FractureProfileService fractureProfileService,
        Path templateRoot,
        Path liveWorldRoot,
        boolean enabled,
        String worldName,
        String templateName,
        World.Environment environment,
        long seed,
        boolean generateStructures,
        Difficulty difficulty,
        long fixedTime,
        boolean storm,
        boolean thundering,
        boolean autoSave,
        boolean spawnMonsters,
        boolean spawnAnimals,
        boolean pvp,
        double worldBorderRadius,
        boolean autoSeedEnabled,
        double autoSeedIntensity,
        int autoSeedAnchorCount,
        int autoSeedRingRadiusBlocks,
        List<String> autoSeedProfileCycle
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.worldThreatManager = worldThreatManager;
        this.fractureProfileService = fractureProfileService;
        this.templateRoot = templateRoot;
        this.liveWorldRoot = liveWorldRoot;
        this.enabled = enabled;
        this.worldName = worldName == null || worldName.isBlank() ? "vortex_fractured" : worldName.trim();
        this.templateName = templateName == null ? "" : templateName.trim();
        this.environment = environment == null ? World.Environment.NORMAL : environment;
        this.seed = seed;
        this.generateStructures = generateStructures;
        this.difficulty = difficulty == null ? Difficulty.HARD : difficulty;
        this.fixedTime = fixedTime;
        this.storm = storm;
        this.thundering = thundering;
        this.autoSave = autoSave;
        this.spawnMonsters = spawnMonsters;
        this.spawnAnimals = spawnAnimals;
        this.pvp = pvp;
        this.worldBorderRadius = Math.max(0.0D, worldBorderRadius);
        this.autoSeedEnabled = autoSeedEnabled;
        this.autoSeedIntensity = Math.max(1.0D, autoSeedIntensity);
        this.autoSeedAnchorCount = Math.max(1, autoSeedAnchorCount);
        this.autoSeedRingRadiusBlocks = Math.max(64, autoSeedRingRadiusBlocks);
        this.autoSeedProfileCycle = List.copyOf(autoSeedProfileCycle);
        this.lastFailure = "";
    }

    public CompletableFuture<World> ensureRealm() {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        if (realmWorld != null) {
            return CompletableFuture.completedFuture(realmWorld);
        }
        CompletableFuture<World> existingFuture = initializationFuture;
        if (existingFuture != null) {
            return existingFuture;
        }

        CompletableFuture<World> future = new CompletableFuture<>();
        initializationFuture = future;
        Path liveDirectory = liveWorldRoot.resolve(worldName);
        Path templateDirectory = templateName.isBlank() ? null : templateRoot.resolve(templateName);

        scheduler.runAsync("fracture-realm-prepare-" + worldName, () -> {
            boolean copiedTemplate = false;
            try {
                if (templateDirectory != null && Files.isDirectory(templateDirectory) && Files.notExists(liveDirectory)) {
                    copyDirectory(templateDirectory, liveDirectory);
                    stripStateFiles(liveDirectory);
                    copiedTemplate = true;
                }
            } catch (IOException exception) {
                lastFailure = exception.getMessage();
                initializationFuture = null;
                future.completeExceptionally(exception);
                return;
            }

            boolean templateUsed = copiedTemplate;
            scheduler.runGlobal("fracture-realm-create-" + worldName, () -> {
                try {
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        WorldCreator creator = new WorldCreator(worldName);
                        creator.environment(environment);
                        creator.generateStructures(generateStructures);
                        creator.seed(seed);
                        world = Bukkit.createWorld(creator);
                    }

                    if (world == null) {
                        throw new IllegalStateException("Unable to create fracture realm world " + worldName);
                    }

                    this.realmWorld = world;
                    this.templateApplied = templateUsed;
                    this.lastFailure = "";
                    applyRealmSettings(world);
                    seedRealm(world);
                    future.complete(world);
                } catch (Exception exception) {
                    lastFailure = exception.getMessage();
                    initializationFuture = null;
                    future.completeExceptionally(exception);
                }
            });
        });

        return future;
    }

    public Optional<World> realmWorld() {
        World world = realmWorld;
        if (world != null) {
            return Optional.of(world);
        }
        return Optional.ofNullable(Bukkit.getWorld(worldName));
    }

    public StatusSnapshot statusSnapshot() {
        World world = realmWorld().orElse(null);
        return new StatusSnapshot(
            enabled,
            world != null,
            worldName,
            templateApplied,
            autoSeedEnabled,
            seededAnchors,
            autoSeedAnchorCount,
            lastFailure
        );
    }

    @SuppressWarnings({"removal", "deprecation"})
    private void applyRealmSettings(World world) {
        world.setDifficulty(difficulty);
        world.setSpawnFlags(spawnMonsters, spawnAnimals);
        world.setPVP(pvp);
        world.setAutoSave(autoSave);

        if (fixedTime >= 0L) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(fixedTime);
        }

        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setStorm(storm);
        world.setThundering(thundering);
        if (storm) {
            world.setWeatherDuration(Integer.MAX_VALUE);
        }
        if (thundering) {
            world.setThunderDuration(Integer.MAX_VALUE);
        }

        if (worldBorderRadius > 0.0D) {
            Location spawn = world.getSpawnLocation();
            WorldBorder border = world.getWorldBorder();
            border.setCenter(spawn.getX(), spawn.getZ());
            border.setSize(Math.max(32.0D, worldBorderRadius * 2.0D));
        }
    }

    private void seedRealm(World world) {
        seededAnchors = 0;
        if (!autoSeedEnabled) {
            return;
        }

        List<String> profiles = usableProfiles();
        Location spawn = world.getSpawnLocation();
        ArrayList<Location> anchors = new ArrayList<>();
        if (autoSeedAnchorCount == 1) {
            anchors.add(anchorFor(world, spawn.getBlockX(), spawn.getBlockZ()));
        } else {
            for (int index = 0; index < autoSeedAnchorCount; index++) {
                double angle = (Math.PI * 2.0D * index) / autoSeedAnchorCount;
                int targetX = spawn.getBlockX() + (int) Math.round(Math.cos(angle) * autoSeedRingRadiusBlocks);
                int targetZ = spawn.getBlockZ() + (int) Math.round(Math.sin(angle) * autoSeedRingRadiusBlocks);
                anchors.add(anchorFor(world, targetX, targetZ));
            }
        }

        for (int index = 0; index < anchors.size(); index++) {
            Location anchor = anchors.get(index);
            String profileId = profiles.get(index % profiles.size());
            String name = "realm-" + profileId + "-" + (index + 1);
            worldThreatManager.seedFracture(anchor, ThreatSource.ADMIN_FORCED, autoSeedIntensity, profileId, name)
                .thenAccept(snapshot -> seededAnchors++)
                .exceptionally(error -> {
                    logger.warning("Unable to seed fracture realm anchor " + name + ": " + friendlyError(error));
                    return null;
                });
        }
    }

    private List<String> usableProfiles() {
        List<String> source = autoSeedProfileCycle.isEmpty() ? fractureProfileService.profileIds() : autoSeedProfileCycle;
        ArrayList<String> profiles = new ArrayList<>();
        for (String profileId : source) {
            String resolved = fractureProfileService.resolve(profileId).id();
            if (!profiles.contains(resolved)) {
                profiles.add(resolved);
            }
        }
        if (profiles.isEmpty()) {
            profiles.add(fractureProfileService.defaultProfile().id());
        }
        return List.copyOf(profiles);
    }

    private Location anchorFor(World world, int blockX, int blockZ) {
        int safeY = Math.max(world.getMinHeight() + 1, world.getHighestBlockYAt(blockX, blockZ) + 1);
        return new Location(world, blockX + 0.5D, safeY, blockZ + 0.5D);
    }

    private String friendlyError(Throwable error) {
        Throwable resolved = error;
        while (resolved.getCause() != null) {
            resolved = resolved.getCause();
        }
        return resolved.getMessage() == null || resolved.getMessage().isBlank()
            ? resolved.getClass().getSimpleName()
            : resolved.getMessage();
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path sourcePath : stream.toList()) {
                Path relative = source.relativize(sourcePath);
                Path targetPath = target.resolve(relative);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void stripStateFiles(Path worldDirectory) throws IOException {
        Files.deleteIfExists(worldDirectory.resolve("uid.dat"));
        Files.deleteIfExists(worldDirectory.resolve("session.lock"));
    }

    public record StatusSnapshot(
        boolean enabled,
        boolean loaded,
        String worldName,
        boolean templateApplied,
        boolean autoSeedEnabled,
        int seededAnchors,
        int configuredAnchors,
        String lastFailure
    ) {

        public String describeLine() {
            if (!enabled) {
                return "Fracture Realm -> disabled";
            }
            if (!loaded) {
                return lastFailure == null || lastFailure.isBlank()
                    ? "Fracture Realm -> waiting for world " + worldName
                    : "Fracture Realm -> failed to load " + worldName + " | " + lastFailure;
            }
            return "Fracture Realm -> world "
                + worldName
                + " ready | source="
                + (templateApplied ? "template" : "procedural")
                + " | auto-seed="
                + (autoSeedEnabled ? "on" : "off")
                + " | anchors="
                + seededAnchors
                + "/"
                + configuredAnchors;
        }
    }
}
