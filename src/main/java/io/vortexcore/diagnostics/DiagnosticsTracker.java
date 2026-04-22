package io.vortexcore.diagnostics;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class DiagnosticsTracker implements AutoCloseable {

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final Duration samplePeriod;
    private final Method regionTpsMethod;
    private final ConcurrentLinkedDeque<Double> recentTickMillis;
    private final ConcurrentMap<RegionKey, RegionSnapshot> regionSnapshots;
    private volatile long usedMemoryBytes;
    private volatile long maxMemoryBytes;
    private volatile long onlinePlayers;

    private VortexTask samplerTask;

    public DiagnosticsTracker(Plugin plugin, FoliaExecutionFacade scheduler, Duration samplePeriod) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.samplePeriod = samplePeriod;
        this.regionTpsMethod = resolveRegionTpsMethod(plugin);
        this.recentTickMillis = new ConcurrentLinkedDeque<>();
        this.regionSnapshots = new ConcurrentHashMap<>();
    }

    public void start() {
        if (samplerTask != null) {
            return;
        }

        samplerTask = scheduler.runAsyncTimer("diagnostics-region-sample", Duration.ZERO, samplePeriod, task -> dispatchSampling());
    }

    public void stop() {
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
    }

    public ServerHealthSnapshot snapshot() {
        List<Double> ticks = List.copyOf(recentTickMillis);
        double averageTickMillis = ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
        double maxTickMillis = ticks.stream().mapToDouble(Double::doubleValue).max().orElse(0.0D);

        List<RegionSnapshot> regions = regionSnapshots.values().stream()
            .sorted(Comparator.comparing(RegionSnapshot::worldName).thenComparingInt(RegionSnapshot::chunkX).thenComparingInt(RegionSnapshot::chunkZ))
            .toList();

        int totalEntities = regions.stream().mapToInt(RegionSnapshot::entityCount).sum();
        return new ServerHealthSnapshot(
            averageTickMillis,
            maxTickMillis,
            usedMemoryBytes,
            maxMemoryBytes,
            onlinePlayers,
            totalEntities,
            regions
        );
    }

    @Override
    public void close() {
        stop();
    }

    /** Called by {@link PaperTickListener} on Paper/Folia; no-op path exists for Spigot. */
    public void recordTickMillis(double millis) {
        recentTickMillis.addLast(millis);
        while (recentTickMillis.size() > 200) {
            recentTickMillis.pollFirst();
        }
    }

    private void dispatchSampling() {
        scheduler.runGlobal("diagnostics-dispatch", () -> {
            Runtime runtime = Runtime.getRuntime();
            usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
            maxMemoryBytes = runtime.maxMemory();
            onlinePlayers = Bukkit.getOnlinePlayers().size();

            for (World world : Bukkit.getWorlds()) {
                Chunk[] loadedChunks = world.getLoadedChunks();
                for (Chunk chunk : loadedChunks) {
                    int chunkX = chunk.getX();
                    int chunkZ = chunk.getZ();
                    scheduler.runRegion(world, chunkX, chunkZ, "diagnostics-region-" + world.getName(), () -> sampleChunk(world, chunkX, chunkZ));
                }
            }
        });
    }

    private void sampleChunk(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            regionSnapshots.remove(new RegionKey(world.getUID().toString(), chunkX, chunkZ));
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        Entity[] entities = chunk.getEntities();
        int playerCount = 0;
        for (Entity entity : entities) {
            if (entity instanceof Player) {
                playerCount++;
            }
        }

        double[] regionTps = lookupRegionTps(world, chunkX, chunkZ);
        RegionTpsSnapshot tpsSnapshot = regionTps == null
            ? RegionTpsSnapshot.empty()
            : RegionTpsSnapshot.from(regionTps);

        regionSnapshots.put(
            new RegionKey(world.getUID().toString(), chunkX, chunkZ),
            new RegionSnapshot(world.getName(), chunkX, chunkZ, entities.length, playerCount, tpsSnapshot, System.currentTimeMillis())
        );
    }

    private static Method resolveRegionTpsMethod(Plugin plugin) {
        try {
            return plugin.getServer().getClass().getMethod("getRegionTPS", World.class, int.class, int.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private double[] lookupRegionTps(World world, int chunkX, int chunkZ) {
        if (regionTpsMethod == null) {
            return null;
        }

        try {
            Object value = regionTpsMethod.invoke(plugin.getServer(), world, chunkX, chunkZ);
            return value instanceof double[] tps ? tps : null;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().fine("Region TPS lookup unavailable: " + exception.getMessage());
            return null;
        }
    }

    public record ServerHealthSnapshot(
        double averageTickMillis,
        double maxTickMillis,
        long usedMemoryBytes,
        long maxMemoryBytes,
        long onlinePlayers,
        int totalEntities,
        List<RegionSnapshot> regionSnapshots
    ) {

        public List<String> describeLines(int topRegions) {
            List<String> lines = new ArrayList<>();
            double memoryPercent = maxMemoryBytes <= 0L ? 0.0D : (usedMemoryBytes * 100.0D) / maxMemoryBytes;
            lines.add(String.format(java.util.Locale.ROOT, "Tick avg/max: %.2fms / %.2fms", averageTickMillis, maxTickMillis));
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    "Memory: %s / %s (%.1f%%) | Online players: %d | Region entities: %d",
                    formatBytes(usedMemoryBytes),
                    formatBytes(maxMemoryBytes),
                    memoryPercent,
                    onlinePlayers,
                    totalEntities
                )
            );

            regionSnapshots.stream()
                .sorted((left, right) -> {
                    int byPlayers = Integer.compare(right.playerCount(), left.playerCount());
                    if (byPlayers != 0) {
                        return byPlayers;
                    }
                    int byTps = Double.compare(left.effectiveOneMinuteTps(), right.effectiveOneMinuteTps());
                    if (byTps != 0) {
                        return byTps;
                    }
                    return Integer.compare(right.entityCount(), left.entityCount());
                })
                .limit(Math.max(0, topRegions))
                .forEach(region -> lines.add(
                    String.format(
                        java.util.Locale.ROOT,
                        "Hot region %s [%d,%d] -> entities %d, players %d, 1m TPS %.2f",
                        region.worldName(),
                        region.chunkX(),
                        region.chunkZ(),
                        region.entityCount(),
                        region.playerCount(),
                        region.effectiveOneMinuteTps()
                    )
                ));

            return List.copyOf(lines);
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024L) {
                return bytes + " B";
            }
            double kib = bytes / 1024.0D;
            if (kib < 1024.0D) {
                return String.format(java.util.Locale.ROOT, "%.1f KiB", kib);
            }
            double mib = kib / 1024.0D;
            if (mib < 1024.0D) {
                return String.format(java.util.Locale.ROOT, "%.1f MiB", mib);
            }
            return String.format(java.util.Locale.ROOT, "%.1f GiB", mib / 1024.0D);
        }
    }

    public record RegionSnapshot(
        String worldName,
        int chunkX,
        int chunkZ,
        int entityCount,
        int playerCount,
        RegionTpsSnapshot tps,
        long sampledAtEpochMillis
    ) {

        public double effectiveOneMinuteTps() {
            return tps == null || tps.oneMinute() <= 0.0D ? 20.0D : tps.oneMinute();
        }
    }

    public record RegionTpsSnapshot(
        double fiveSeconds,
        double fifteenSeconds,
        double oneMinute,
        double fiveMinutes,
        double fifteenMinutes
    ) {

        private static RegionTpsSnapshot from(double[] values) {
            return new RegionTpsSnapshot(
                valueAt(values, 0),
                valueAt(values, 1),
                valueAt(values, 2),
                valueAt(values, 3),
                valueAt(values, 4)
            );
        }

        private static RegionTpsSnapshot empty() {
            return new RegionTpsSnapshot(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        private static double valueAt(double[] values, int index) {
            return index < values.length ? values[index] : 0.0D;
        }
    }

    private record RegionKey(String worldId, int chunkX, int chunkZ) {
    }
}
