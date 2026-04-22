package io.vortexcore.item;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class CustomItemRuntimeService implements Listener, ClassSkillService.LiveAttributeAugmenter {

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final CustomItemService customItemService;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final SpellbookService spellbookService;
    private final EffectService effectService;
    private final MessageService messageService;
    private final Map<UUID, Map<String, Long>> itemCooldowns;
    /** Per-player+key timestamp of the last cooldown action-bar nudge to prevent spam. */
    private final Map<UUID, Map<String, Long>> cooldownNudges;

    public CustomItemRuntimeService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        CustomItemService customItemService,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        SpellbookService spellbookService,
        EffectService effectService,
        MessageService messageService
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.customItemService = customItemService;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.spellbookService = spellbookService;
        this.effectService = effectService;
        this.messageService = messageService;
        this.itemCooldowns = new ConcurrentHashMap<>();
        this.cooldownNudges = new ConcurrentHashMap<>();
    }

    @Override
    public void augment(Player player, UnifiedPlayer profile) {
        for (ItemStack item : equippedItems(player)) {
            CustomItemDefinition definition = definition(item);
            if (definition == null || !definition.hasPassiveBonuses() || !meetsClassRequirement(definition, profile)) {
                continue;
            }
            definition.attributeBonuses().forEach((attribute, bonus) -> profile.stat(attribute).addFlat(bonus));
            definition.attributeMultipliers().forEach((attribute, bonus) -> profile.stat(attribute).addMultiplier(bonus));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleRefresh(player, "inventory-click");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleRefresh(player, "inventory-drag");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        scheduleRefresh(event.getPlayer(), "held-slot");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleRefresh(event.getPlayer(), "swap-hands");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleRefresh(event.getPlayer(), "respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleRefresh(event.getPlayer(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        itemCooldowns.remove(event.getPlayer().getUniqueId());
        cooldownNudges.remove(event.getPlayer().getUniqueId());
    }



    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (!dropLockEnabled()) {
            return;
        }
        if (customItemService.isLocked(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            messageService.send(event.getPlayer(),
                "<red>That relic is bound to you — it cannot be dropped.</red>");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!dropLockEnabled()) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> locked = drops.stream()
            .filter(customItemService::isLocked)
            .toList();
        if (locked.isEmpty()) {
            return;
        }
        drops.removeAll(locked);
        // return locked items to the player on respawn via their inventory snapshot
        Player player = event.getEntity();
        for (ItemStack item : locked) {
            // store back directly — inventory is still accessible until cleared after the event
            player.getInventory().addItem(item).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }



    private boolean dropLockEnabled() {
        return plugin.getConfig().getBoolean("items.prevent-drop-locked", true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        CustomItemDefinition definition = definition(item);
        if (definition == null) {
            if (isPotentialEquipmentItem(item)) {
                scheduleRefresh(player, "interact-equip");
            }
            return;
        }

        CustomItemDefinition.ActivationTrigger trigger = null;
        if (event.getAction().isRightClick()) {
            trigger = CustomItemDefinition.ActivationTrigger.RIGHT_CLICK;
        } else if (event.getAction().isLeftClick()) {
            trigger = CustomItemDefinition.ActivationTrigger.LEFT_CLICK;
        }
        if (trigger == null) {
            return;
        }

        if (tryActivateAbility(player, definition, trigger)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        CustomItemDefinition definition = definition(player.getInventory().getItemInMainHand());
        if (definition == null) {
            return;
        }
        // Fire the left-click ability (spell / effect) but DO NOT cancel the event.
        // This lets vanilla melee damage proceed AND adds the configured spell on top —
        // so a mage staff still hits for its base item damage while also casting a bolt.
        tryActivateAbility(player, definition, CustomItemDefinition.ActivationTrigger.LEFT_CLICK);
    }

    private boolean tryActivateAbility(Player player, CustomItemDefinition definition, CustomItemDefinition.ActivationTrigger trigger) {
        CustomItemDefinition.AbilityBinding binding = definition.ability(trigger);
        if (!binding.isConfigured()) {
            return false;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (!meetsClassRequirement(definition, profile)) {
            messageService.actionBar(player, "<red>This relic requires class: " + NameFormat.friendly(definition.requiredClassId()) + ".</red>");
            return false;
        }

        String cooldownKey = definition.id() + ":" + trigger.name().toLowerCase(Locale.ROOT);
        long cooldownRemaining = remainingCooldownMillis(player.getUniqueId(), cooldownKey);
        if (cooldownRemaining > 0L) {
            sendCooldownNudge(player, cooldownKey,
                "<gray>" + friendlyItemName(definition) + "</gray> <dark_gray>·</dark_gray> <yellow>" + formatSeconds(cooldownRemaining) + "s</yellow>");
            return false;
        }

        String spellId = binding.useActiveSpell()
            ? spellbookService.activeSpellId(profile).orElse(null)
            : binding.useSpellId();
        if (binding.useActiveSpell() && spellId == null) {
            messageService.actionBar(player, "<red>" + friendlyItemName(definition) + " requires an active spell.</red>");
            return false;
        }

        if (spellId != null && !spellbookService.availableSpells(profile).contains(spellId)) {
            messageService.actionBar(player,
                "<red>" + friendlyItemName(definition) + " requires access to spell " + spellbookService.spellLabel(spellId) + ".</red>"
            );
            return false;
        }
        if (binding.useEffectId() != null && !effectService.hasRegisteredEffect(binding.useEffectId())) {
            messageService.actionBar(player,
                "<red>" + friendlyItemName(definition) + " references missing effect " + binding.useEffectId() + ".</red>"
            );
            return false;
        }

        CompletableFuture<Void> activation = CompletableFuture.completedFuture(null);
        if (spellId != null) {
            activation = activation.thenCompose(ignored -> spellbookService.cast(player, profile, spellId));
        }
        if (binding.useEffectId() != null) {
            activation = activation.thenCompose(ignored ->
                effectService.applyEffectToEntity(
                    player,
                    binding.useEffectId(),
                    binding.useEffectStacks(),
                    binding.useEffectDurationTicks(),
                    binding.useEffectPotency(),
                    binding.useEffectRefreshDuration()
                )
            );
        }

        activation.whenComplete((ignored, error) -> {
            if (error != null) {
                if (spellId != null) {
                    return;
                }
                Throwable resolved = unwrap(error);
                scheduler.runEntity(player, "item-ability-error-" + player.getUniqueId(), () ->
                    messageService.actionBar(player,
                        resolved.getMessage() == null || resolved.getMessage().isBlank()
                            ? "<red>The relic failed to activate.</red>"
                            : "<red>" + resolved.getMessage() + "</red>"
                    )
                );
                return;
            }

            if (binding.useCooldownMillis() > 0L) {
                itemCooldowns
                    .computeIfAbsent(player.getUniqueId(), ignoredMap -> new ConcurrentHashMap<>())
                    .put(cooldownKey, System.currentTimeMillis() + binding.useCooldownMillis());
            }
            if (spellId == null && binding.useEffectId() != null) {
                scheduler.runEntity(player, "item-ability-msg-" + player.getUniqueId(), () ->
                    messageService.actionBar(player, "<green>Activated " + friendlyItemName(definition) + ".</green>")
                );
            }
        });

        return true;
    }

    private void scheduleRefresh(Player player, String reason) {
        scheduler.runEntity(player, "item-runtime-refresh-" + reason + "-" + player.getUniqueId(), () -> {
            refreshInventoryLore(player);
            classSkillService.applyLiveAttributes(player, playerProfileService.profile(player));
        });
    }

    /**
     * Re-brands every custom item in the player's inventory with the current
     * definition's name + lore. Ensures players never see stale text after a
     * plugin update or {@code /vortex reloaditems}.
     */
    private void refreshInventoryLore(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack stack : contents) {
            customItemService.refresh(stack);
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        customItemService.refresh(offHand);
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            customItemService.refresh(armor);
        }
    }

    private List<ItemStack> equippedItems(Player player) {
        ArrayList<ItemStack> items = new ArrayList<>();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.getType() != Material.AIR) {
            items.add(mainHand);
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            items.add(offHand);
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR) {
                items.add(armor);
            }
        }
        return List.copyOf(items);
    }

    private CustomItemDefinition definition(ItemStack item) {
        String id = customItemService.resolveId(item);
        return id == null ? null : customItemService.definition(id).orElse(null);
    }

    private boolean meetsClassRequirement(CustomItemDefinition definition, UnifiedPlayer profile) {
        if (definition.requiredClassId() == null) {
            return true;
        }
        return classSkillService.selectedClass(profile)
            .map(selectedClass -> selectedClass.commandId().equals(definition.requiredClassId()))
            .orElse(false);
    }

    private boolean isPotentialEquipmentItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_HELMET")
            || name.endsWith("_CHESTPLATE")
            || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS")
            || item.getType() == Material.SHIELD
            || item.getType() == Material.ELYTRA;
    }

    private long remainingCooldownMillis(UUID playerId, String itemId) {
        Map<String, Long> cooldowns = itemCooldowns.get(playerId);
        if (cooldowns == null) {
            return 0L;
        }
        long readyAt = cooldowns.getOrDefault(itemId, 0L);
        return Math.max(0L, readyAt - System.currentTimeMillis());
    }

    /**
     * Sends a tiny action-bar nudge instead of a chat message so spam-clicking a
     * left-click ability never floods chat. The action bar overwrites itself, and
     * we additionally throttle to one update per ~250ms per (player, key) to avoid
     * unnecessary network packets.
     */
    private void sendCooldownNudge(Player player, String key, String message) {
        long now = System.currentTimeMillis();
        Map<String, Long> perPlayer = cooldownNudges.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        Long last = perPlayer.get(key);
        if (last != null && now - last < 250L) {
            return;
        }
        perPlayer.put(key, now);
        messageService.actionBar(player, message);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String friendlyItemName(CustomItemDefinition definition) {
        return definition.displayName() == null || definition.displayName().isBlank()
            ? NameFormat.friendly(definition.id())
            : definition.displayName();
    }

    private String formatSeconds(long millis) {
        return String.format(Locale.US, "%.1f", millis / 1000.0D);
    }
}
