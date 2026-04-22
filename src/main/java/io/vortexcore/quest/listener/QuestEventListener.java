package io.vortexcore.quest.listener;

import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Consolidated listener for all quest objective event types.
 * Replaces the 6 individual quest listener classes and adds consistent
 * protection checks where applicable.
 */
public final class QuestEventListener implements Listener {

    private final QuestService questService;
    private final ProtectionHookService protectionHookService;
    private final Map<UUID, Location> lastChecked = new ConcurrentHashMap<>();

    public QuestEventListener(QuestService questService, ProtectionHookService protectionHookService) {
        this.questService = questService;
        this.protectionHookService = protectionHookService;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!protectionHookService.check(player, event.getBlock().getLocation(), ProtectionAction.GATHER).allowed()) {
            return;
        }
        String blockType = event.getBlock().getType().name().toLowerCase();
        questService.progressObjectiveByType(player, QuestObjectiveType.MINE_BLOCK, blockType, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof Item caughtItem)) {
            return;
        }
        String itemType = caughtItem.getItemStack().getType().name().toLowerCase();
        int amount = caughtItem.getItemStack().getAmount();
        questService.progressObjectiveByType(event.getPlayer(), QuestObjectiveType.FISH_ITEM, itemType, amount);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        Location previous = lastChecked.get(player.getUniqueId());
        if (previous != null
            && previous.getBlockX() == to.getBlockX()
            && previous.getBlockZ() == to.getBlockZ()
            && previous.getWorld().equals(to.getWorld())) {
            return;
        }
        lastChecked.put(player.getUniqueId(), to.clone());
        String worldName = to.getWorld().getName().toLowerCase();
        questService.progressObjectiveByType(player, QuestObjectiveType.VISIT_LOCATION, worldName, 1);
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

    public void cleanUp(UUID playerId) {
        lastChecked.remove(playerId);
    }
}
