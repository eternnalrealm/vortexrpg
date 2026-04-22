package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

public final class QuestFishListener implements Listener {

    private final QuestService questService;

    public QuestFishListener(QuestService questService) {
        this.questService = questService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        if (!(event.getCaught() instanceof Item caughtItem)) {
            return;
        }

        Player player = event.getPlayer();
        String itemType = caughtItem.getItemStack().getType().name().toLowerCase();
        int amount = caughtItem.getItemStack().getAmount();
        questService.progressObjectiveByType(player, QuestObjectiveType.FISH_ITEM, itemType, amount);
    }
}
