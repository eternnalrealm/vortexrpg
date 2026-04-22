package io.vortexcore.scheduling;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaOrchestrator implements FoliaExecutionFacade {

    private final Plugin plugin;

    public FoliaOrchestrator(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(String taskName, Runnable action) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> safely(taskName, action));
    }

    @Override
    public void runGlobal(String taskName, Runnable action) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> safely(taskName, action));
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
        return entity.getScheduler().execute(
            plugin,
            () -> safely(taskName, action),
            retiredAction == null ? null : () -> safely(taskName + "-retired", retiredAction),
            Math.max(1L, delayTicks)
        );
    }

    @Override
    public void runRegion(Location location, String taskName, Runnable action) {
        Objects.requireNonNull(location.getWorld(), "location world");
        runRegion(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, taskName, action);
    }

    @Override
    public void runRegion(World world, int chunkX, int chunkZ, String taskName, Runnable action) {
        plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> safely(taskName, action));
    }

    @Override
    public VortexTask runAsyncTimer(String taskName, Duration initialDelay, Duration period, Consumer<VortexTask> action) {
        VortexTask[] handle = new VortexTask[1];
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runAtFixedRate(
            plugin,
            t -> safely(taskName, () -> action.accept(handle[0])),
            initialDelay.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS
        );
        handle[0] = new PaperVortexTask(task);
        return handle[0];
    }

    @Override
    public VortexTask runGlobalTimer(String taskName, long initialDelayTicks, long periodTicks, Consumer<VortexTask> action) {
        VortexTask[] handle = new VortexTask[1];
        ScheduledTask task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            t -> safely(taskName, () -> action.accept(handle[0])),
            initialDelayTicks,
            periodTicks
        );
        handle[0] = new PaperVortexTask(task);
        return handle[0];
    }

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
        VortexTask[] handle = new VortexTask[1];
        ScheduledTask task = plugin.getServer().getRegionScheduler().runAtFixedRate(
            plugin,
            world,
            chunkX,
            chunkZ,
            t -> safely(taskName, () -> action.accept(handle[0])),
            initialDelayTicks,
            periodTicks
        );
        handle[0] = new PaperVortexTask(task);
        return handle[0];
    }

    private void safely(String taskName, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Unhandled exception in Folia task " + taskName, throwable);
        }
    }
}
