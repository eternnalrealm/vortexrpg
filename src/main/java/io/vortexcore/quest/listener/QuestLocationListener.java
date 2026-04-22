package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class QuestLocationListener implements Listener {

    private final QuestService questService;
    private final Map<UUID, Location> lastChecked;

    public QuestLocationListener(QuestService questService) {
        this.questService = questService;
        this.lastChecked = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }

        Player player = event.getPlayer();
        Location to = event.getTo();

        Location previous = lastChecked.get(player.getUniqueId());
        if (previous != null && previous.getBlockX() == to.getBlockX()
            && previous.getBlockZ() == to.getBlockZ()
            && previous.getWorld().equals(to.getWorld())) {
            return;
        }
        lastChecked.put(player.getUniqueId(), to.clone());

        String worldName = to.getWorld().getName().toLowerCase();
        questService.progressObjectiveByType(player, QuestObjectiveType.VISIT_LOCATION, worldName, 1);
    }

    public void cleanUp(UUID playerId) {
        lastChecked.remove(playerId);
    }
}
