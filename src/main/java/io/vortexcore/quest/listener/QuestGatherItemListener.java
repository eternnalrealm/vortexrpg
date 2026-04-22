package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

public final class QuestGatherItemListener implements Listener {

    private final QuestService questService;

    public QuestGatherItemListener(QuestService questService) {
        this.questService = questService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        String itemType = event.getItem().getItemStack().getType().name().toLowerCase();
        int amount = event.getItem().getItemStack().getAmount();
        questService.progressObjectiveByType(player, QuestObjectiveType.GATHER_ITEM, itemType, amount);
    }
}
