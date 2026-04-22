package io.vortexcore.dungeon;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.boss.BossDefinition;
import io.vortexcore.boss.BossRegistry;
import io.vortexcore.instances.InstanceController;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class DungeonService implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final MessageService messageService;
    private final DungeonRegistry dungeonRegistry;
    private final InstanceController instanceController;
    private final BossRegistry bossRegistry;
    private final VaultEconomyBridge vaultBridge;
    private final NamespacedKey dungeonMobKey;
    private final NamespacedKey dungeonIdKey;
    private final ConcurrentHashMap<UUID, ActiveDungeon> activeDungeons;
    private final ConcurrentHashMap<UUID, UUID> playerDungeonMap;
    private VortexTask tickTask;

    public DungeonService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        MessageService messageService,
        DungeonRegistry dungeonRegistry,
        InstanceController instanceController,
        BossRegistry bossRegistry,
        VaultEconomyBridge vaultBridge
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.messageService = messageService;
        this.dungeonRegistry = dungeonRegistry;
        this.instanceController = instanceController;
        this.bossRegistry = bossRegistry;
        this.vaultBridge = vaultBridge;
        this.dungeonMobKey = new NamespacedKey(plugin, "dungeon_mob");
        this.dungeonIdKey = new NamespacedKey(plugin, "dungeon_instance_id");
        this.activeDungeons = new ConcurrentHashMap<>();
        this.playerDungeonMap = new ConcurrentHashMap<>();
    }

    public void start() {
        if (tickTask != null) return;
        tickTask = scheduler.runGlobalTimer("dungeon-tick", 20L, 20L, ignored -> tickAll());
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (ActiveDungeon dungeon : activeDungeons.values()) {
            failDungeon(dungeon, "Server shutting down.");
        }
        activeDungeons.clear();
        playerDungeonMap.clear();
    }

    public DungeonRegistry registry() {
        return dungeonRegistry;
    }

    public Map<UUID, ActiveDungeon> activeDungeons() {
        return Collections.unmodifiableMap(activeDungeons);
    }

    public Optional<ActiveDungeon> playerDungeon(Player player) {
        UUID instanceId = playerDungeonMap.get(player.getUniqueId());
        return instanceId == null ? Optional.empty() : Optional.ofNullable(activeDungeons.get(instanceId));
    }

    public CompletableFuture<ActiveDungeon> enterDungeon(Player player, String dungeonId) {
        DungeonDefinition definition = dungeonRegistry.get(dungeonId).orElse(null);
        if (definition == null) {
            messageService.send(player, "<red>Unknown dungeon: " + dungeonId + "</red>");
            return CompletableFuture.completedFuture(null);
        }

        if (playerDungeonMap.containsKey(player.getUniqueId())) {
            messageService.send(player, "<red>You are already inside a dungeon. Use /vdungeon leave first.</red>");
            return CompletableFuture.completedFuture(null);
        }

        UUID instanceId = UUID.randomUUID();
        List<Player> players = List.of(player);

        return instanceController.createInstance(definition.templateName(), instanceId, players)
            .thenApply(partyInstance -> {
                ActiveDungeon dungeon = new ActiveDungeon(instanceId, definition, partyInstance.worldName());
                dungeon.addParticipant(player);
                activeDungeons.put(instanceId, dungeon);
                playerDungeonMap.put(player.getUniqueId(), instanceId);

                messageService.send(player, "<green>Entering dungeon: " + definition.displayName() + "</green>");
                messageService.send(player, "<gray>Time limit: " + definition.timeLimitSeconds() / 60 + " minutes. Waves: " + definition.waves().size() + ".</gray>");

                scheduler.runGlobal("dungeon-start-wave-" + instanceId, () -> startNextWave(dungeon));
                return dungeon;
            }).exceptionally(throwable -> {
                messageService.send(player, "<red>Failed to create dungeon instance: " + throwable.getMessage() + "</red>");
                logger.warning("Dungeon creation failed for " + dungeonId + ": " + throwable.getMessage());
                return null;
            });
    }

    public void leaveDungeon(Player player) {
        UUID instanceId = playerDungeonMap.remove(player.getUniqueId());
        if (instanceId == null) {
            messageService.send(player, "<red>You are not in a dungeon.</red>");
            return;
        }

        ActiveDungeon dungeon = activeDungeons.get(instanceId);
        if (dungeon != null) {
            dungeon.removeParticipant(player);
            teleportToOverworld(player);

            if (dungeon.participants().isEmpty()) {
                endDungeon(dungeon);
            }
        }

        messageService.send(player, "<yellow>You left the dungeon.</yellow>");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String instanceIdStr = pdc.get(dungeonIdKey, PersistentDataType.STRING);
        if (instanceIdStr == null) return;

        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceIdStr);
        } catch (IllegalArgumentException ex) {
            return;
        }

        ActiveDungeon dungeon = activeDungeons.get(instanceId);
        if (dungeon == null) return;

        dungeon.decrementMobs();

        if (dungeon.remainingMobs() <= 0) {
            if (dungeon.state() == ActiveDungeon.State.BOSS_PHASE) {
                completeDungeon(dungeon);
            } else {
                scheduler.runGlobal("dungeon-next-wave-" + instanceId, () -> startNextWave(dungeon));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID instanceId = playerDungeonMap.remove(event.getPlayer().getUniqueId());
        if (instanceId == null) return;

        ActiveDungeon dungeon = activeDungeons.get(instanceId);
        if (dungeon != null) {
            dungeon.removeParticipant(event.getPlayer());
            if (dungeon.participants().isEmpty()) {
                endDungeon(dungeon);
            }
        }
    }

    private void startNextWave(ActiveDungeon dungeon) {
        if (dungeon.state() == ActiveDungeon.State.COMPLETED || dungeon.state() == ActiveDungeon.State.FAILED) return;

        int waveIndex = dungeon.currentWaveIndex();
        List<DungeonDefinition.Wave> waves = dungeon.definition().waves();

        if (waveIndex >= waves.size()) {
            if (!dungeon.definition().bossId().isEmpty()) {
                startBossPhase(dungeon);
            } else {
                completeDungeon(dungeon);
            }
            return;
        }

        DungeonDefinition.Wave wave = waves.get(waveIndex);
        dungeon.setState(ActiveDungeon.State.WAVE_ACTIVE);
        dungeon.setRemainingMobs(wave.mobCount());

        broadcastToDungeon(dungeon, "<gold>Wave " + wave.waveNumber() + " — " + wave.mobCount() + "× " + formatEntity(wave.mobType()) + "</gold>");

        org.bukkit.World world = Bukkit.getWorld(dungeon.worldName());
        if (world == null) {
            failDungeon(dungeon, "Instance world unloaded.");
            return;
        }

        Location spawnCenter = world.getSpawnLocation();
        for (int i = 0; i < wave.mobCount(); i++) {
            double angle = (2 * Math.PI * i) / wave.mobCount();
            Location mobLoc = spawnCenter.clone().add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4);

            scheduler.runRegion(mobLoc, "dungeon-spawn-mob-" + dungeon.instanceId() + "-w" + waveIndex + "-" + i, () -> {
                Entity spawned = world.spawnEntity(mobLoc, wave.mobType());
                if (spawned instanceof LivingEntity living) {
                    AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealth != null) {
                        maxHealth.setBaseValue(Math.min(wave.mobHealth(), 1024.0));
                        living.setHealth(maxHealth.getValue());
                    }
                    AttributeInstance attack = living.getAttribute(Attribute.ATTACK_DAMAGE);
                    if (attack != null) {
                        attack.setBaseValue(attack.getDefaultValue() * wave.mobDamageMultiplier());
                    }
                    living.setRemoveWhenFarAway(false);

                    PersistentDataContainer pdc = living.getPersistentDataContainer();
                    pdc.set(dungeonMobKey, PersistentDataType.BYTE, (byte) 1);
                    pdc.set(dungeonIdKey, PersistentDataType.STRING, dungeon.instanceId().toString());
                }
            });
        }

        dungeon.setCurrentWaveIndex(waveIndex + 1);
    }

    private void startBossPhase(ActiveDungeon dungeon) {
        dungeon.setState(ActiveDungeon.State.BOSS_PHASE);
        dungeon.setRemainingMobs(1);

        String bossId = dungeon.definition().bossId();
        Optional<BossDefinition> bossDef = bossRegistry.get(bossId);

        EntityType bossType = bossDef.map(BossDefinition::entityType).orElse(EntityType.WITHER_SKELETON);
        double bossHealth = bossDef.map(BossDefinition::maxHealth).orElse(500.0);
        String bossName = bossDef.map(BossDefinition::displayName).orElse("<red>Dungeon Boss</red>");

        broadcastToDungeon(dungeon, "<dark_red><bold>BOSS PHASE</bold></dark_red> — " + bossName);

        org.bukkit.World world = Bukkit.getWorld(dungeon.worldName());
        if (world == null) {
            failDungeon(dungeon, "Instance world unloaded.");
            return;
        }

        Location spawnCenter = world.getSpawnLocation();
        scheduler.runRegion(spawnCenter, "dungeon-spawn-boss-" + dungeon.instanceId(), () -> {
            Entity spawned = world.spawnEntity(spawnCenter, bossType);
            if (spawned instanceof LivingEntity living) {
                living.customName(MINI.deserialize(bossName));
                living.setCustomNameVisible(true);

                AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(Math.min(bossHealth, 1024.0));
                    living.setHealth(maxHealth.getValue());
                }

                living.setRemoveWhenFarAway(false);
                PersistentDataContainer pdc = living.getPersistentDataContainer();
                pdc.set(dungeonMobKey, PersistentDataType.BYTE, (byte) 1);
                pdc.set(dungeonIdKey, PersistentDataType.STRING, dungeon.instanceId().toString());
            }
        });
    }

    private void completeDungeon(ActiveDungeon dungeon) {
        dungeon.setState(ActiveDungeon.State.COMPLETED);
        broadcastToDungeon(dungeon, "<green><bold>DUNGEON COMPLETE!</bold></green> — " + dungeon.definition().displayName());

        double reward = dungeon.definition().completionReward();
        for (UUID uuid : new ArrayList<>(dungeon.participants())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                if (vaultBridge.isAvailable() && reward > 0) {
                    vaultBridge.rewardBreachCache(player, 0, 0).thenAccept(opt ->
                        opt.ifPresent(amount -> messageService.send(player, "<green>+" + vaultBridge.formatAmount(reward) + " dungeon reward.</green>"))
                    );
                    scheduler.runGlobal("dungeon-reward-" + uuid, () -> {
                        net.milkbowl.vault.economy.Economy eco = null;
                        try {
                            var reg = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                            if (reg != null) eco = reg.getProvider();
                        } catch (Exception ignored) {}
                        if (eco != null) {
                            eco.depositPlayer(player, reward);
                            messageService.send(player, "<green>+" + vaultBridge.formatAmount(reward) + " dungeon completion reward!</green>");
                        }
                    });
                }
            }
        }

        scheduler.runGlobal("dungeon-cleanup-" + dungeon.instanceId(), () -> cleanupDungeon(dungeon, 100L));
    }

    private void failDungeon(ActiveDungeon dungeon, String reason) {
        dungeon.setState(ActiveDungeon.State.FAILED);
        broadcastToDungeon(dungeon, "<red>Dungeon failed: " + reason + "</red>");
        cleanupDungeon(dungeon, 60L);
    }

    private void endDungeon(ActiveDungeon dungeon) {
        if (dungeon.state() != ActiveDungeon.State.COMPLETED && dungeon.state() != ActiveDungeon.State.FAILED) {
            dungeon.setState(ActiveDungeon.State.FAILED);
        }
        cleanupDungeon(dungeon, 0L);
    }

    private void cleanupDungeon(ActiveDungeon dungeon, long delayTicks) {
        Runnable cleanup = () -> {
            for (UUID uuid : new ArrayList<>(dungeon.participants())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    teleportToOverworld(player);
                    dungeon.removeParticipant(player);
                }
                playerDungeonMap.remove(uuid);
            }
            dungeon.cleanup();
            activeDungeons.remove(dungeon.instanceId());
            instanceController.destroyInstance(dungeon.instanceId());
            logger.info("Dungeon instance " + dungeon.instanceId() + " (" + dungeon.definition().id() + ") cleaned up.");
        };

        if (delayTicks > 0) {
            scheduler.runGlobalTimer("dungeon-cleanup-delayed-" + dungeon.instanceId(), delayTicks, Long.MAX_VALUE, task -> {
                task.cancel();
                cleanup.run();
            });
        } else {
            scheduler.runGlobal("dungeon-cleanup-now-" + dungeon.instanceId(), cleanup);
        }
    }

    private void teleportToOverworld(Player player) {
        org.bukkit.World overworld = Bukkit.getWorlds().get(0);
        Location spawn = overworld.getSpawnLocation();
        scheduler.runEntity(player, "dungeon-teleport-back-" + player.getUniqueId(), () -> player.teleportAsync(spawn));
    }

    private void tickAll() {
        for (ActiveDungeon dungeon : activeDungeons.values()) {
            dungeon.updateTimerBar();

            if (dungeon.secondsRemaining() <= 0 && dungeon.state() != ActiveDungeon.State.COMPLETED && dungeon.state() != ActiveDungeon.State.FAILED) {
                failDungeon(dungeon, "Time's up!");
            }
        }
    }

    private void broadcastToDungeon(ActiveDungeon dungeon, String message) {
        for (UUID uuid : dungeon.participants()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messageService.send(player, message);
            }
        }
    }

    private String formatEntity(EntityType type) {
        return type.name().toLowerCase().replace('_', ' ');
    }
}
