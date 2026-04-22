package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;

public final class QuestCraftItemListener implements Listener {

    private final QuestService questService;

    public QuestCraftItemListener(QuestService questService) {
        this.questService = questService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String itemType = event.getCurrentItem() != null
            ? event.getCurrentItem().getType().name().toLowerCase()
            : "";
        if (itemType.isBlank()) {
            return;
        }

        int amount = event.getCurrentItem().getAmount();
        questService.progressObjectiveByType(player, QuestObjectiveType.CRAFT_ITEM, itemType, amount);
    }
}
