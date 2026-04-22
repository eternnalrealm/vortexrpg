package io.vortexcore.world;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class RiftSpawnController implements Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final WorldThreatManager worldThreatManager;
    private final FractureLootService lootService;
    private final ExpeditionBoardManager expeditionBoardManager;
    private final PlayerProfileService playerProfileService;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final EffectService effectService;
    private final double suppressionPerKill;
    private final long visualPulseTicks;
    private final NamespacedKey fracturedKey;
    private final NamespacedKey stateKey;
    private final NamespacedKey scoreKey;
    private final CopyOnWriteArrayList<ExpeditionCompletionListener> completionListeners;
    private VortexTask visualTask;

    public RiftSpawnController(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        WorldThreatManager worldThreatManager,
        FractureLootService lootService,
        ExpeditionBoardManager expeditionBoardManager,
        PlayerProfileService playerProfileService,
        VaultEconomyBridge vaultEconomyBridge,
        EffectService effectService,
        double suppressionPerKill,
        long visualPulseTicks
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.worldThreatManager = worldThreatManager;
        this.lootService = lootService;
        this.expeditionBoardManager = expeditionBoardManager;
        this.playerProfileService = playerProfileService;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.effectService = effectService;
        this.suppressionPerKill = suppressionPerKill;
        this.visualPulseTicks = Math.max(10L, visualPulseTicks);
        this.fracturedKey = new NamespacedKey(plugin, "fractured_mob");
        this.stateKey = new NamespacedKey(plugin, "fractured_state");
        this.scoreKey = new NamespacedKey(plugin, "fractured_score");
        this.completionListeners = new CopyOnWriteArrayList<>();
    }

    public void start() {
        if (visualTask != null) {
            return;
        }
        scheduler.runGlobal("fracture-visual-teams-init", this::ensureGlowTeams);
        visualTask = scheduler.runGlobalTimer("fracture-visual-pulse", visualPulseTicks, visualPulseTicks, task -> pulseActiveFractures());
    }

    public void stop() {
        if (visualTask != null) {
            visualTask.cancel();
            visualTask = null;
        }
        clearGlowTeams();
    }

    public void setCompletionListener(ExpeditionCompletionListener completionListener) {
        this.completionListeners.clear();
        if (completionListener != null) {
            this.completionListeners.add(completionListener);
        }
    }

    public void addCompletionListener(ExpeditionCompletionListener completionListener) {
        if (completionListener != null) {
            this.completionListeners.addIfAbsent(completionListener);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }

        if (monster.getPersistentDataContainer().has(fracturedKey, PersistentDataType.BYTE)) {
            return;
        }

        Optional<FractureRegionSnapshot> fracture = worldThreatManager.fractureAt(event.getLocation());
        if (fracture.isEmpty() || fracture.get().state() == FractureState.PURIFIED) {
            return;
        }
        if (!fracture.get().profile().allowsMob(monster.getType())) {
            return;
        }

        scheduler.runEntity(monster, "fracture-affix-" + monster.getUniqueId(), () -> applyFracture(monster, fracture.get()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }

        PersistentDataContainer data = monster.getPersistentDataContainer();
        if (!data.has(fracturedKey, PersistentDataType.BYTE)) {
            return;
        }

        clearGlowTeam(monster.getUniqueId());

        Location location = monster.getLocation();
        Player killer = event.getEntity().getKiller();
        Optional<FractureRegionSnapshot> fracture = worldThreatManager.fractureAt(location);
        fracture.ifPresent(snapshot -> {
            AttributeInstance maxHealth = monster.getAttribute(Attribute.MAX_HEALTH);
            double effort = suppressionPerKill + snapshot.state().severity() + ((maxHealth == null ? monster.getHealth() : maxHealth.getValue()) / 18.0D);
            worldThreatManager.contributeSuppression(location, effort);

            lootService.rollDrop(snapshot, monster).ifPresent(drop -> scheduler.runRegion(location, "fracture-drop-" + monster.getUniqueId(), () -> {
                if (location.getWorld() != null) {
                    location.getWorld().dropItemNaturally(location, drop.item());
                }
            }));

            if (killer != null) {
                expeditionBoardManager.recordFractureKill(killer, snapshot, effort).ifPresent(runCompletion -> {
                    runCompletion.participants().forEach(memberId -> {
                        Player member = plugin.getServer().getPlayer(memberId);
                        if (member == null || !member.isOnline()) {
                            return;
                        }

                        vaultEconomyBridge.rewardExpedition(member, runCompletion.completion())
                            .thenAccept(economyReward -> scheduler.runEntity(member, "expedition-complete-" + member.getUniqueId(), () -> {
                                playerProfileService.grantExperience(
                                    member,
                                    runCompletion.completion().experienceReward(),
                                    "Mythic expedition clear (" + runCompletion.completion().scoreTier().name() + ")"
                                );
                                String rewardSuffix = economyReward.isPresent()
                                    ? " | +" + vaultEconomyBridge.formatAmount(economyReward.getAsDouble())
                                    : "";
                                member.sendMessage(Component.text(
                                    "Expedition complete! Rank " + runCompletion.completion().scoreTier().name()
                                        + " | Score " + String.format(Locale.US, "%.1f", runCompletion.completion().finalScore())
                                        + " | Clear " + formatDuration(runCompletion.completion().clearSeconds())
                                        + " | +" + runCompletion.completion().experienceReward() + " RPG XP"
                                        + rewardSuffix
                                ));
                            }));
                        for (ExpeditionCompletionListener completionListener : completionListeners) {
                            completionListener.onExpeditionComplete(memberId, runCompletion.completion());
                        }
                    });
                });
            }
        });

        if (killer != null && fracture.isPresent()) {
            killer.sendMessage(Component.text(
                "Fracture pressure reduced in " + NameFormat.friendly(fracture.get().state().name()) + " zone."
            ));
        }
    }

    private void applyFracture(Monster monster, FractureRegionSnapshot fracture) {
        PersistentDataContainer data = monster.getPersistentDataContainer();
        data.set(fracturedKey, PersistentDataType.BYTE, (byte) 1);
        data.set(stateKey, PersistentDataType.STRING, fracture.state().name());
        data.set(scoreKey, PersistentDataType.DOUBLE, fracture.threatScore());

        double healthMultiplier = 1.15D + (fracture.state().severity() * 0.22D);
        double attackMultiplier = 1.05D + (fracture.state().severity() * 0.16D);
        double speedMultiplier = 1.0D + (fracture.state().severity() * 0.05D);
        double armorBonus = fracture.state().severity();
        healthMultiplier *= fracture.profile().mobHealthMultiplier();
        attackMultiplier *= fracture.profile().mobDamageMultiplier();
        armorBonus += fracture.profile().mobArmorBonus();

        Set<ThreatModifier> modifiers = fracture.modifiers();
        if (modifiers.contains(ThreatModifier.BLOOD_MOON)) {
            healthMultiplier += 0.15D;
            attackMultiplier += 0.18D;
        }
        if (modifiers.contains(ThreatModifier.HOLLOW_FOG)) {
            speedMultiplier += 0.10D;
        }
        if (modifiers.contains(ThreatModifier.ENTROPIC_GROWTH)) {
            healthMultiplier += 0.25D;
        }
        if (modifiers.contains(ThreatModifier.IRON_STORM)) {
            armorBonus += 3.0D;
        }
        if (modifiers.contains(ThreatModifier.NECROTIC_PULSE)) {
            attackMultiplier += 0.20D;
        }
        if (modifiers.contains(ThreatModifier.STORM_LATTICE)) {
            speedMultiplier += 0.12D;
        }
        if (modifiers.contains(ThreatModifier.GLASS_TIDE)) {
            attackMultiplier += 0.10D;
            armorBonus -= 1.0D;
        }

        scaleAttribute(monster.getAttribute(Attribute.MAX_HEALTH), healthMultiplier);
        AttributeInstance maxHealth = monster.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            monster.setHealth(Math.max(1.0D, maxHealth.getBaseValue()));
        }
        scaleAttribute(monster.getAttribute(Attribute.ATTACK_DAMAGE), attackMultiplier);
        scaleAttribute(monster.getAttribute(Attribute.MOVEMENT_SPEED), speedMultiplier);
        addFlat(monster.getAttribute(Attribute.ARMOR), armorBonus);
        addFlat(monster.getAttribute(Attribute.FOLLOW_RANGE), fracture.state().severity() * 3.0D);

        if (fracture.state() == FractureState.BOSS_BREACH) {
            addFlat(monster.getAttribute(Attribute.KNOCKBACK_RESISTANCE), 0.35D);
        }

        refreshVisuals(monster, fracture, true);
        fracture.profile().mobEffects().forEach(effectId -> effectService.applyEffectToEntity(monster, effectId, 1, 0, 1.0D, true)
            .exceptionally(error -> {
                logger.fine("Unable to apply fracture mob effect " + effectId + ": " + error.getMessage());
                return null;
            }));
        logger.fine("Applied fracture affixes to " + monster.getType() + " at " + monster.getLocation());
    }

    private void pulseActiveFractures() {
        List<FractureRegionSnapshot> fractures = worldThreatManager.activeFractures();
        for (FractureRegionSnapshot snapshot : fractures) {
            World world = Bukkit.getWorld(snapshot.key().worldId());
            if (world == null) {
                continue;
            }
            scheduler.runRegion(world, snapshot.key().chunkX(), snapshot.key().chunkZ(), "fracture-visual-pulse-region", () -> {
                if (!world.isChunkLoaded(snapshot.key().chunkX(), snapshot.key().chunkZ())) {
                    return;
                }
                Chunk chunk = world.getChunkAt(snapshot.key().chunkX(), snapshot.key().chunkZ());
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Monster monster
                        && monster.getPersistentDataContainer().has(fracturedKey, PersistentDataType.BYTE)) {
                        refreshVisuals(monster, snapshot, true);
                    }
                }
            });
        }
    }

    private void refreshVisuals(Monster monster, FractureRegionSnapshot fracture, boolean emitParticles) {
        PersistentDataContainer data = monster.getPersistentDataContainer();
        data.set(stateKey, PersistentDataType.STRING, fracture.state().name());
        data.set(scoreKey, PersistentDataType.DOUBLE, fracture.threatScore());
        monster.setGlowing(true);
        monster.customName(Component.text(nameFor(monster, fracture)));
        monster.setCustomNameVisible(true);
        assignGlowTeam(monster.getUniqueId(), fracture.state());
        if (emitParticles) {
            emitParticles(monster, fracture.state());
        }
    }

    private void emitParticles(Monster monster, FractureState state) {
        Location location = monster.getLocation().add(0.0D, Math.max(0.6D, monster.getHeight() * 0.55D), 0.0D);
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        switch (state) {
            case UNSTABLE -> {
                world.spawnParticle(Particle.ELECTRIC_SPARK, location, 6, 0.30D, 0.45D, 0.30D, 0.02D);
                world.spawnParticle(Particle.ENCHANT, location, 3, 0.20D, 0.35D, 0.20D, 0.01D);
            }
            case CORRUPTED -> {
                world.spawnParticle(Particle.ELECTRIC_SPARK, location, 8, 0.35D, 0.50D, 0.35D, 0.02D);
                world.spawnParticle(Particle.WITCH, location, 4, 0.25D, 0.45D, 0.25D, 0.02D);
            }
            case OVERRUN -> {
                world.spawnParticle(Particle.ELECTRIC_SPARK, location, 10, 0.40D, 0.55D, 0.40D, 0.03D);
                world.spawnParticle(Particle.SMOKE, location, 6, 0.30D, 0.45D, 0.30D, 0.01D);
            }
            case BOSS_BREACH -> {
                world.spawnParticle(Particle.ELECTRIC_SPARK, location, 12, 0.45D, 0.60D, 0.45D, 0.03D);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, location, 7, 0.35D, 0.50D, 0.35D, 0.01D);
            }
            case PURIFIED -> {
            }
        }
    }

    private void assignGlowTeam(UUID entityId, FractureState state) {
        scheduler.runGlobal("fracture-glow-assign-" + entityId, () -> {
            Scoreboard scoreboard = mainScoreboard();
            if (scoreboard == null) {
                return;
            }
            ensureGlowTeams(scoreboard);
            String entry = entityId.toString();
            for (FractureState fractureState : FractureState.values()) {
                Team team = scoreboard.getTeam(teamName(fractureState));
                if (team != null) {
                    team.removeEntry(entry);
                }
            }
            Team team = scoreboard.getTeam(teamName(state));
            if (team != null) {
                team.addEntry(entry);
            }
        });
    }

    private void clearGlowTeam(UUID entityId) {
        scheduler.runGlobal("fracture-glow-clear-" + entityId, () -> {
            Scoreboard scoreboard = mainScoreboard();
            if (scoreboard == null) {
                return;
            }
            String entry = entityId.toString();
            for (FractureState state : FractureState.values()) {
                Team team = scoreboard.getTeam(teamName(state));
                if (team != null) {
                    team.removeEntry(entry);
                }
            }
        });
    }

    private void ensureGlowTeams() {
        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            return;
        }
        ensureGlowTeams(scoreboard);
    }

    private void ensureGlowTeams(Scoreboard scoreboard) {
        ensureTeam(scoreboard, FractureState.UNSTABLE, NamedTextColor.GOLD);
        ensureTeam(scoreboard, FractureState.CORRUPTED, NamedTextColor.DARK_PURPLE);
        ensureTeam(scoreboard, FractureState.OVERRUN, NamedTextColor.RED);
        ensureTeam(scoreboard, FractureState.BOSS_BREACH, NamedTextColor.DARK_RED);
    }

    private void ensureTeam(Scoreboard scoreboard, FractureState state, NamedTextColor color) {
        Team team = scoreboard.getTeam(teamName(state));
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName(state));
        }
        team.color(color);
    }

    private void clearGlowTeams() {
        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            return;
        }
        for (FractureState state : FractureState.values()) {
            Team team = scoreboard.getTeam(teamName(state));
            if (team != null) {
                team.unregister();
            }
        }
    }

    private Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private String teamName(FractureState state) {
        return "vortex_fracture_" + state.name().toLowerCase(Locale.ROOT);
    }

    private void scaleAttribute(AttributeInstance attribute, double multiplier) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(attribute.getBaseValue() * multiplier);
    }

    private void addFlat(AttributeInstance attribute, double addition) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(Math.max(0.0D, attribute.getBaseValue() + addition));
    }

    private String nameFor(Monster monster, FractureRegionSnapshot fracture) {
        String modifier = fracture.modifiers().stream().findFirst().map(threatModifier -> NameFormat.friendly(threatModifier.name())).orElse("Fractured");
        return modifier + " " + NameFormat.friendly(fracture.state().name()) + " " + NameFormat.friendly(monster.getType().name());
    }

    private String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public interface ExpeditionCompletionListener {

        void onExpeditionComplete(UUID playerId, ExpeditionCompletion completion);
    }
}
