package io.vortexcore.boss;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.loot.GaussianLootGenerator;
import io.vortexcore.loot.LootProfile;
import io.vortexcore.loot.LootRoll;
import io.vortexcore.quest.QuestObjectiveType;
import io.vortexcore.quest.QuestService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class BossService implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final long ABILITY_COOLDOWN_MILLIS = 8000L;

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final MessageService messageService;
    private final BossRegistry bossRegistry;
    private final BossAbilityExecutor abilityExecutor;
    private final GaussianLootGenerator lootGenerator;
    private final QuestService questService;
    private final PlayerProfileService playerProfileService;
    private final ProtectionHookService protectionHookService;
    private final long tickPeriod;
    private final double participantRange;
    private final NamespacedKey bossKey;
    private final NamespacedKey bossIdKey;
    private final ConcurrentMap<UUID, ActiveBoss> activeBosses;
    private final ConcurrentMap<String, Long> spawnCooldowns;
    private VortexTask tickTask;

    public BossService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        MessageService messageService,
        BossRegistry bossRegistry,
        GaussianLootGenerator lootGenerator,
        QuestService questService,
        PlayerProfileService playerProfileService,
        ProtectionHookService protectionHookService,
        long tickPeriod,
        double participantRange
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
        this.bossRegistry = bossRegistry;
        this.abilityExecutor = new BossAbilityExecutor(logger, scheduler);
        this.lootGenerator = lootGenerator;
        this.questService = questService;
        this.playerProfileService = playerProfileService;
        this.protectionHookService = protectionHookService;
        this.tickPeriod = tickPeriod;
        this.participantRange = participantRange;
        this.bossKey = new NamespacedKey(plugin, "custom_boss");
        this.bossIdKey = new NamespacedKey(plugin, "custom_boss_id");
        this.activeBosses = new ConcurrentHashMap<>();
        this.spawnCooldowns = new ConcurrentHashMap<>();
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = scheduler.runGlobalTimer("boss-tick", tickPeriod, tickPeriod, ignored -> tickAll());
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        activeBosses.values().forEach(ActiveBoss::despawn);
        activeBosses.clear();
    }

    public BossRegistry registry() {
        return bossRegistry;
    }

    public Map<UUID, ActiveBoss> activeBosses() {
        return Collections.unmodifiableMap(activeBosses);
    }

    public int clearAll() {
        int count = activeBosses.size();
        activeBosses.values().forEach(ActiveBoss::despawn);
        activeBosses.clear();
        logger.info("Cleared " + count + " active boss(es).");
        return count;
    }

    public void forceSummon(Player summoner, String bossId, Location location) {
        BossDefinition definition = bossRegistry.get(bossId).orElse(null);
        if (definition == null) {
            messageService.send(summoner, "<red>Unknown boss: " + bossId + "</red>");
            return;
        }

        scheduler.runRegion(location, "boss-force-summon-" + bossId, () -> {
            Entity spawned = location.getWorld().spawnEntity(location, definition.entityType());
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                messageService.send(summoner, "<red>Failed to spawn boss entity.</red>");
                return;
            }

            living.customName(MINI.deserialize(definition.displayName()));
            living.setCustomNameVisible(true);

            AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(Math.min(definition.maxHealth(), 1024.0));
                living.setHealth(maxHealth.getValue());
            }
            AttributeInstance attack = living.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                attack.setBaseValue(attack.getDefaultValue() * definition.damageMultiplier());
            }
            AttributeInstance speed = living.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(speed.getDefaultValue() * definition.speedMultiplier());
            }

            living.setRemoveWhenFarAway(false);

            PersistentDataContainer pdc = living.getPersistentDataContainer();
            pdc.set(bossKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(bossIdKey, PersistentDataType.STRING, bossId);

            ActiveBoss activeBoss = new ActiveBoss(living.getUniqueId(), definition);
            activeBoss.addParticipant(summoner);
            activeBosses.put(living.getUniqueId(), activeBoss);

            messageService.send(summoner, "<green>Force-summoned " + definition.displayName() + " at your location.</green>");
            logger.info("Boss " + bossId + " force-summoned by " + summoner.getName() + " at " + formatLocation(location));
        });
    }

    public void spawnBoss(Player summoner, String bossId, Location location) {
        BossDefinition definition = bossRegistry.get(bossId).orElse(null);
        if (definition == null) {
            messageService.send(summoner, "<red>Unknown boss: " + bossId + "</red>");
            return;
        }

        int playerLevel = playerProfileService.profile(summoner).level();
        if (playerLevel < definition.minLevel()) {
            messageService.send(summoner, "<red>You need level " + definition.minLevel() + " to summon this boss. (Your level: " + playerLevel + ")</red>");
            return;
        }

        ProtectionCheckResult protResult = protectionHookService.check(summoner, location, ProtectionAction.SPELL_CAST);
        if (!protResult.allowed()) {
            messageService.send(summoner, "<red>" + ProtectionHookService.denyMessage(protResult, ProtectionAction.SPELL_CAST) + "</red>");
            return;
        }

        Long lastSpawn = spawnCooldowns.get(bossId);
        long now = System.currentTimeMillis();
        if (lastSpawn != null && (now - lastSpawn) < definition.spawnCooldownSeconds() * 1000L) {
            long remainingSeconds = definition.spawnCooldownSeconds() - ((now - lastSpawn) / 1000L);
            messageService.send(summoner, "<red>This boss is on cooldown. " + remainingSeconds + "s remaining.</red>");
            return;
        }

        for (ActiveBoss existing : activeBosses.values()) {
            if (existing.definition().id().equals(bossId)) {
                messageService.send(summoner, "<red>This boss is already active!</red>");
                return;
            }
        }

        scheduler.runRegion(location, "boss-spawn-" + bossId, () -> {
            Entity spawned = location.getWorld().spawnEntity(location, definition.entityType());
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                messageService.send(summoner, "<red>Failed to spawn boss entity.</red>");
                return;
            }

            living.customName(MINI.deserialize(definition.displayName()));
            living.setCustomNameVisible(true);

            AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(Math.min(definition.maxHealth(), 1024.0));
                living.setHealth(maxHealth.getValue());
            }
            AttributeInstance attack = living.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                attack.setBaseValue(attack.getDefaultValue() * definition.damageMultiplier());
            }

            AttributeInstance speed = living.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(speed.getDefaultValue() * definition.speedMultiplier());
            }

            living.setRemoveWhenFarAway(false);

            PersistentDataContainer pdc = living.getPersistentDataContainer();
            pdc.set(bossKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(bossIdKey, PersistentDataType.STRING, bossId);

            ActiveBoss activeBoss = new ActiveBoss(living.getUniqueId(), definition);
            activeBoss.addParticipant(summoner);
            activeBosses.put(living.getUniqueId(), activeBoss);
            spawnCooldowns.put(bossId, System.currentTimeMillis());

            Component bossName = MINI.deserialize(definition.displayName());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getWorld().equals(location.getWorld())
                    && online.getLocation().distanceSquared(location) <= participantRange * participantRange) {
                    messageService.send(online, Component.text("")
                        .append(bossName)
                        .append(Component.text(" has been summoned by " + summoner.getName() + "!")));
                }
            }

            logger.info("Boss " + bossId + " spawned by " + summoner.getName() + " at " + formatLocation(location));
        });
    }

    public void openBossMenu(Player player) {
        Map<String, BossDefinition> all = bossRegistry.all();
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Boss Compendium"), 6);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int index = 0;

        for (Map.Entry<String, BossDefinition> entry : all.entrySet()) {
            if (index >= slots.length) break;
            BossDefinition def = entry.getValue();
            int slot = slots[index++];

            boolean isActive = activeBosses.values().stream().anyMatch(ab -> ab.definition().id().equals(def.id()));
            boolean onCooldown = isOnCooldown(def);
            int playerLevel = playerProfileService.profile(player).level();
            boolean meetsLevel = playerLevel >= def.minLevel();

            Material iconMaterial = isActive ? Material.REDSTONE_BLOCK
                : onCooldown ? Material.GRAY_DYE
                : meetsLevel ? def.summonItem()
                : Material.BARRIER;

            String status = isActive ? "<red>ACTIVE</red>"
                : onCooldown ? "<gray>Cooldown</gray>"
                : meetsLevel ? "<green>Available</green>"
                : "<red>Requires Level " + def.minLevel() + "</red>";

            List<String> lore = new ArrayList<>();
            lore.add("HP: " + String.format(Locale.US, "%.0f", def.maxHealth()));
            lore.add("Level Req: " + def.minLevel());
            lore.add("Phases: " + def.phases().size());
            lore.add("Status: " + status);
            if (!isActive && !onCooldown && meetsLevel) {
                lore.add("");
                lore.add("Click to receive summon item.");
            }

            ItemStack icon = buildIcon(iconMaterial, def.displayName(), lore);

            builder.button(slot, icon, context -> {
                if (isActive || onCooldown || !meetsLevel) {
                    return CompletableFuture.completedFuture(null);
                }
                ItemStack summonItem = buildSummonItem(def);
                context.player().getInventory().addItem(summonItem);
                messageService.send(context.player(), "You received a summon item for " + def.displayName() + ".");
                return context.close();
            });
        }

        if (all.isEmpty()) {
            builder.button(22, buildIcon(Material.STRUCTURE_VOID, "No Bosses Configured", List.of("Add bosses to bosses.yml.")),
                context -> CompletableFuture.completedFuture(null));
        }

        menuBuilder.open(player, builder.build());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(bossKey, PersistentDataType.BYTE)) {
            return;
        }

        ActiveBoss activeBoss = activeBosses.remove(entity.getUniqueId());
        if (activeBoss == null) {
            return;
        }

        activeBoss.defeat();
        BossDefinition def = activeBoss.definition();
        Component bossName = MINI.deserialize(def.displayName());

        List<LootProfile> profiles = def.lootProfiles().stream()
            .map(lp -> new LootProfile(lp.itemId(), lp.baseValue(), lp.scalePerLevel(), lp.spread(), lp.spread() * 3.0))
            .toList();

        for (UUID participantId : activeBoss.participants()) {
            Player participant = Bukkit.getPlayer(participantId);
            if (participant == null || !participant.isOnline()) {
                continue;
            }

            int participantLevel = playerProfileService.profile(participant).level();
            for (LootProfile profile : profiles) {
                LootRoll roll = lootGenerator.roll(profile.statKey(), participantLevel, List.of(profile));
                messageService.send(participant, Component.text("")
                    .append(bossName)
                    .append(Component.text(" defeated! Loot: " + roll.itemId() + " [" + roll.rarity().name() + "]")));
            }

            if (questService != null) {
                questService.progressObjectiveByType(participant, QuestObjectiveType.KILL_BOSS, def.id(), 1);
            }
        }

        Component announcement = Component.text("")
            .append(bossName)
            .append(Component.text(" has been defeated!"));
        for (Player online : Bukkit.getOnlinePlayers()) {
            messageService.send(online, announcement);
        }

        logger.info("Boss " + def.id() + " defeated. Participants: " + activeBoss.participants().size());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damaged = event.getEntity();
        ActiveBoss activeBoss = activeBosses.get(damaged.getUniqueId());
        if (activeBoss == null) {
            return;
        }

        Player damager = extractPlayerDamager(event);
        if (damager != null) {
            activeBoss.addParticipant(damager);
        }

        double scale = activeBoss.damageScale();
        if (scale < 1.0) {
            event.setDamage(event.getDamage() * scale);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeBosses.values().forEach(boss -> boss.bossBar().removeViewer(event.getPlayer()));
    }

    private void tickAll() {
        long now = System.currentTimeMillis();

        activeBosses.entrySet().removeIf(entry -> {
            ActiveBoss boss = entry.getValue();
            if (!boss.tick()) {
                boss.despawn();
                logger.info("Boss " + boss.definition().id() + " despawned (timeout or dead).");
                return true;
            }

            BossDefinition.BossPhase phase = boss.currentPhase();
            if (!phase.abilities().isEmpty() && (now - boss.lastAbilityAtMillis()) >= ABILITY_COOLDOWN_MILLIS) {
                int abilityIndex = (int) ((now / ABILITY_COOLDOWN_MILLIS) % phase.abilities().size());
                String abilityId = phase.abilities().get(abilityIndex);
                abilityExecutor.execute(boss, abilityId);
                boss.setLastAbilityAtMillis(now);
            }

            addNearbyParticipants(boss);
            return false;
        });
    }

    private void addNearbyParticipants(ActiveBoss boss) {
        Entity bossEntity = Bukkit.getServer().getEntity(boss.entityId());
        if (bossEntity == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(bossEntity.getWorld())
                && player.getLocation().distanceSquared(bossEntity.getLocation()) <= participantRange * participantRange
                && !boss.participants().contains(player.getUniqueId())) {
                boss.addParticipant(player);
            }
        }
    }

    private boolean isOnCooldown(BossDefinition def) {
        Long lastSpawn = spawnCooldowns.get(def.id());
        if (lastSpawn == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastSpawn) < def.spawnCooldownSeconds() * 1000L;
    }

    private Player extractPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
            && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private ItemStack buildSummonItem(BossDefinition def) {
        ItemStack item = new ItemStack(def.summonItem());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize("<gold>Summon: </gold>").append(MINI.deserialize(def.displayName())));
        meta.lore(List.of(
            Component.text("Right-click to summon this boss."),
            Component.text("Level requirement: " + def.minLevel()),
            Component.text("Boss ID: " + def.id())
        ));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(bossIdKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildIcon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize(name));
        meta.lore(loreLines.stream().map(line -> (Component) MINI.deserialize(line)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String formatLocation(Location loc) {
        return loc.getWorld().getName() + " [" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]";
    }
}
