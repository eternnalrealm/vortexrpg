package io.vortexcore.scheduling;

import java.time.Duration;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

public interface FoliaExecutionFacade {

    void runAsync(String taskName, Runnable action);

    void runGlobal(String taskName, Runnable action);

    boolean runEntity(Entity entity, String taskName, Runnable action);

    boolean runEntity(Entity entity, String taskName, Runnable action, Runnable retiredAction);

    boolean runEntity(Entity entity, String taskName, long delayTicks, Runnable action);

    boolean runEntity(Entity entity, String taskName, long delayTicks, Runnable action, Runnable retiredAction);

    void runRegion(Location location, String taskName, Runnable action);

    void runRegion(World world, int chunkX, int chunkZ, String taskName, Runnable action);

    VortexTask runAsyncTimer(String taskName, Duration initialDelay, Duration period, Consumer<VortexTask> action);

    VortexTask runGlobalTimer(String taskName, long initialDelayTicks, long periodTicks, Consumer<VortexTask> action);

    VortexTask runRegionTimer(
        World world,
        int chunkX,
        int chunkZ,
        String taskName,
        long initialDelayTicks,
        long periodTicks,
        Consumer<VortexTask> action
    );
}
