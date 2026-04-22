package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public final class QuestMineBlockListener implements Listener {

    private final QuestService questService;

    public QuestMineBlockListener(QuestService questService) {
        this.questService = questService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String blockType = event.getBlock().getType().name().toLowerCase();
        questService.progressObjectiveByType(player, QuestObjectiveType.MINE_BLOCK, blockType, 1);
    }
}
