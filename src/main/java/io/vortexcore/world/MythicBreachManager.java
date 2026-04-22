package io.vortexcore.world;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.instances.InstanceController;
import io.vortexcore.instances.PartyInstance;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public final class MythicBreachManager implements Listener, AutoCloseable {

    private static final long BOSS_MISSING_FAIL_MILLIS = 10_000L;

    private final Plugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final WorldThreatManager worldThreatManager;
    private final InstanceController instanceController;
    private final ExpeditionBoardManager expeditionBoardManager;
    private final ExpeditionPartyManager expeditionPartyManager;
    private final FractureLootService fractureLootService;
    private final PlayerProfileService playerProfileService;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final EffectService effectService;
    private final VortexMenuBuilder menuBuilder;
    private final MessageService messageService;
    private final EntityType bossEntityType;
    private final double bossBaseHealth;
    private final double bossDamageMultiplier;
    private final double bossScoreBonus;
    private final double phaseTwoThreshold;
    private final double phaseThreeThreshold;
    private final double phaseFourThreshold;
    private final long baseAbilityCooldownMillis;
    private final int sharedReviveCount;
    private final long disconnectGraceMillis;
    private final long reviveRespawnDelayMillis;
    private final long baseTokenReward;
    private final long tokenSeverityBonus;
    private final NamespacedKey breachBossKey;
    private final NamespacedKey breachSessionKey;
    private final ConcurrentMap<UUID, BreachSession> sessions;
    private final ConcurrentMap<UUID, UUID> playerSessions;
    private final ConcurrentMap<String, UUID> worldSessions;
    private final ConcurrentMap<UUID, ConcurrentMap<UUID, PendingRewardCache>> rewardCaches;
    private final CopyOnWriteArrayList<BreachCompletionListener> completionListeners;

    private VortexTask tickTask;

    public MythicBreachManager(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        WorldThreatManager worldThreatManager,
        InstanceController instanceController,
        ExpeditionBoardManager expeditionBoardManager,
        ExpeditionPartyManager expeditionPartyManager,
        FractureLootService fractureLootService,
        PlayerProfileService playerProfileService,
        VaultEconomyBridge vaultEconomyBridge,
        EffectService effectService,
        VortexMenuBuilder menuBuilder,
        MessageService messageService,
        String bossEntityType,
        double bossBaseHealth,
        double bossDamageMultiplier,
        double bossScoreBonus,
        double phaseTwoThreshold,
        double phaseThreeThreshold,
        double phaseFourThreshold,
        long baseAbilityCooldownSeconds,
        int sharedReviveCount,
        long disconnectGraceSeconds,
        long reviveRespawnDelaySeconds,
        long baseTokenReward,
        long tokenSeverityBonus
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.worldThreatManager = worldThreatManager;
        this.instanceController = instanceController;
        this.expeditionBoardManager = expeditionBoardManager;
        this.expeditionPartyManager = expeditionPartyManager;
        this.fractureLootService = fractureLootService;
        this.playerProfileService = playerProfileService;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.effectService = effectService;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
        this.bossEntityType = parseBossEntityType(bossEntityType);
        this.bossBaseHealth = Math.max(50.0D, bossBaseHealth);
        this.bossDamageMultiplier = Math.max(1.0D, bossDamageMultiplier);
        this.bossScoreBonus = Math.max(50.0D, bossScoreBonus);
        double normalizedPhaseTwo = clamp(phaseTwoThreshold, 0.55D, 0.90D);
        double normalizedPhaseThree = clamp(phaseThreeThreshold, 0.25D, normalizedPhaseTwo - 0.05D);
        double normalizedPhaseFour = clamp(phaseFourThreshold, 0.05D, normalizedPhaseThree - 0.05D);
        this.phaseTwoThreshold = normalizedPhaseTwo;
        this.phaseThreeThreshold = normalizedPhaseThree;
        this.phaseFourThreshold = normalizedPhaseFour;
        this.baseAbilityCooldownMillis = Math.max(4_000L, baseAbilityCooldownSeconds * 1_000L);
        this.sharedReviveCount = Math.max(0, sharedReviveCount);
        this.disconnectGraceMillis = Math.max(5_000L, disconnectGraceSeconds * 1_000L);
        this.reviveRespawnDelayMillis = Math.max(1_000L, reviveRespawnDelaySeconds * 1_000L);
        this.baseTokenReward = Math.max(1L, baseTokenReward);
        this.tokenSeverityBonus = Math.max(0L, tokenSeverityBonus);
        this.breachBossKey = new NamespacedKey(plugin, "mythic_breach_boss");
        this.breachSessionKey = new NamespacedKey(plugin, "mythic_breach_session");
        this.sessions = new ConcurrentHashMap<>();
        this.playerSessions = new ConcurrentHashMap<>();
        this.worldSessions = new ConcurrentHashMap<>();
        this.rewardCaches = new ConcurrentHashMap<>();
        this.completionListeners = new CopyOnWriteArrayList<>();
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = scheduler.runGlobalTimer("mythic-breach-tick", 10L, 10L, ignored -> tickSessions());
    }

    public CompletableFuture<BreachLaunchResult> launchOrRejoin(Player initiator) {
        UUID activeSessionId = playerSessions.get(initiator.getUniqueId());
        if (activeSessionId != null) {
            BreachSession session = sessions.get(activeSessionId);
            if (session != null) {
                synchronized (session) {
                    if (session.eliminatedParticipants.contains(initiator.getUniqueId())) {
                        return completed("You were eliminated from this breach. Wait for the party or claim rewards later.");
                    }
                    session.disconnectedAtEpochMillis.remove(initiator.getUniqueId());
                }
                return rejoinSession(initiator, session);
            }
            playerSessions.remove(initiator.getUniqueId(), activeSessionId);
        }

        Optional<ExpeditionBreachContext> contextOptional = expeditionBoardManager.breachContextFor(initiator.getUniqueId());
        if (contextOptional.isEmpty()) {
            return completed("You need an active breach contract before launching a finale.");
        }

        ExpeditionBreachContext context = contextOptional.get();
        if (context.leaderOnlyLaunch()) {
            Optional<ExpeditionPartyManager.PartySnapshot> party = expeditionPartyManager.partyOf(initiator.getUniqueId());
            if (party.isPresent() && !party.get().leaderId().equals(initiator.getUniqueId())) {
                return completed("Only the expedition party leader can launch the breach finale.");
            }
        }

        if (!expeditionBoardManager.setBreachLaunched(context.runId(), true)) {
            return completed("That breach finale is already being launched.");
        }

        List<Player> onlineParticipants = context.participants().stream()
            .map(Bukkit::getPlayer)
            .filter(player -> player != null && player.isOnline())
            .toList();
        if (onlineParticipants.isEmpty()) {
            expeditionBoardManager.setBreachLaunched(context.runId(), false);
            return completed("No online party members are available to enter the breach.");
        }

        Location sourceLocation = sourceLocation(context.contract().fracture());
        if (sourceLocation.getWorld() == null) {
            expeditionBoardManager.setBreachLaunched(context.runId(), false);
            return completed("The source fracture world is unavailable.");
        }

        Map<UUID, Location> returnLocations = new LinkedHashMap<>();
        for (Player participant : onlineParticipants) {
            returnLocations.put(participant.getUniqueId(), participant.getLocation().clone());
        }

        return worldThreatManager.openBossBreach(sourceLocation, context.expeditionId(), onlineParticipants)
            .thenCompose(instance -> createSession(context, instance, sourceLocation, returnLocations))
            .whenComplete((result, error) -> {
                if (error != null) {
                    expeditionBoardManager.setBreachLaunched(context.runId(), false);
                }
            });
    }

    public Optional<BreachSessionSnapshot> activeSession(UUID playerId) {
        UUID sessionId = playerSessions.get(playerId);
        if (sessionId == null) {
            return Optional.empty();
        }
        BreachSession session = sessions.get(sessionId);
        if (session == null) {
            playerSessions.remove(playerId, sessionId);
            return Optional.empty();
        }
        synchronized (session) {
            long now = System.currentTimeMillis();
            long elapsedSeconds = Math.max(1L, (now - session.startedAtEpochMillis) / 1000L);
            String activeAbility = session.pendingAbility == null ? "None" : session.pendingAbility.type.displayName;
            long reconnectGraceSeconds = Math.max(0L, (disconnectGraceMillis - Math.max(0L, now - session.lastCombatPresenceAtEpochMillis)) / 1000L);
            return Optional.of(new BreachSessionSnapshot(
                session.sessionId,
                session.partyInstance.worldName(),
                session.bossName,
                session.participants.size(),
                elapsedSeconds,
                session.timeLimitSeconds,
                session.phase,
                activeAbility,
                session.remainingRevives,
                session.totalRevives,
                activeFighterCount(session),
                session.eliminatedParticipants.size(),
                reconnectGraceSeconds
            ));
        }
    }

    public void openRewardCache(Player player) {
        List<PendingRewardCache> caches = rewardCaches(player.getUniqueId());
        if (caches.isEmpty()) {
            messageService.send(player,
                "No pending breach cache. Breach token balance: " + playerProfileService.breachTokenBalance(player) + "."
            );
            return;
        }

        if (caches.size() == 1) {
            menuBuilder.open(player, buildRewardDetailMenu(caches.getFirst()));
            return;
        }

        menuBuilder.open(player, buildRewardListMenu(caches));
    }

    public int pendingRewardCount(UUID playerId) {
        ConcurrentMap<UUID, PendingRewardCache> pending = rewardCaches.get(playerId);
        return pending == null ? 0 : pending.size();
    }

    public long breachTokenBalance(Player player) {
        return playerProfileService.breachTokenBalance(player);
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public int pendingRewardCacheCount() {
        return rewardCaches.values().stream().mapToInt(Map::size).sum();
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        sessions.clear();
        playerSessions.clear();
        worldSessions.clear();
        rewardCaches.clear();
    }

    public void setCompletionListener(BreachCompletionListener completionListener) {
        this.completionListeners.clear();
        if (completionListener != null) {
            this.completionListeners.add(completionListener);
        }
    }

    public void addCompletionListener(BreachCompletionListener completionListener) {
        if (completionListener != null) {
            this.completionListeners.addIfAbsent(completionListener);
        }
    }

    @Override
    public void close() {
        stop();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID sessionId = worldSessions.get(event.getEntity().getWorld().getName());
        if (sessionId == null) {
            return;
        }

        BreachSession session = sessions.get(sessionId);
        if (session == null) {
            worldSessions.remove(event.getEntity().getWorld().getName(), sessionId);
            return;
        }

        if (!event.getEntity().getUniqueId().equals(session.bossEntityId)) {
            return;
        }

        if (event.getEntity() instanceof LivingEntity livingEntity) {
            fractureLootService.rollDrop(session.fracture, livingEntity).ifPresent(drop -> {
                if (event.getEntity().getWorld() != null) {
                    event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), drop.item());
                }
            });
        }

        finishSuccess(session, event.getEntity().getKiller());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        UUID sessionId = playerSessions.get(event.getPlayer().getUniqueId());
        if (sessionId == null) {
            return;
        }
        BreachSession session = sessions.get(sessionId);
        if (session == null) {
            playerSessions.remove(event.getPlayer().getUniqueId(), sessionId);
            return;
        }

        if (!event.getPlayer().getWorld().getName().equals(session.partyInstance.worldName())) {
            notifySession(session, event.getPlayer().getName() + " left the breach instance.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID sessionId = playerSessions.get(player.getUniqueId());
        if (sessionId != null) {
            BreachSession session = sessions.get(sessionId);
            if (session != null && !session.closed) {
                synchronized (session) {
                    if (session.eliminatedParticipants.contains(player.getUniqueId())) {
                        messageService.send(player,
                            "Your party is still inside an active mythic breach, but you were eliminated from the fight."
                        );
                    } else {
                        messageService.send(player,
                            "You still have an active mythic breach. Use /expeditions breach to rejoin "
                                + session.partyInstance.worldName() + "."
                        );
                    }
                }
            }
        }

        int pendingCaches = pendingRewardCount(player.getUniqueId());
        if (pendingCaches > 0) {
            messageService.send(player,
                "You have " + pendingCaches + " unclaimed breach cache"
                    + (pendingCaches == 1 ? "" : "s")
                    + ". Use /expeditions rewards."
            );
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID sessionId = playerSessions.get(event.getPlayer().getUniqueId());
        if (sessionId == null) {
            return;
        }
        BreachSession session = sessions.get(sessionId);
        if (session != null) {
            synchronized (session) {
                session.disconnectedAtEpochMillis.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
            notifySession(session, event.getPlayer().getName() + " disconnected from the breach.");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID sessionId = playerSessions.get(player.getUniqueId());
        if (sessionId == null) {
            return;
        }

        BreachSession session = sessions.get(sessionId);
        if (session == null || !player.getWorld().getName().equals(session.partyInstance.worldName())) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);

        boolean revived;
        int remainingRevives;
        synchronized (session) {
            if (session.closed || session.eliminatedParticipants.contains(player.getUniqueId())) {
                return;
            }

            session.disconnectedAtEpochMillis.remove(player.getUniqueId());
            if (session.remainingRevives > 0) {
                session.remainingRevives--;
                session.pendingReviveRespawns.add(player.getUniqueId());
                revived = true;
            } else {
                session.eliminatedParticipants.add(player.getUniqueId());
                session.pendingEliminationRespawns.add(player.getUniqueId());
                revived = false;
            }
            remainingRevives = session.remainingRevives;
        }

        if (revived) {
            notifySession(session, player.getName() + " fell. Shared revives remaining: " + remainingRevives + ".");
        } else {
            notifySession(session, player.getName() + " was eliminated. No shared revives remain for them.");
        }

        long delayTicks = Math.max(1L, reviveRespawnDelayMillis / 50L);
        scheduler.runGlobalTimer("mythic-breach-auto-respawn-" + player.getUniqueId(), delayTicks, delayTicks, task -> {
            task.cancel();
            scheduler.runEntity(player, "mythic-breach-auto-respawn-entity-" + player.getUniqueId(), () -> {
                if (player.isOnline()) {
                    player.spigot().respawn();
                }
            });
        });
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID sessionId = playerSessions.get(player.getUniqueId());
        if (sessionId == null) {
            return;
        }

        BreachSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        boolean revived = false;
        boolean eliminated = false;
        Location respawnLocation = null;
        synchronized (session) {
            if (session.pendingReviveRespawns.remove(player.getUniqueId())) {
                respawnLocation = session.instanceSpawn.clone();
                session.lastCombatPresenceAtEpochMillis = System.currentTimeMillis();
                revived = true;
            } else if (session.pendingEliminationRespawns.remove(player.getUniqueId())) {
                respawnLocation = session.returnLocations.getOrDefault(player.getUniqueId(), fallbackLocation());
                eliminated = true;
            }
        }

        if (respawnLocation == null || respawnLocation.getWorld() == null) {
            return;
        }

        event.setRespawnLocation(respawnLocation);
        if (revived) {
            scheduler.runEntity(player, "mythic-breach-revive-complete-" + player.getUniqueId(), () -> {
                playerProfileService.refreshProfile(player);
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1, false, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, true, true));
                messageService.send(player, "A shared revive pulls you back into the breach.");
            });
        } else if (eliminated) {
            scheduler.runEntity(player, "mythic-breach-eliminated-" + player.getUniqueId(), () -> messageService.send(player,
                "You have been eliminated from the active breach. Wait for your party or claim rewards later if they clear it."
            ));
        }
    }

    private CompletableFuture<BreachLaunchResult> createSession(
        ExpeditionBreachContext context,
        FractureBossInstance instance,
        Location sourceLocation,
        Map<UUID, Location> returnLocations
    ) {
        CompletableFuture<BreachLaunchResult> future = new CompletableFuture<>();
        spawnBoss(instance.partyInstance(), instance.fracture(), context.participants())
            .whenComplete((spawnedBoss, error) -> {
                if (error != null) {
                    cleanupLaunchFailure(context, instance, sourceLocation, error);
                    future.complete(new BreachLaunchResult("Unable to start breach finale: " + error.getMessage(), false));
                    return;
                }

                BreachSession session = new BreachSession(
                    context.runId(),
                    context.expeditionId(),
                    sourceLocation.clone(),
                    instance.fracture(),
                    instance.partyInstance(),
                    Set.copyOf(context.participants()),
                    new LinkedHashMap<>(returnLocations),
                    spawnedBoss.entityId(),
                    spawnedBoss.maxHealth(),
                    bossName(instance.fracture()),
                    instance.partyInstance().spawnLocation().clone(),
                    context.contract().timeLimitSeconds(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + 5_000L,
                    sharedReviveCount
                );

                sessions.put(session.sessionId, session);
                worldSessions.put(session.partyInstance.worldName(), session.sessionId);
                session.participants.forEach(playerId -> playerSessions.put(playerId, session.sessionId));
                notifySession(session, "Mythic breach opened in " + session.partyInstance.worldName() + ". Defeat " + session.bossName + ".");
                future.complete(new BreachLaunchResult(
                    "Mythic breach launched in " + session.partyInstance.worldName() + ". Defeat " + session.bossName + ".",
                    true
                ));
            });
        return future;
    }

    private void cleanupLaunchFailure(
        ExpeditionBreachContext context,
        FractureBossInstance instance,
        Location sourceLocation,
        Throwable error
    ) {
        logger.warning("Unable to finish mythic breach launch: " + error.getMessage());
        worldThreatManager.resolveBossBreach(sourceLocation, context.expeditionId(), false)
            .whenComplete((ignored, resolveError) -> {
                if (resolveError != null) {
                    logger.warning("Unable to release failed breach claim: " + resolveError.getMessage());
                }
            });
        instanceController.destroyInstance(instance.partyInstance().partyId())
            .whenComplete((ignored, destroyError) -> {
                if (destroyError != null) {
                    logger.warning("Unable to destroy failed breach instance: " + destroyError.getMessage());
                }
            });
    }

    private CompletableFuture<SpawnedBoss> spawnBoss(PartyInstance instance, FractureRegionSnapshot fracture, Set<UUID> participants) {
        CompletableFuture<SpawnedBoss> future = new CompletableFuture<>();
        Location spawnLocation = instance.spawnLocation().clone().add(0.0D, 1.0D, 0.0D);
        scheduler.runRegion(spawnLocation, "mythic-breach-spawn-" + instance.worldName(), () -> {
            World world = spawnLocation.getWorld();
            if (world == null) {
                future.completeExceptionally(new IllegalStateException("Breach instance world is not loaded."));
                return;
            }

            EntityType configuredBossType = fracture.profile().bossType() == null ? bossEntityType : fracture.profile().bossType();
            Entity entity = world.spawnEntity(spawnLocation, configuredBossType);
            if (!(entity instanceof Monster monster)) {
                entity.remove();
                future.completeExceptionally(new IllegalStateException("Configured breach boss type must be a monster."));
                return;
            }

            double totalHealth = bossBaseHealth
                + (fracture.state().severity() * 85.0D)
                + (fracture.threatScore() * 0.85D)
                + (participants.size() * 60.0D);
            totalHealth *= fracture.profile().bossHealthMultiplier();
            scaleAttribute(monster.getAttribute(Attribute.MAX_HEALTH), totalHealth, true);
            monster.setHealth(totalHealth);
            scaleAttribute(
                monster.getAttribute(Attribute.ATTACK_DAMAGE),
                (bossDamageMultiplier + (fracture.state().severity() * 0.35D)) * fracture.profile().bossDamageMultiplier(),
                false
            );
            addFlat(monster.getAttribute(Attribute.ARMOR), 8.0D + (fracture.state().severity() * 2.0D));
            scaleAttribute(monster.getAttribute(Attribute.KNOCKBACK_RESISTANCE), 1.0D, true);
            scaleAttribute(monster.getAttribute(Attribute.FOLLOW_RANGE), 48.0D, true);

            monster.customName(Component.text(bossName(fracture)));
            monster.setCustomNameVisible(true);
            monster.setGlowing(true);
            monster.setPersistent(true);
            monster.setRemoveWhenFarAway(false);

            PersistentDataContainer data = monster.getPersistentDataContainer();
            data.set(breachBossKey, PersistentDataType.BYTE, (byte) 1);
            data.set(breachSessionKey, PersistentDataType.STRING, instance.partyId().toString());
            fracture.profile().bossEffects().forEach(effectId -> effectService.applyEffectToEntity(monster, effectId, 1, 0, 1.0D, true)
                .exceptionally(error -> {
                    logger.fine("Unable to apply fracture boss effect " + effectId + ": " + error.getMessage());
                    return null;
                }));

            world.spawnParticle(Particle.SOUL_FIRE_FLAME, spawnLocation, 40, 1.2D, 1.0D, 1.2D, 0.02D);
            world.playSound(spawnLocation, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2F, 0.75F);
            future.complete(new SpawnedBoss(monster.getUniqueId(), totalHealth));
        });
        return future;
    }

    private void finishSuccess(BreachSession session, Player killer) {
        if (!markClosed(session)) {
            return;
        }

        expeditionBoardManager.completeBreachRun(session.sessionId, bossScoreBonus + (session.fracture.threatScore() * 0.50D))
            .ifPresent(runCompletion -> runCompletion.participants().forEach(playerId -> {
                PendingRewardCache rewardCache = createRewardCache(session, runCompletion.completion());
                rewardCaches.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(rewardCache.cacheId, rewardCache);
                for (BreachCompletionListener completionListener : completionListeners) {
                    completionListener.onBreachClear(playerId, runCompletion.completion(), rewardCache.tokenReward);
                }

                Player member = plugin.getServer().getPlayer(playerId);
                if (member == null || !member.isOnline()) {
                    return;
                }
                vaultEconomyBridge.rewardExpedition(member, runCompletion.completion())
                    .thenAccept(economyReward -> scheduler.runEntity(member, "breach-success-" + member.getUniqueId(), () -> {
                        messageService.send(member,
                            "Breach cleared! Rank " + runCompletion.completion().scoreTier().name()
                                + " | Score " + String.format(Locale.US, "%.1f", runCompletion.completion().finalScore())
                                + " | Clear " + formatDuration(runCompletion.completion().clearSeconds())
                                + " | Cache ready with " + rewardCache.tokenReward + " breach tokens"
                                + (economyReward.isPresent() ? " | +" + vaultEconomyBridge.formatAmount(economyReward.getAsDouble()) : "")
                        );
                        openRewardCache(member);
                    }));
            }));

        worldThreatManager.resolveBossBreach(session.sourceLocation, session.expeditionId, true)
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warning("Unable to resolve successful breach: " + error.getMessage());
                }
                returnPlayers(session, "The breach is sealed.");
                destroySessionWorld(session);
            });

        if (killer != null) {
            messageService.send(killer, "Champion defeated. The fracture is collapsing.");
        }
    }

    private void finishFailure(BreachSession session, String reason) {
        if (!markClosed(session)) {
            return;
        }

        expeditionBoardManager.setBreachLaunched(session.sessionId, false);
        worldThreatManager.resolveBossBreach(session.sourceLocation, session.expeditionId, false)
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warning("Unable to resolve failed breach: " + error.getMessage());
                }
                notifySession(session, reason);
                returnPlayers(session, "You are pulled back from the failed breach.");
                destroySessionWorld(session);
            });
    }

    private boolean markClosed(BreachSession session) {
        synchronized (session) {
            if (session.closed) {
                return false;
            }
            session.closed = true;
            return true;
        }
    }

    private void destroySessionWorld(BreachSession session) {
        sessions.remove(session.sessionId, session);
        worldSessions.remove(session.partyInstance.worldName(), session.sessionId);
        session.participants.forEach(playerId -> playerSessions.remove(playerId, session.sessionId));
        instanceController.destroyInstance(session.partyInstance.partyId())
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warning("Unable to destroy breach instance " + session.partyInstance.worldName() + ": " + error.getMessage());
                }
            });
    }

    private void returnPlayers(BreachSession session, String message) {
        for (UUID playerId : session.participants) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location target = session.returnLocations.getOrDefault(playerId, fallbackLocation());
            scheduler.runEntity(player, "breach-return-" + playerId, () -> {
                messageService.send(player, message);
                player.teleportAsync(target);
            });
        }
    }

    private CompletableFuture<BreachLaunchResult> rejoinSession(Player player, BreachSession session) {
        CompletableFuture<BreachLaunchResult> future = new CompletableFuture<>();
        scheduler.runEntity(player, "breach-rejoin-" + player.getUniqueId(), () -> {
            synchronized (session) {
                if (session.eliminatedParticipants.contains(player.getUniqueId())) {
                    future.complete(new BreachLaunchResult("You were eliminated from this breach and cannot rejoin the fight.", false));
                    return;
                }
            }
            player.teleportAsync(session.instanceSpawn.clone());
            future.complete(new BreachLaunchResult(
                "Rejoined active breach in " + session.partyInstance.worldName() + ".",
                true
            ));
        });
        return future;
    }

    private void tickSessions() {
        for (BreachSession session : List.copyOf(sessions.values())) {
            if (session.closed) {
                continue;
            }

            long elapsedSeconds = Math.max(1L, (System.currentTimeMillis() - session.startedAtEpochMillis) / 1000L);
            if (elapsedSeconds >= session.timeLimitSeconds) {
                finishFailure(session, "The breach timer expired before the champion fell.");
                continue;
            }

            long now = System.currentTimeMillis();
            int activeFighters = activeFighterCount(session);
            if (activeFighters > 0) {
                synchronized (session) {
                    session.lastCombatPresenceAtEpochMillis = now;
                }
            } else {
                boolean waitingOnRespawn;
                int eligiblePlayers;
                long absentMillis;
                synchronized (session) {
                    waitingOnRespawn = !session.pendingReviveRespawns.isEmpty();
                    eligiblePlayers = session.participants.size() - session.eliminatedParticipants.size();
                    absentMillis = now - session.lastCombatPresenceAtEpochMillis;
                }
                if (waitingOnRespawn && absentMillis < disconnectGraceMillis) {
                    continue;
                }

                if (absentMillis >= disconnectGraceMillis) {
                    if (eligiblePlayers <= 0) {
                        finishFailure(session, "The expedition party was eliminated inside the breach.");
                    } else if (connectedParticipantCount(session) <= 0) {
                        finishFailure(session, "The breach collapsed after the party lost connection.");
                    } else {
                        finishFailure(session, "The expedition party wiped and exhausted its shared revives.");
                    }
                    continue;
                }
            }

            scheduler.runRegion(session.instanceSpawn, "mythic-breach-combat-" + session.sessionId, () -> tickSessionCombat(session));
        }
    }

    private void tickSessionCombat(BreachSession session) {
        if (session.closed) {
            return;
        }

        long now = System.currentTimeMillis();
        Entity raw = Bukkit.getEntity(session.bossEntityId);
        if (!(raw instanceof Monster boss) || !boss.isValid()) {
            if ((now - session.lastBossSeenAtEpochMillis) >= BOSS_MISSING_FAIL_MILLIS) {
                finishFailure(session, "The breach destabilized after the champion vanished.");
            }
            return;
        }

        session.lastBossSeenAtEpochMillis = now;
        double healthPercent = Math.max(0.0D, boss.getHealth() / Math.max(1.0D, session.bossMaxHealth));
        int desiredPhase = resolvePhase(healthPercent);
        while (session.phase < desiredPhase) {
            advancePhase(session, boss, session.phase + 1);
        }

        if (session.pendingAbility != null) {
            if (now >= session.pendingAbility.executeAtEpochMillis) {
                PendingAbility pending = session.pendingAbility;
                session.pendingAbility = null;
                resolveAbility(session, boss, pending);
                session.nextAbilityAtEpochMillis = now + abilityCooldownMillis(session.phase);
            }
            return;
        }

        if (now >= session.nextAbilityAtEpochMillis) {
            beginAbility(session, boss, now);
        }
    }

    private int resolvePhase(double healthPercent) {
        if (healthPercent <= phaseFourThreshold) {
            return 4;
        }
        if (healthPercent <= phaseThreeThreshold) {
            return 3;
        }
        if (healthPercent <= phaseTwoThreshold) {
            return 2;
        }
        return 1;
    }

    private void advancePhase(BreachSession session, Monster boss, int nextPhase) {
        session.phase = Math.max(session.phase, nextPhase);
        switch (session.phase) {
            case 2 -> {
                addFlat(boss.getAttribute(Attribute.ARMOR), 4.0D);
                scaleAttribute(boss.getAttribute(Attribute.ATTACK_DAMAGE), bossDamageMultiplier + 1.0D, false);
                notifySession(session, "Phase 2: " + session.bossName + " hardens and begins casting Void Lance.");
                boss.getWorld().spawnParticle(Particle.ENCHANT, boss.getLocation(), 36, 1.0D, 1.2D, 1.0D, 0.1D);
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.1F, 1.1F);
            }
            case 3 -> {
                healPercent(boss, 0.08D);
                scaleAttribute(boss.getAttribute(Attribute.MOVEMENT_SPEED), 0.33D, false);
                notifySession(session, "Phase 3: the breach tears wider and adds rift summons to the fight.");
                boss.getWorld().spawnParticle(Particle.PORTAL, boss.getLocation(), 48, 1.6D, 1.0D, 1.6D, 0.3D);
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0F, 0.75F);
            }
            case 4 -> {
                healPercent(boss, 0.12D);
                addFlat(boss.getAttribute(Attribute.ARMOR), 6.0D);
                scaleAttribute(boss.getAttribute(Attribute.ATTACK_DAMAGE), bossDamageMultiplier + 2.4D, false);
                notifySession(session, "Phase 4: " + session.bossName + " is enraged. Shadow Nova is now active.");
                boss.getWorld().spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, boss.getLocation(), 64, 2.0D, 1.0D, 2.0D, 0.4D);
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.25F, 0.9F);
            }
            default -> {
            }
        }
        session.nextAbilityAtEpochMillis = Math.min(session.nextAbilityAtEpochMillis, System.currentTimeMillis() + 1_500L);
    }

    private void beginAbility(BreachSession session, Monster boss, long now) {
        BreachAbility ability = selectAbility(session);
        UUID targetPlayerId = ability.requiresTarget ? Optional.ofNullable(preferredTarget(session)).map(Player::getUniqueId).orElse(null) : null;
        PendingAbility pending = new PendingAbility(ability, now + ability.telegraphMillis, targetPlayerId);
        session.pendingAbility = pending;
        session.abilityRotation++;
        telegraphAbility(session, boss, pending);
    }

    private BreachAbility selectAbility(BreachSession session) {
        List<BreachAbility> rotation = switch (session.phase) {
            case 1 -> List.of(BreachAbility.TITAN_SLAM, BreachAbility.VOID_LANCE);
            case 2 -> List.of(BreachAbility.TITAN_SLAM, BreachAbility.VOID_LANCE, BreachAbility.SUMMON_RIFT);
            case 3 -> List.of(BreachAbility.VOID_LANCE, BreachAbility.SUMMON_RIFT, BreachAbility.TITAN_SLAM);
            default -> List.of(BreachAbility.SHADOW_NOVA, BreachAbility.VOID_LANCE, BreachAbility.SUMMON_RIFT, BreachAbility.TITAN_SLAM);
        };
        return rotation.get(Math.floorMod(session.abilityRotation, rotation.size()));
    }

    private Player preferredTarget(BreachSession session) {
        Player bossTarget = Optional.ofNullable(Bukkit.getEntity(session.bossEntityId))
            .filter(Monster.class::isInstance)
            .map(Monster.class::cast)
            .map(Monster::getTarget)
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .orElse(null);
        if (bossTarget != null && bossTarget.isOnline()) {
            return bossTarget;
        }

        for (UUID participantId : session.participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline()) {
                return player;
            }
        }
        return null;
    }

    private void telegraphAbility(BreachSession session, Monster boss, PendingAbility pending) {
        Location bossLocation = boss.getLocation().clone();
        String warning = switch (pending.type) {
            case TITAN_SLAM -> "Titan Slam incoming. Move away from the boss.";
            case VOID_LANCE -> "Void Lance is locking onto a target.";
            case SUMMON_RIFT -> "The champion is opening a rift. Prepare for adds.";
            case SHADOW_NOVA -> "Shadow Nova charging. Clear the center.";
        };

        boss.getWorld().spawnParticle(pending.type.telegraphParticle, bossLocation, pending.type.particleCount, 1.4D, 1.0D, 1.4D, 0.15D);
        boss.getWorld().playSound(bossLocation, pending.type.telegraphSound, 1.0F, pending.type.telegraphPitch);
        notifySession(session, warning);

        if (pending.targetPlayerId == null) {
            return;
        }

        Player target = Bukkit.getPlayer(pending.targetPlayerId);
        if (target == null || !target.isOnline()) {
            return;
        }
        scheduler.runEntity(target, "mythic-breach-target-warning-" + target.getUniqueId(), () -> {
            if (!target.isOnline()) {
                return;
            }
            messageService.send(target, "You are targeted by " + pending.type.displayName + ".");
            target.getWorld().spawnParticle(Particle.WITCH, target.getLocation().add(0.0D, 1.0D, 0.0D), 18, 0.35D, 0.5D, 0.35D, 0.02D);
            target.playSound(target.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9F, 1.35F);
        });
    }

    private void resolveAbility(BreachSession session, Monster boss, PendingAbility pending) {
        switch (pending.type) {
            case TITAN_SLAM -> resolveTitanSlam(session, boss);
            case VOID_LANCE -> resolveVoidLance(session, boss, pending);
            case SUMMON_RIFT -> resolveSummonRift(session, boss);
            case SHADOW_NOVA -> resolveShadowNova(session, boss);
        }
    }

    private void resolveTitanSlam(BreachSession session, Monster boss) {
        Location origin = boss.getLocation().clone();
        double radius = 4.5D + (session.phase * 0.75D);
        double damage = 6.0D + (session.phase * 2.5D);

        boss.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, origin, 2, 0.4D, 0.2D, 0.4D, 0.0D);
        boss.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1.1F, 0.8F);

        forEachParticipantInWorld(session, player -> {
            Location playerLocation = player.getLocation();
            if (playerLocation.distanceSquared(origin) > (radius * radius)) {
                return;
            }

            Vector knockback = playerLocation.toVector().subtract(origin.toVector());
            if (knockback.lengthSquared() < 0.01D) {
                knockback = new Vector(0.0D, 0.4D, 0.0D);
            } else {
                knockback.normalize().multiply(1.1D + (session.phase * 0.15D)).setY(0.45D);
            }

            player.damage(damage);
            player.setVelocity(knockback);
            messageService.send(player, "Titan Slam hits you for " + String.format(Locale.US, "%.1f", damage) + " damage.");
        });
    }

    private void resolveVoidLance(BreachSession session, Monster boss, PendingAbility pending) {
        UUID targetPlayerId = pending.targetPlayerId;
        if (targetPlayerId == null) {
            resolveTitanSlam(session, boss);
            return;
        }

        Player target = Bukkit.getPlayer(targetPlayerId);
        if (target == null || !target.isOnline()) {
            resolveTitanSlam(session, boss);
            return;
        }

        Location origin = boss.getLocation().clone().add(0.0D, 1.2D, 0.0D);
        scheduler.runEntity(target, "mythic-breach-void-lance-" + targetPlayerId, () -> {
            if (!target.isOnline() || !target.getWorld().getName().equals(session.partyInstance.worldName())) {
                return;
            }

            Location targetLocation = target.getLocation().clone().add(0.0D, 1.0D, 0.0D);
            target.getWorld().spawnParticle(Particle.SONIC_BOOM, targetLocation, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            target.getWorld().spawnParticle(Particle.GLOW, targetLocation, 22, 0.5D, 0.6D, 0.5D, 0.03D);
            target.getWorld().playSound(targetLocation, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0F, 1.15F);
            target.damage(9.0D + (session.phase * 3.0D));
            messageService.send(target, "Void Lance pierces through you.");

            World world = origin.getWorld();
            if (world != null && world.getName().equals(target.getWorld().getName())) {
                world.spawnParticle(Particle.END_ROD, origin, 12, 0.1D, 0.1D, 0.1D, 0.0D);
            }
        });
    }

    private void resolveSummonRift(BreachSession session, Monster boss) {
        World world = boss.getWorld();
        Location center = boss.getLocation().clone();
        int summonCount = Math.min(5, 2 + session.phase);

        for (int index = 0; index < summonCount; index++) {
            double angle = (Math.PI * 2.0D * index) / summonCount;
            Location spawn = center.clone().add(Math.cos(angle) * 3.0D, 0.0D, Math.sin(angle) * 3.0D);
            Entity entity = world.spawnEntity(spawn, selectMinionType(session.phase));
            if (!(entity instanceof Monster minion)) {
                entity.remove();
                continue;
            }

            double health = 18.0D + (session.phase * 8.0D);
            scaleAttribute(minion.getAttribute(Attribute.MAX_HEALTH), health, true);
            minion.setHealth(health);
            scaleAttribute(minion.getAttribute(Attribute.ATTACK_DAMAGE), 3.0D + session.phase, false);
            minion.customName(Component.text("Fractured Minion"));
            minion.setCustomNameVisible(false);
            minion.setPersistent(false);
        }

        world.spawnParticle(Particle.PORTAL, center, 40, 1.5D, 0.8D, 1.5D, 0.2D);
        world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 1.0F, 1.1F);
        notifySession(session, "Fractured reinforcements pour into the arena.");
    }

    private void resolveShadowNova(BreachSession session, Monster boss) {
        Location origin = boss.getLocation().clone();
        double radius = 6.5D;
        double damage = 12.0D + (session.phase * 2.5D);

        boss.getWorld().spawnParticle(Particle.DRAGON_BREATH, origin, 55, 2.2D, 0.6D, 2.2D, 0.08D);
        boss.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0F, 0.7F);

        forEachParticipantInWorld(session, player -> {
            Location location = player.getLocation();
            if (location.distanceSquared(origin) > (radius * radius)) {
                return;
            }

            Vector knockback = location.toVector().subtract(origin.toVector());
            if (knockback.lengthSquared() > 0.01D) {
                knockback.normalize().multiply(1.35D).setY(0.35D);
                player.setVelocity(knockback);
            }
            player.damage(damage);
            messageService.send(player, "Shadow Nova detonates around the boss.");
        });
    }

    private void forEachParticipantInWorld(BreachSession session, ParticipantAction action) {
        for (UUID participantId : session.participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            scheduler.runEntity(player, "mythic-breach-participant-" + participantId, () -> {
                if (!player.isOnline() || !player.getWorld().getName().equals(session.partyInstance.worldName())) {
                    return;
                }
                action.accept(player);
            });
        }
    }

    private long abilityCooldownMillis(int phase) {
        long reduction = Math.max(0L, (phase - 1L) * 1_200L);
        return Math.max(4_000L, baseAbilityCooldownMillis - reduction);
    }

    private void notifySession(BreachSession session, String message) {
        forEachParticipantInWorld(session, player -> messageService.send(player, message));
    }

    private int activeFighterCount(BreachSession session) {
        int count = 0;
        synchronized (session) {
            for (UUID participantId : session.participants) {
                if (session.eliminatedParticipants.contains(participantId)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(participantId);
                if (player == null || !player.isOnline() || player.isDead()) {
                    continue;
                }
                if (!player.getWorld().getName().equals(session.partyInstance.worldName())) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private int connectedParticipantCount(BreachSession session) {
        int count = 0;
        synchronized (session) {
            for (UUID participantId : session.participants) {
                if (session.eliminatedParticipants.contains(participantId)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(participantId);
                if (player != null && player.isOnline()) {
                    count++;
                }
            }
        }
        return count;
    }

    private PendingRewardCache createRewardCache(BreachSession session, ExpeditionCompletion completion) {
        long tokenReward = baseTokenReward
            + (session.fracture.state().severity() * tokenSeverityBonus)
            + switch (completion.scoreTier()) {
                case S -> 18L;
                case A -> 12L;
                case B -> 8L;
                case C -> 4L;
            };

        return new PendingRewardCache(
            UUID.randomUUID(),
            session.bossName + " Cache",
            completion.scoreTier(),
            completion.finalScore(),
            completion.clearSeconds(),
            completion.experienceReward(),
            tokenReward,
            fractureLootService.buildBreachCacheRewards(session.fracture, completion.scoreTier()),
            System.currentTimeMillis()
        );
    }

    private List<PendingRewardCache> rewardCaches(UUID playerId) {
        ConcurrentMap<UUID, PendingRewardCache> pending = rewardCaches.get(playerId);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return pending.values().stream()
            .sorted((left, right) -> Long.compare(right.createdAtEpochMillis, left.createdAtEpochMillis))
            .toList();
    }

    private VortexMenuBuilder.MenuDefinition buildRewardListMenu(List<PendingRewardCache> caches) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Breach Cache"), 4);
        builder.button(4, icon(Material.NETHER_STAR, "Pending Caches", List.of(
            "Caches Ready: " + caches.size(),
            "Select one to inspect and claim."
        )), context -> CompletableFuture.completedFuture(null));

        int slot = 10;
        for (PendingRewardCache cache : caches) {
            if (slot >= 34) {
                break;
            }
            builder.button(slot++, icon(Material.CHEST, cache.title, List.of(
                "Rank: " + cache.scoreTier.name(),
                "Score: " + String.format(Locale.US, "%.1f", cache.finalScore),
                "XP: " + cache.experienceReward,
                "Tokens: " + cache.tokenReward,
                "Items: " + cache.rewardItems.size()
            )), context -> {
                context.open(buildRewardDetailMenu(cache));
                return CompletableFuture.completedFuture(null);
            });
        }

        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildRewardDetailMenu(PendingRewardCache cache) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Breach Cache"), 4);
        builder.button(4, icon(Material.NETHER_STAR, cache.title, List.of(
            "Rank: " + cache.scoreTier.name(),
            "Score: " + String.format(Locale.US, "%.1f", cache.finalScore),
            "Clear: " + formatDuration(cache.clearSeconds),
            "XP: " + cache.experienceReward,
            "Tokens: " + cache.tokenReward
        )), context -> CompletableFuture.completedFuture(null));

        int[] rewardSlots = {19, 20, 21, 22, 23, 24, 25};
        for (int index = 0; index < Math.min(rewardSlots.length, cache.rewardItems.size()); index++) {
            ItemStack reward = cache.rewardItems.get(index).clone();
            builder.button(rewardSlots[index], reward, context -> CompletableFuture.completedFuture(null));
        }

        builder.button(31, icon(Material.LIME_CONCRETE, "Claim Cache", List.of(
            "Adds all listed items to your inventory.",
            "Any overflow drops at your feet.",
            "Also grants XP and breach tokens."
        )), context -> claimRewardCache(context.player(), cache), true);

        return builder.build();
    }

    private CompletableFuture<Void> claimRewardCache(Player player, PendingRewardCache cache) {
        ConcurrentMap<UUID, PendingRewardCache> pending = rewardCaches.get(player.getUniqueId());
        if (pending == null || pending.remove(cache.cacheId, cache) == false) {
            scheduler.runEntity(player, "breach-cache-missing-" + player.getUniqueId(), () -> messageService.send(player,
                "That breach cache has already been claimed or expired."
            ));
            return CompletableFuture.completedFuture(null);
        }
        if (pending.isEmpty()) {
            rewardCaches.remove(player.getUniqueId(), pending);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        vaultEconomyBridge.rewardBreachCache(player, cache.experienceReward, cache.tokenReward)
            .thenAccept(economyReward -> scheduler.runEntity(player, "breach-cache-claim-" + player.getUniqueId(), () -> {
                playerProfileService.grantExperience(player, cache.experienceReward, "claiming a mythic breach cache");
                playerProfileService.grantBreachTokens(player, cache.tokenReward, "claiming a mythic breach cache");

                for (ItemStack reward : cache.rewardItems) {
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(reward.clone());
                    overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }

                messageService.send(player,
                    "Claimed breach cache: " + cache.rewardItems.size()
                        + " item(s), +" + cache.experienceReward + " RPG XP, +" + cache.tokenReward + " breach tokens."
                        + (economyReward.isPresent() ? " +" + vaultEconomyBridge.formatAmount(economyReward.getAsDouble()) : "")
                );
                future.complete(null);
            }));
        return future;
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private void scaleAttribute(AttributeInstance attribute, double value, boolean clampCurrentHealth) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(Math.max(0.0D, value));
    }

    private void addFlat(AttributeInstance attribute, double delta) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(Math.max(0.0D, attribute.getBaseValue() + delta));
    }

    private void healPercent(LivingEntity entity, double percent) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double maximum = maxHealth.getValue();
        double nextHealth = Math.min(maximum, entity.getHealth() + (maximum * Math.max(0.0D, percent)));
        entity.setHealth(Math.max(1.0D, nextHealth));
    }

    private Location sourceLocation(FractureRegionSnapshot fracture) {
        World world = Bukkit.getWorld(fracture.key().worldId());
        if (world == null) {
            return new Location(null, 0.0D, 0.0D, 0.0D);
        }
        int blockX = (fracture.key().chunkX() << 4) + 8;
        int blockZ = (fracture.key().chunkZ() << 4) + 8;
        return new Location(world, blockX + 0.5D, world.getHighestBlockYAt(blockX, blockZ) + 1.0D, blockZ + 0.5D);
    }

    private Location fallbackLocation() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return new Location(null, 0.0D, 80.0D, 0.0D);
        }
        return worlds.get(0).getSpawnLocation();
    }

    private EntityType parseBossEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return EntityType.WITHER_SKELETON;
        }
        try {
            EntityType parsed = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return parsed.isSpawnable() && parsed.isAlive() ? parsed : EntityType.WITHER_SKELETON;
        } catch (IllegalArgumentException exception) {
            logger.warning("Unknown breach boss type '" + raw + "'. Falling back to WITHER_SKELETON.");
            return EntityType.WITHER_SKELETON;
        }
    }

    private EntityType selectMinionType(int phase) {
        return switch (phase) {
            case 1 -> EntityType.ZOMBIE;
            case 2 -> EntityType.HUSK;
            case 3 -> EntityType.STRAY;
            default -> EntityType.WITHER_SKELETON;
        };
    }

    private String bossName(FractureRegionSnapshot fracture) {
        String prefix = fracture.profile().displayName();
        return switch (fracture.state()) {
            case BOSS_BREACH -> prefix + " Sovereign";
            case OVERRUN -> prefix + " Harbinger";
            case CORRUPTED -> prefix + " Marshal";
            case UNSTABLE -> prefix + " Warden";
            case PURIFIED -> prefix + " Echo";
        };
    }

    private CompletableFuture<BreachLaunchResult> completed(String message) {
        return CompletableFuture.completedFuture(new BreachLaunchResult(message, false));
    }

    private String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    private interface ParticipantAction {
        void accept(Player player);
    }

    public static record BreachLaunchResult(String message, boolean success) {
    }

    private record SpawnedBoss(UUID entityId, double maxHealth) {
    }

    private record PendingAbility(BreachAbility type, long executeAtEpochMillis, UUID targetPlayerId) {
    }

    private record PendingRewardCache(
        UUID cacheId,
        String title,
        ExpeditionScoreTier scoreTier,
        double finalScore,
        long clearSeconds,
        int experienceReward,
        long tokenReward,
        List<ItemStack> rewardItems,
        long createdAtEpochMillis
    ) {

        private PendingRewardCache {
            rewardItems = List.copyOf(rewardItems);
        }
    }

    private enum BreachAbility {
        TITAN_SLAM("Titan Slam", 2_000L, false, Particle.CRIT, 24, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8F),
        VOID_LANCE("Void Lance", 1_500L, true, Particle.SCULK_SOUL, 20, Sound.BLOCK_BEACON_POWER_SELECT, 1.25F),
        SUMMON_RIFT("Summon Rift", 1_500L, false, Particle.PORTAL, 28, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.95F),
        SHADOW_NOVA("Shadow Nova", 2_000L, false, Particle.DRAGON_BREATH, 34, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.75F);

        private final String displayName;
        private final long telegraphMillis;
        private final boolean requiresTarget;
        private final Particle telegraphParticle;
        private final int particleCount;
        private final Sound telegraphSound;
        private final float telegraphPitch;

        BreachAbility(
            String displayName,
            long telegraphMillis,
            boolean requiresTarget,
            Particle telegraphParticle,
            int particleCount,
            Sound telegraphSound,
            float telegraphPitch
        ) {
            this.displayName = displayName;
            this.telegraphMillis = telegraphMillis;
            this.requiresTarget = requiresTarget;
            this.telegraphParticle = telegraphParticle;
            this.particleCount = particleCount;
            this.telegraphSound = telegraphSound;
            this.telegraphPitch = telegraphPitch;
        }
    }

    private static final class BreachSession {

        private final UUID sessionId;
        private final UUID expeditionId;
        private final Location sourceLocation;
        private final FractureRegionSnapshot fracture;
        private final PartyInstance partyInstance;
        private final Set<UUID> participants;
        private final Map<UUID, Location> returnLocations;
        private final UUID bossEntityId;
        private final double bossMaxHealth;
        private final String bossName;
        private final Location instanceSpawn;
        private final long timeLimitSeconds;
        private final long startedAtEpochMillis;
        private final int totalRevives;
        private final Set<UUID> pendingReviveRespawns;
        private final Set<UUID> pendingEliminationRespawns;
        private final Set<UUID> eliminatedParticipants;
        private final Map<UUID, Long> disconnectedAtEpochMillis;
        private long lastBossSeenAtEpochMillis;
        private long lastCombatPresenceAtEpochMillis;
        private long nextAbilityAtEpochMillis;
        private int remainingRevives;
        private int phase;
        private int abilityRotation;
        private PendingAbility pendingAbility;
        private boolean closed;

        private BreachSession(
            UUID sessionId,
            UUID expeditionId,
            Location sourceLocation,
            FractureRegionSnapshot fracture,
            PartyInstance partyInstance,
            Set<UUID> participants,
            Map<UUID, Location> returnLocations,
            UUID bossEntityId,
            double bossMaxHealth,
            String bossName,
            Location instanceSpawn,
            long timeLimitSeconds,
            long startedAtEpochMillis,
            long nextAbilityAtEpochMillis,
            int totalRevives
        ) {
            this.sessionId = sessionId;
            this.expeditionId = expeditionId;
            this.sourceLocation = sourceLocation;
            this.fracture = fracture;
            this.partyInstance = partyInstance;
            this.participants = new LinkedHashSet<>(participants);
            this.returnLocations = new LinkedHashMap<>(returnLocations);
            this.bossEntityId = bossEntityId;
            this.bossMaxHealth = bossMaxHealth;
            this.bossName = bossName;
            this.instanceSpawn = instanceSpawn;
            this.timeLimitSeconds = timeLimitSeconds;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.totalRevives = totalRevives;
            this.pendingReviveRespawns = new LinkedHashSet<>();
            this.pendingEliminationRespawns = new LinkedHashSet<>();
            this.eliminatedParticipants = new LinkedHashSet<>();
            this.disconnectedAtEpochMillis = new LinkedHashMap<>();
            this.lastBossSeenAtEpochMillis = startedAtEpochMillis;
            this.lastCombatPresenceAtEpochMillis = startedAtEpochMillis;
            this.nextAbilityAtEpochMillis = nextAbilityAtEpochMillis;
            this.remainingRevives = totalRevives;
            this.phase = 1;
            this.abilityRotation = 0;
            this.pendingAbility = null;
            this.closed = false;
        }
    }

    public interface BreachCompletionListener {

        void onBreachClear(UUID playerId, ExpeditionCompletion completion, long tokenReward);
    }
}
