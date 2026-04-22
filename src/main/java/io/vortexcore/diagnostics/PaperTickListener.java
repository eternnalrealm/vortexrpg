package io.vortexcore.diagnostics;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Registers the Paper-only {@link ServerTickEndEvent} to feed accurate tick-duration
 * measurements into {@link DiagnosticsTracker}.
 * <p>
 * This class is only instantiated and registered when the server is running Paper or Folia.
 * It must never be loaded on plain Spigot because {@link ServerTickEndEvent} does not exist there.
 */
public final class PaperTickListener implements Listener {

    private final DiagnosticsTracker tracker;

    public PaperTickListener(DiagnosticsTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerTickEnd(ServerTickEndEvent event) {
        tracker.recordTickMillis(event.getTickDuration());
    }
}
