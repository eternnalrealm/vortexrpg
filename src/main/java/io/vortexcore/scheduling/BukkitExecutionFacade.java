package io.vortexcore.scheduling;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Spigot-compatible implementation of {@link FoliaExecutionFacade}.
 * <p>
 * Used automatically on servers that do not have the Folia region scheduler
 * (i.e. plain Spigot or Paper without Folia). All region-scoped operations
 * fall back to the global main-thread scheduler since there is only one region.
 */
public final class BukkitExecutionFacade implements FoliaExecutionFacade {

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    public BukkitExecutionFacade(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getServer().getScheduler();
    }

    @Override
    public void runAsync(String taskName, Runnable action) {
        scheduler.runTaskAsynchronously(plugin, () -> safely(taskName, action));
    }

    @Override
    public void runGlobal(String taskName, Runnable action) {
        scheduler.runTask(plugin, () -> safely(taskName, action));
    }

    @Override
    public boolean runEntity(Entity entity, String taskName, Runnable action) {
        return runEntity(entity, taskName, 1L, action, null);
    }

    @Override
    public boolean runEntity(Entity entity, String taskName, Runnable action, Runnable retiredAction) {
        return runEntity(entity, taskName, 1L, action, retiredAction);
    }

    @Override
    public boolean runEntity(Entity entity, String taskName, long delayTicks, Runnable action) {
        return runEntity(entity, taskName, delayTicks, action, null);
    }

    @Override
    public boolean runEntity(Entity entity, String taskName, long delayTicks, Runnable action, Runnable retiredAction) {
        Objects.requireNonNull(entity, "entity");
        if (!entity.isValid()) {
            if (retiredAction != null) safely(taskName + "-retired", retiredAction);
            return false;
        }
        scheduler.runTaskLater(plugin, () -> {
            if (!entity.isValid()) {
                if (retiredAction != null) safely(taskName + "-retired", retiredAction);
                return;
            }
            safely(taskName, action);
        }, Math.max(1L, delayTicks));
        return true;
    }

    /** On Spigot there are no regions — run on main thread. */
    @Override
    public void runRegion(Location location, String taskName, Runnable action) {
        scheduler.runTask(plugin, () -> safely(taskName, action));
    }

    /** On Spigot there are no regions — run on main thread. */
    @Override
    public void runRegion(World world, int chunkX, int chunkZ, String taskName, Runnable action) {
        scheduler.runTask(plugin, () -> safely(taskName, action));
    }

    @Override
    public VortexTask runAsyncTimer(String taskName, Duration initialDelay, Duration period, Consumer<VortexTask> action) {
        long initialTicks = durationToTicks(initialDelay);
        long periodTicks = durationToTicks(period);
        // Use a 1-element array as a mutable reference captured by the lambda.
        // The array is populated synchronously before the first task execution
        // (earliest execution is initialTicks >= 1 tick away).
        VortexTask[] handle = new VortexTask[1];
        BukkitTask task = scheduler.runTaskTimerAsynchronously(plugin, () -> {
            if (handle[0] == null || handle[0].isCancelled()) return;
            safely(taskName, () -> action.accept(handle[0]));
        }, initialTicks, periodTicks);
        handle[0] = new BukkitVortexTask(task);
        return handle[0];
    }

    @Override
    public VortexTask runGlobalTimer(String taskName, long initialDelayTicks, long periodTicks, Consumer<VortexTask> action) {
        VortexTask[] handle = new VortexTask[1];
        BukkitTask task = scheduler.runTaskTimer(plugin, () -> {
            if (handle[0] == null || handle[0].isCancelled()) return;
            safely(taskName, () -> action.accept(handle[0]));
        }, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        handle[0] = new BukkitVortexTask(task);
        return handle[0];
    }

    /** On Spigot there are no regions — delegates to {@link #runGlobalTimer}. */
    @Override
    public VortexTask runRegionTimer(
        World world,
        int chunkX,
        int chunkZ,
        String taskName,
        long initialDelayTicks,
        long periodTicks,
        Consumer<VortexTask> action
    ) {
        return runGlobalTimer(taskName, initialDelayTicks, periodTicks, action);
    }



    private static long durationToTicks(Duration d) {
        long millis = d.toMillis();
        return Math.max(1L, millis / MILLIS_PER_TICK);
    }

    private void safely(String taskName, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Unhandled exception in task " + taskName, throwable);
        }
    }
}
