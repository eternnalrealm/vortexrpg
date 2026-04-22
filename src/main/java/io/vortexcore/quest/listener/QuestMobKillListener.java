package io.vortexcore.quest.listener;

import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public final class QuestMobKillListener implements Listener {

    private final QuestService questService;

    public QuestMobKillListener(QuestService questService) {
        this.questService = questService;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }

        String mobType = entity.getType().name().toLowerCase();
        questService.progressObjectiveByType(killer, QuestObjectiveType.KILL_MOB, mobType, 1);
    }
}
