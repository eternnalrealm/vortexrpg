package io.vortexcore.scheduling;

import org.bukkit.scheduler.BukkitTask;

/** VortexTask backed by a Bukkit {@link BukkitTask}. Used on plain Spigot/Paper. */
final class BukkitVortexTask implements VortexTask {

    private final BukkitTask delegate;
    private volatile boolean cancelled = false;

    BukkitVortexTask(BukkitTask delegate) {
        this.delegate = delegate;
    }

    @Override
    public void cancel() {
        cancelled = true;
        delegate.cancel();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
