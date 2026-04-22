package io.vortexcore.scheduling;

/**
 * Platform-agnostic handle for a scheduled repeating or delayed task.
 * <p>
 * On Paper/Folia this wraps {@code io.papermc.paper.threadedregions.scheduler.ScheduledTask}.
 * On Spigot this wraps {@code org.bukkit.scheduler.BukkitTask}.
 */
public interface VortexTask {

    /** Cancel this task. Safe to call multiple times. */
    void cancel();

    /** Returns {@code true} if the task has been cancelled. */
    boolean isCancelled();
}
