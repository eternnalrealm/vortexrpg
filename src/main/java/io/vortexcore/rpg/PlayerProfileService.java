package io.vortexcore.rpg;

import io.vortexcore.math.ExperienceCurve;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.nexus.message.PlayerLevelUpMessage;
import io.vortexcore.persistence.PlayerDataRepository;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.persistence.model.PlayerSnapshot;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.support.NameFormat;
import io.vortexcore.ui.MessageService;
import io.vortexcore.world.WorldThreatManager;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class PlayerProfileService implements Listener, AutoCloseable {

    private static final String BREACH_TOKEN_KEY = "breach_tokens";
    private static final String ACTIVE_TITLE_KEY = "cosmetic.active_title";
    private static final String UNLOCKED_TITLES_KEY = "cosmetic.unlocked_titles";
    private static final String ACTIVE_TRAIL_KEY = "cosmetic.active_trail";
    private static final String UNLOCKED_TRAILS_KEY = "cosmetic.unlocked_trails";
    private static final String ACTIVE_AURA_KEY = "cosmetic.active_aura";
    private static final String UNLOCKED_AURAS_KEY = "cosmetic.unlocked_auras";
    private static final String ACTIVE_BANNER_KEY = "cosmetic.active_banner";
    private static final String UNLOCKED_BANNERS_KEY = "cosmetic.unlocked_banners";
    private static final long XP_BATCH_MILLIS = 2500L;

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final PlayerDataRepository repository;
    private final ClassSkillService classSkillService;
    private final NexusBus nexusBus;
    private final WorldThreatManager worldThreatManager;
    private final MessageService messageService;
    private final ExperienceCurve experienceCurve;
    private final Map<UUID, UnifiedPlayer> profiles;
    private final Map<UUID, CompletableFuture<PlayerSnapshot>> pendingSaves;
    private final Set<UUID> loadingPlayers;
    private final ConcurrentHashMap<UUID, XpBatch> xpBatches;

    public PlayerProfileService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        PlayerDataRepository repository,
        ClassSkillService classSkillService,
        NexusBus nexusBus,
        WorldThreatManager worldThreatManager,
        MessageService messageService,
        ExperienceCurve experienceCurve
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.repository = repository;
        this.classSkillService = classSkillService;
        this.nexusBus = nexusBus;
        this.worldThreatManager = worldThreatManager;
        this.messageService = messageService;
        this.experienceCurve = experienceCurve;
        this.profiles = new ConcurrentHashMap<>();
        this.pendingSaves = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.xpBatches = new ConcurrentHashMap<>();
    }

    public UnifiedPlayer profile(Player player) {
        UnifiedPlayer profile = profiles.computeIfAbsent(player.getUniqueId(), ignored -> new UnifiedPlayer(player.getUniqueId(), player.getName()));
        profile.updateLastKnownName(player.getName());
        return profile;
    }

    public Optional<UnifiedPlayer> profile(UUID uniqueId) {
        return Optional.ofNullable(profiles.get(uniqueId));
    }

    public ExperienceCurve experienceCurve() {
        return experienceCurve;
    }

    public long experienceToNextLevel(UnifiedPlayer profile) {
        long nextThreshold = experienceCurve.totalExperienceToReach(profile.level() + 1);
        return Math.max(0L, nextThreshold - profile.experience());
    }

    public long breachTokenBalance(Player player) {
        return readLongMetadata(profile(player), BREACH_TOKEN_KEY);
    }

    public long breachTokenBalance(UnifiedPlayer profile) {
        return readLongMetadata(profile, BREACH_TOKEN_KEY);
    }

    public boolean spendBreachTokens(UnifiedPlayer profile, long amount) {
        if (amount <= 0L) {
            return true;
        }

        long balance = breachTokenBalance(profile);
        if (balance < amount) {
            return false;
        }

        profile.putMetadata(BREACH_TOKEN_KEY, Long.toString(balance - amount));
        return true;
    }

    public void grantBreachTokens(Player player, long amount, String reason) {
        if (amount <= 0L) {
            return;
        }

        UnifiedPlayer profile = profile(player);
        long nextBalance = readLongMetadata(profile, BREACH_TOKEN_KEY) + amount;
        profile.putMetadata(BREACH_TOKEN_KEY, Long.toString(nextBalance));

        scheduler.runEntity(player, "breach-tokens-" + player.getUniqueId(), () -> messageService.send(player,
            "<aqua>+" + amount + " breach tokens</aqua> from " + reason + ". Balance: " + nextBalance + "."
        ));
    }

    public void refreshProfile(Player player) {
        UnifiedPlayer profile = profile(player);
        scheduler.runEntity(player, "profile-refresh-" + player.getUniqueId(), () -> classSkillService.applyLiveAttributes(player, profile));
    }

    public Optional<String> activeTitle(UnifiedPlayer profile) {
        String active = profile.metadata().get(ACTIVE_TITLE_KEY);
        return active == null || active.isBlank() ? Optional.empty() : Optional.of(active.toLowerCase());
    }

    public Set<String> unlockedTitles(UnifiedPlayer profile) {
        return parseCsv(profile.metadata().get(UNLOCKED_TITLES_KEY));
    }

    public boolean hasUnlockedTitle(UnifiedPlayer profile, String titleId) {
        return unlockedTitles(profile).contains(titleId.toLowerCase());
    }

    public void unlockTitle(UnifiedPlayer profile, String titleId, boolean equip) {
        Set<String> unlocked = new java.util.LinkedHashSet<>(unlockedTitles(profile));
        unlocked.add(titleId.toLowerCase());
        profile.putMetadata(UNLOCKED_TITLES_KEY, String.join("|", unlocked));
        if (equip) {
            profile.putMetadata(ACTIVE_TITLE_KEY, titleId.toLowerCase());
        }
    }

    public void equipTitle(UnifiedPlayer profile, String titleId) {
        if (hasUnlockedTitle(profile, titleId)) {
            profile.putMetadata(ACTIVE_TITLE_KEY, titleId.toLowerCase());
        }
    }

    public Optional<String> activeTrail(UnifiedPlayer profile) {
        return readActiveCosmetic(profile, ACTIVE_TRAIL_KEY);
    }

    public Set<String> unlockedTrails(UnifiedPlayer profile) {
        return parseCsv(profile.metadata().get(UNLOCKED_TRAILS_KEY));
    }

    public boolean hasUnlockedTrail(UnifiedPlayer profile, String trailId) {
        return unlockedTrails(profile).contains(trailId.toLowerCase());
    }

    public void unlockTrail(UnifiedPlayer profile, String trailId, boolean equip) {
        unlockCosmetic(profile, UNLOCKED_TRAILS_KEY, ACTIVE_TRAIL_KEY, trailId, equip);
    }

    public void equipTrail(UnifiedPlayer profile, String trailId) {
        if (hasUnlockedTrail(profile, trailId)) {
            profile.putMetadata(ACTIVE_TRAIL_KEY, trailId.toLowerCase());
        }
    }

    public Optional<String> activeAura(UnifiedPlayer profile) {
        return readActiveCosmetic(profile, ACTIVE_AURA_KEY);
    }

    public Set<String> unlockedAuras(UnifiedPlayer profile) {
        return parseCsv(profile.metadata().get(UNLOCKED_AURAS_KEY));
    }

    public boolean hasUnlockedAura(UnifiedPlayer profile, String auraId) {
        return unlockedAuras(profile).contains(auraId.toLowerCase());
    }

    public void unlockAura(UnifiedPlayer profile, String auraId, boolean equip) {
        unlockCosmetic(profile, UNLOCKED_AURAS_KEY, ACTIVE_AURA_KEY, auraId, equip);
    }

    public void equipAura(UnifiedPlayer profile, String auraId) {
        if (hasUnlockedAura(profile, auraId)) {
            profile.putMetadata(ACTIVE_AURA_KEY, auraId.toLowerCase());
        }
    }

    public Optional<String> activeBanner(UnifiedPlayer profile) {
        return readActiveCosmetic(profile, ACTIVE_BANNER_KEY);
    }

    public Set<String> unlockedBanners(UnifiedPlayer profile) {
        return parseCsv(profile.metadata().get(UNLOCKED_BANNERS_KEY));
    }

    public boolean hasUnlockedBanner(UnifiedPlayer profile, String bannerId) {
        return unlockedBanners(profile).contains(bannerId.toLowerCase());
    }

    public void unlockBanner(UnifiedPlayer profile, String bannerId, boolean equip) {
        unlockCosmetic(profile, UNLOCKED_BANNERS_KEY, ACTIVE_BANNER_KEY, bannerId, equip);
    }

    public void equipBanner(UnifiedPlayer profile, String bannerId) {
        if (hasUnlockedBanner(profile, bannerId)) {
            profile.putMetadata(ACTIVE_BANNER_KEY, bannerId.toLowerCase());
        }
    }

    public void grantExperience(Player player, long amount, String reason) {
        if (amount <= 0L) {
            return;
        }
        if (loadingPlayers.contains(player.getUniqueId())) {
            return;
        }

        UnifiedPlayer profile = profile(player);
        UnifiedPlayer.ProgressionResult result = profile.grantExperience(amount, experienceCurve);
        if (result.levelsGained() > 0) {
            classSkillService.grantSkillPoints(player, profile, result.levelsGained());
            nexusBus.publish(new PlayerLevelUpMessage(player.getUniqueId(), result.previousLevel(), result.newLevel()));
        }

        UUID playerId = player.getUniqueId();
        XpBatch batch = xpBatches.compute(playerId, (id, existing) -> {
            if (existing == null) {
                return new XpBatch(amount, result.levelsGained(), result.newLevel(), System.currentTimeMillis());
            }
            return new XpBatch(
                existing.totalXp + amount,
                existing.totalLevelsGained + result.levelsGained(),
                result.newLevel(),
                existing.firstGainAt
            );
        });

        long elapsed = System.currentTimeMillis() - batch.firstGainAt;
        if (elapsed >= XP_BATCH_MILLIS || result.levelsGained() > 0) {
            XpBatch flushed = xpBatches.remove(playerId);
            if (flushed != null) {
                flushXpBatch(player, profile, flushed);
            }
        } else if (batch.totalXp == amount) {
            scheduler.runEntity(player, "xp-batch-flush-" + playerId, XP_BATCH_MILLIS / 50L, () -> {
                XpBatch delayed = xpBatches.remove(playerId);
                if (delayed != null) {
                    flushXpBatch(player, profile, delayed);
                }
            });
        }
    }

    public ExperienceAdjustmentResult addExperience(Player player, long amount, String reason) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        UnifiedPlayer profile = profile(player);
        return setExperience(player, profile.experience() + amount, reason);
    }

    public ExperienceAdjustmentResult removeExperience(Player player, long amount, String reason) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        UnifiedPlayer profile = profile(player);
        return setExperience(player, Math.max(0L, profile.experience() - amount), reason);
    }

    public ExperienceAdjustmentResult setExperience(Player player, long totalExperience, String reason) {
        if (loadingPlayers.contains(player.getUniqueId())) {
            throw new IllegalStateException("That player profile is still loading.");
        }

        UnifiedPlayer profile = profile(player);
        long previousExperience = profile.experience();
        String safeReason = reason == null ? "" : reason.trim();
        xpBatches.remove(player.getUniqueId());

        UnifiedPlayer.ProgressionResult result = profile.setExperience(totalExperience, experienceCurve);
        int levelDelta = result.newLevel() - result.previousLevel();
        if (levelDelta != 0) {
            classSkillService.adjustSkillPoints(player, profile, levelDelta);
            if (levelDelta > 0) {
                nexusBus.publish(new PlayerLevelUpMessage(player.getUniqueId(), result.previousLevel(), result.newLevel()));
            }
        } else {
            refreshProfile(player);
        }

        scheduler.runEntity(player, "xp-admin-update-" + player.getUniqueId(), () -> {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<yellow>XP updated</yellow> <gray>(" + previousExperience + " -> " + result.totalExperience() + ")</gray>"));
            if (!safeReason.isBlank()) {
                messageService.send(player,
                    "<yellow>Your RPG XP was updated by " + safeReason + ".</yellow> <gray>Now at "
                        + result.totalExperience() + " XP and level " + result.newLevel() + ".</gray>"
                );
            }
        });

        return new ExperienceAdjustmentResult(previousExperience, result.totalExperience(), result.previousLevel(), result.newLevel());
    }

    private void flushXpBatch(Player player, UnifiedPlayer profile, XpBatch batch) {
        scheduler.runEntity(player, "xp-batch-display-" + player.getUniqueId(), () -> {
            classSkillService.applyLiveAttributes(player, profile);
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<green>+" + batch.totalXp + " XP</green>"));

            if (batch.totalLevelsGained > 0) {
                messageService.send(player,
                    "<gold><bold>Level up!</bold> You are now level " + batch.currentLevel + ".</gold>"
                );
            }
            if (!classSkillService.hasClass(profile)) {
                messageService.send(player, "Choose a class with <yellow>/class</yellow> to activate your RPG profile.");
            }
        });
    }

    @Override
    public void close() {
        flushAndClose(Duration.ofSeconds(5L));
    }

    public SaveDrainResult flushAndClose(Duration timeout) {
        if (repository == null) {
            int loadedProfiles = profiles.size();
            profiles.clear();
            return new SaveDrainResult(loadedProfiles, 0, 0, false);
        }

        LinkedHashSet<CompletableFuture<?>> trackedSaves = new LinkedHashSet<>();
        profiles.forEach((uniqueId, profile) -> trackedSaves.add(queueSave(uniqueId, profile.lastKnownName(), profile)));
        trackedSaves.addAll(pendingSaves.values());

        boolean timedOut = waitForDrain(trackedSaves, timeout);
        int pendingAfterDrain = pendingSaves.size();
        int completedSaves = Math.max(0, trackedSaves.size() - pendingAfterDrain);
        profiles.clear();
        return new SaveDrainResult(trackedSaves.size(), completedSaves, pendingAfterDrain, timedOut);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UnifiedPlayer profile = profile(player);

        if (repository == null) {
            scheduler.runEntity(player, "profile-join-local-" + player.getUniqueId(), () -> {
                classSkillService.applyLiveAttributes(player, profile);
                if (!classSkillService.hasClass(profile)) {
                    messageService.send(player, "Welcome to VortexRPG. Use <yellow>/class</yellow> to choose your path.");
                }
            });
            return;
        }

        loadingPlayers.add(player.getUniqueId());
        repository.loadPlayer(player.getUniqueId()).whenComplete((snapshot, error) -> {
            if (error != null) {
                logger.warning("Unable to load player profile for " + player.getName() + ": " + error.getMessage());
                loadingPlayers.remove(player.getUniqueId());
                return;
            }

            scheduler.runEntity(player, "profile-join-load-" + player.getUniqueId(), () -> {
                if (!player.isOnline()) {
                    loadingPlayers.remove(player.getUniqueId());
                    return;
                }
                profile.applySnapshot(snapshot == null ? PlayerSnapshot.create(player.getUniqueId()) : snapshot);
                loadingPlayers.remove(player.getUniqueId());
                classSkillService.applyLiveAttributes(player, profile);
                if (!classSkillService.hasClass(profile)) {
                    messageService.send(player, "Welcome to VortexRPG. Use <yellow>/class</yellow> to choose your path.");
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        loadingPlayers.remove(player.getUniqueId());
        UnifiedPlayer profile = profiles.remove(player.getUniqueId());
        if (profile != null) {
            queueSave(player.getUniqueId(), player.getName(), profile);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !(event.getEntity() instanceof Monster monster)) {
            return;
        }

        long experience = experienceFromKill(monster);
        grantExperience(killer, experience, "defeating " + NameFormat.friendly(monster.getType().name()));
    }

    private CompletableFuture<PlayerSnapshot> queueSave(UUID playerId, String playerName, UnifiedPlayer profile) {
        if (repository == null) {
            return CompletableFuture.completedFuture(profile.toSnapshot());
        }

        AtomicReference<CompletableFuture<PlayerSnapshot>> queuedRef = new AtomicReference<>();
        pendingSaves.compute(playerId, (ignored, previous) -> {
            CompletableFuture<Void> gate = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((saved, error) -> null);
            CompletableFuture<PlayerSnapshot> next = gate.thenCompose(ignoredGate -> repository.saveAtomically(profile.toSnapshot()));
            queuedRef.set(next);
            return next;
        });

        CompletableFuture<PlayerSnapshot> queuedSave = queuedRef.get();
        queuedSave.whenComplete((savedSnapshot, error) -> {
            if (error != null) {
                logger.warning("Unable to save player profile for " + playerName + ": " + error.getMessage());
                pendingSaves.remove(playerId, queuedSave);
                return;
            }
            profile.applySnapshot(savedSnapshot);
            pendingSaves.remove(playerId, queuedSave);
        });
        return queuedSave;
    }

    private boolean waitForDrain(LinkedHashSet<CompletableFuture<?>> trackedSaves, Duration timeout) {
        if (trackedSaves.isEmpty()) {
            return false;
        }

        CompletableFuture<?>[] drainedTargets = trackedSaves.stream()
            .map(save -> save.handle((saved, error) -> null))
            .toArray(CompletableFuture[]::new);
        CompletableFuture<Void> drainFuture = CompletableFuture.allOf(drainedTargets);
        try {
            drainFuture.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException exception) {
            logger.warning("Timed out while draining player profile saves after " + timeout.toSeconds() + " seconds. Pending saves: " + pendingSaves.size());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while draining player profile saves.");
            return true;
        } catch (ExecutionException exception) {
            logger.warning("Profile save drain completed with errors: " + exception.getMessage());
            return false;
        }
    }

    private long experienceFromKill(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        double effectiveHealth = maxHealth == null ? Math.max(1.0D, entity.getHealth()) : maxHealth.getValue();
        double fractureMultiplier = worldThreatManager.fractureAt(entity.getLocation())
            .map(snapshot -> 1.0D + (snapshot.state().severity() * 0.35D))
            .orElse(1.0D);
        return Math.max(10L, Math.round((10.0D + effectiveHealth * 1.75D) * fractureMultiplier));
    }

    private Optional<String> readActiveCosmetic(UnifiedPlayer profile, String key) {
        String active = profile.metadata().get(key);
        return active == null || active.isBlank() ? Optional.empty() : Optional.of(active.toLowerCase());
    }

    private void unlockCosmetic(UnifiedPlayer profile, String unlockedKey, String activeKey, String cosmeticId, boolean equip) {
        Set<String> unlocked = new LinkedHashSet<>(parseCsv(profile.metadata().get(unlockedKey)));
        unlocked.add(cosmeticId.toLowerCase());
        profile.putMetadata(unlockedKey, String.join("|", unlocked));
        if (equip || profile.metadata().getOrDefault(activeKey, "").isBlank()) {
            profile.putMetadata(activeKey, cosmeticId.toLowerCase());
        }
    }

    private long readLongMetadata(UnifiedPlayer profile, String key) {
        try {
            return Long.parseLong(profile.metadata().getOrDefault(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String token : raw.split("[,|]")) {
            String normalized = token.trim().toLowerCase();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    public record SaveDrainResult(int attemptedSaves, int completedSaves, int pendingSaves, boolean timedOut) {
    }

    private record XpBatch(long totalXp, int totalLevelsGained, int currentLevel, long firstGainAt) {
    }

    public record ExperienceAdjustmentResult(long previousExperience, long newExperience, int previousLevel, int newLevel) {
    }
}
