package io.vortexcore.instances;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class InstanceController {

    private static final java.util.regex.Pattern SAFE_NAME =
        java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final Path templateRoot;
    private final Path liveWorldRoot;
    private final Map<UUID, PartyInstance> activeInstances;

    public InstanceController(Plugin plugin, FoliaExecutionFacade scheduler, Path templateRoot, Path liveWorldRoot) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.templateRoot = templateRoot;
        this.liveWorldRoot = liveWorldRoot;
        this.activeInstances = new ConcurrentHashMap<>();
    }

    public CompletableFuture<PartyInstance> createInstance(String templateName, UUID partyId, Collection<? extends Player> players) {
        if (!SAFE_NAME.matcher(templateName).matches()) {
            CompletableFuture<PartyInstance> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalArgumentException("Invalid template name: " + templateName));
            return rejected;
        }
        CompletableFuture<PartyInstance> future = new CompletableFuture<>();
        String worldName = templateName + "_" + partyId.toString().substring(0, 8);
        Path templateDirectory = templateRoot.resolve(templateName);
        Path liveDirectory = liveWorldRoot.resolve(worldName);

        scheduler.runAsync("instance-copy-" + worldName, () -> {
            try {
                if (!Files.exists(templateDirectory)) {
                    throw new IOException("Missing dungeon template " + templateDirectory);
                }

                if (Files.exists(liveDirectory)) {
                    deleteDirectory(liveDirectory);
                }

                copyDirectory(templateDirectory, liveDirectory);
                stripStateFiles(liveDirectory);
            } catch (IOException exception) {
                future.completeExceptionally(exception);
                return;
            }

            scheduler.runGlobal("instance-create-world-" + worldName, () -> {
                World world = Bukkit.createWorld(new WorldCreator(worldName));
                if (world == null) {
                    future.completeExceptionally(new IllegalStateException("Unable to create instance world " + worldName));
                    return;
                }

                Location spawn = world.getSpawnLocation();
                PartyInstance instance = new PartyInstance(partyId, templateName, worldName, liveDirectory, spawn);
                activeInstances.put(partyId, instance);

                for (Player player : players) {
                    scheduler.runEntity(player, "instance-teleport-" + player.getUniqueId(), () -> player.teleportAsync(spawn));
                }

                future.complete(instance);
            });
        });

        return future;
    }

    public CompletableFuture<Void> destroyInstance(UUID partyId) {
        PartyInstance instance = activeInstances.remove(partyId);
        if (instance == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runGlobal("instance-unload-" + instance.worldName(), () -> {
            Bukkit.unloadWorld(instance.worldName(), false);
            scheduler.runAsync("instance-delete-" + instance.worldName(), () -> {
                try {
                    deleteDirectory(instance.worldDirectory());
                    future.complete(null);
                } catch (IOException exception) {
                    future.completeExceptionally(exception);
                }
            });
        });
        return future;
    }

    public int activeInstanceCount() {
        return activeInstances.size();
    }

    public void shutdown() {
        java.util.List<CompletableFuture<Void>> pending = activeInstances.keySet().stream()
            .map(this::destroyInstance)
            .collect(java.util.stream.Collectors.toList());
        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .get(10L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().warning("Instance cleanup incomplete during shutdown: " + e.getMessage());
            }
        }
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

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
