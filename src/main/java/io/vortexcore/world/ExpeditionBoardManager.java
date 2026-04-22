package io.vortexcore.world;

import io.vortexcore.ui.VortexMenuBuilder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ExpeditionBoardManager {

    private static final int LEADERBOARD_MAX_ENTRIES = 64;
    private static final int LEADERBOARD_RETENTION_WEEKS = 8;

    private final VortexMenuBuilder menuBuilder;
    private final WorldThreatManager worldThreatManager;
    private final FractureLootService fractureLootService;
    private final ExpeditionPartyManager partyManager;
    private final int maxContracts;
    private final ConcurrentMap<UUID, ExpeditionContract> acceptedContracts;
    private final ConcurrentMap<UUID, UUID> runAssignments;
    private final ConcurrentMap<UUID, ActiveExpeditionRun> activeRuns;
    private final ConcurrentMap<String, ConcurrentMap<UUID, ExpeditionLeaderboardEntry>> weeklyLeaderboard;

    private volatile MythicRotation cachedRotation;

    public ExpeditionBoardManager(
        VortexMenuBuilder menuBuilder,
        WorldThreatManager worldThreatManager,
        FractureLootService fractureLootService,
        ExpeditionPartyManager partyManager,
        int maxContracts
    ) {
        this.menuBuilder = menuBuilder;
        this.worldThreatManager = worldThreatManager;
        this.fractureLootService = fractureLootService;
        this.partyManager = partyManager;
        this.maxContracts = maxContracts;
        this.acceptedContracts = new ConcurrentHashMap<>();
        this.runAssignments = new ConcurrentHashMap<>();
        this.activeRuns = new ConcurrentHashMap<>();
        this.weeklyLeaderboard = new ConcurrentHashMap<>();
    }

    public void openBoard(Player player) {
        menuBuilder.open(player, buildBoard(player));
    }

    public Optional<ExpeditionContract> acceptedContract(UUID playerId) {
        UUID runId = runAssignments.get(playerId);
        if (runId == null) {
            return Optional.empty();
        }

        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            runAssignments.remove(playerId, runId);
            acceptedContracts.remove(playerId);
            return Optional.empty();
        }

        synchronized (run) {
            if (!run.participants.contains(playerId)) {
                runAssignments.remove(playerId, runId);
                acceptedContracts.remove(playerId);
                return Optional.empty();
            }
        }

        Optional<FractureRegionSnapshot> liveFracture = worldThreatManager.fracture(run.contract.fracture().key());
        if (liveFracture.isEmpty()) {
            clearRun(runId);
            return Optional.empty();
        }

        return Optional.of(run.contract);
    }

    public Optional<ExpeditionRunSnapshot> activeRun(UUID playerId) {
        Optional<ExpeditionContract> contract = acceptedContract(playerId);
        if (contract.isEmpty()) {
            return Optional.empty();
        }

        UUID runId = runAssignments.get(playerId);
        if (runId == null) {
            return Optional.empty();
        }

        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            return Optional.empty();
        }

        synchronized (run) {
            long elapsedSeconds = Math.max(1L, (System.currentTimeMillis() - run.startedAtEpochMillis) / 1000L);
            return Optional.of(new ExpeditionRunSnapshot(
                run.runId,
                run.partyId,
                run.contract.contractId(),
                run.contract.title(),
                run.contract.objective(),
                run.contract.rotationId(),
                run.participants.size(),
                run.partyRun,
                run.contract.breachFinale(),
                run.breachLaunched,
                run.killCount,
                run.contract.targetKills(),
                elapsedSeconds,
                run.contract.timeLimitSeconds(),
                run.score,
                run.contract.scoreMultiplier()
            ));
        }
    }

    public Optional<ExpeditionRunCompletion> recordFractureKill(Player player, FractureRegionSnapshot fracture, double suppressionEffort) {
        UUID runId = runAssignments.get(player.getUniqueId());
        if (runId == null) {
            return Optional.empty();
        }

        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            runAssignments.remove(player.getUniqueId(), runId);
            acceptedContracts.remove(player.getUniqueId());
            return Optional.empty();
        }

        AtomicReference<ExpeditionRunCompletion> completionRef = new AtomicReference<>();
        synchronized (run) {
            if (!run.participants.contains(player.getUniqueId())) {
                return Optional.empty();
            }
            if (!run.contract.fracture().key().equals(fracture.key())) {
                return Optional.empty();
            }

            long now = System.currentTimeMillis();
            run.killCount++;

            double basePoints = 16.0D
                + (fracture.state().severity() * 6.5D)
                + (Math.max(0.0D, suppressionEffort) * 0.35D)
                + (Math.max(0.0D, fracture.threatScore()) * 0.08D);
            double momentumMultiplier = 1.0D + Math.min(0.45D, run.killCount * 0.012D);
            double partySynergy = 1.0D + Math.max(0.0D, (run.participants.size() - 1) * 0.08D);
            run.score += basePoints * run.contract.scoreMultiplier() * momentumMultiplier * partySynergy;

            // Breach contracts finish in the private boss instance, not on overworld trash clears.
            if (run.contract.breachFinale()) {
                return Optional.empty();
            }

            if (run.killCount < run.contract.targetKills()) {
                return Optional.empty();
            }

            long elapsedSeconds = Math.max(1L, (now - run.startedAtEpochMillis) / 1000L);
            long remainingSeconds = Math.max(0L, run.contract.timeLimitSeconds() - elapsedSeconds);
            double timeBonus = remainingSeconds * (1.5D + (fracture.state().severity() * 0.2D)) * run.contract.scoreMultiplier();
            double finalScore = run.score + timeBonus;
            ExpeditionScoreTier scoreTier = ExpeditionScoreTier.resolve(finalScore, run.contract.tier());

            int participantCount = Math.max(1, run.participants.size());
            int baseReward = Math.max(15, (int) Math.round((finalScore * 0.35D) + (run.contract.tier().ordinal() * 22.0D)));
            int experienceReward = Math.max(10, (int) Math.round(baseReward * (1.0D / Math.sqrt(participantCount))));

            ExpeditionCompletion completion = new ExpeditionCompletion(
                run.contract.contractId(),
                run.contract.title(),
                run.contract.rotationId(),
                scoreTier,
                finalScore,
                elapsedSeconds,
                experienceReward
            );

            LinkedHashSet<UUID> participantSnapshot = new LinkedHashSet<>(run.participants);
            participantSnapshot.forEach(memberId -> {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberId);
                String name = offlinePlayer.getName() == null ? memberId.toString().substring(0, 8) : offlinePlayer.getName();
                registerLeaderboard(memberId, name, completion);
            });
            completionRef.set(new ExpeditionRunCompletion(completion, Set.copyOf(participantSnapshot)));
        }

        ExpeditionRunCompletion completion = completionRef.get();
        if (completion != null) {
            clearRun(runId);
            return Optional.of(completion);
        }
        return Optional.empty();
    }

    public Optional<ExpeditionBreachContext> breachContextFor(UUID playerId) {
        UUID runId = runAssignments.get(playerId);
        if (runId == null) {
            return Optional.empty();
        }

        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            runAssignments.remove(playerId, runId);
            acceptedContracts.remove(playerId);
            return Optional.empty();
        }

        synchronized (run) {
            if (!run.participants.contains(playerId) || !run.contract.breachFinale()) {
                return Optional.empty();
            }
            return Optional.of(new ExpeditionBreachContext(
                run.runId,
                run.partyId,
                run.runId,
                run.contract,
                Set.copyOf(run.participants),
                run.partyRun
            ));
        }
    }

    public boolean setBreachLaunched(UUID runId, boolean launched) {
        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            return false;
        }

        synchronized (run) {
            if (!run.contract.breachFinale()) {
                return false;
            }
            if (launched && run.breachLaunched) {
                return false;
            }
            run.breachLaunched = launched;
            return true;
        }
    }

    public Optional<ExpeditionRunCompletion> completeBreachRun(UUID runId, double bossScoreBonus) {
        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            return Optional.empty();
        }

        AtomicReference<ExpeditionRunCompletion> completionRef = new AtomicReference<>();
        synchronized (run) {
            if (!run.contract.breachFinale()) {
                return Optional.empty();
            }

            long now = System.currentTimeMillis();
            long elapsedSeconds = Math.max(1L, (now - run.startedAtEpochMillis) / 1000L);
            long remainingSeconds = Math.max(0L, run.contract.timeLimitSeconds() - elapsedSeconds);
            double timeBonus = remainingSeconds * 2.4D * run.contract.scoreMultiplier();
            double finalScore = run.score + Math.max(0.0D, bossScoreBonus) + timeBonus;
            ExpeditionScoreTier scoreTier = ExpeditionScoreTier.resolve(finalScore, run.contract.tier());

            int participantCount = Math.max(1, run.participants.size());
            int baseReward = Math.max(50, (int) Math.round((finalScore * 0.42D) + 120.0D));
            int experienceReward = Math.max(30, (int) Math.round(baseReward * (1.0D / Math.sqrt(participantCount))));

            ExpeditionCompletion completion = new ExpeditionCompletion(
                run.contract.contractId(),
                run.contract.title(),
                run.contract.rotationId(),
                scoreTier,
                finalScore,
                elapsedSeconds,
                experienceReward
            );

            LinkedHashSet<UUID> participantSnapshot = new LinkedHashSet<>(run.participants);
            participantSnapshot.forEach(memberId -> {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberId);
                String name = offlinePlayer.getName() == null ? memberId.toString().substring(0, 8) : offlinePlayer.getName();
                registerLeaderboard(memberId, name, completion);
            });
            completionRef.set(new ExpeditionRunCompletion(completion, Set.copyOf(participantSnapshot)));
        }

        ExpeditionRunCompletion completion = completionRef.get();
        if (completion != null) {
            clearRun(runId);
            return Optional.of(completion);
        }
        return Optional.empty();
    }

    public List<ExpeditionLeaderboardEntry> currentLeaderboard(int maxEntries) {
        String rotationId = currentRotation().rotationId;
        ConcurrentMap<UUID, ExpeditionLeaderboardEntry> entries = weeklyLeaderboard.get(rotationId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, maxEntries);
        return entries.values().stream()
            .sorted(Comparator
                .comparingDouble(ExpeditionLeaderboardEntry::bestScore).reversed()
                .thenComparingLong(ExpeditionLeaderboardEntry::clearSeconds)
                .thenComparingLong(ExpeditionLeaderboardEntry::completedAtEpochMillis)
            )
            .limit(limit)
            .toList();
    }

    public String currentRotationId() {
        return currentRotation().rotationId;
    }

    public void abandonContracts(Set<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return;
        }
        playerIds.forEach(this::abandonContract);
    }

    public void abandonContract(UUID playerId) {
        UUID runId = runAssignments.get(playerId);
        if (runId == null) {
            acceptedContracts.remove(playerId);
            return;
        }

        ActiveExpeditionRun run = activeRuns.get(runId);
        if (run == null) {
            runAssignments.remove(playerId, runId);
            acceptedContracts.remove(playerId);
            return;
        }

        boolean removeWholeRun = false;
        synchronized (run) {
            run.participants.remove(playerId);
            runAssignments.remove(playerId, runId);
            acceptedContracts.remove(playerId);
            if (run.participants.isEmpty()) {
                removeWholeRun = true;
            }
        }

        if (removeWholeRun) {
            activeRuns.remove(runId, run);
        }
    }

    public List<ExpeditionContract> contractsFor(Player player) {
        MythicRotation rotation = currentRotation();
        return worldThreatManager.activeFractures().stream()
            .sorted(Comparator
                .comparing((FractureRegionSnapshot snapshot) -> snapshot.state().severity()).reversed()
                .thenComparingDouble(snapshot -> distanceSquared(player, snapshot))
            )
            .limit(maxContracts)
            .map(snapshot -> toContract(snapshot, rotation))
            .toList();
    }

    public ExpeditionPartyManager partyManager() {
        return partyManager;
    }

    public int activeRunCount() {
        return activeRuns.size();
    }

    public int assignedPlayerCount() {
        return runAssignments.size();
    }

    private ActiveExpeditionRun acceptContract(Player initiator, ExpeditionContract contract) {
        UUID initiatorId = initiator.getUniqueId();
        Optional<ExpeditionPartyManager.PartySnapshot> party = partyManager.partyOf(initiatorId);
        Set<UUID> participants = party.map(snapshot -> snapshot.members().stream()
                .filter(this::isOnline)
                .collect(Collectors.toCollection(LinkedHashSet::new)))
            .orElseGet(() -> new LinkedHashSet<>(Set.of(initiatorId)));
        participants.add(initiatorId);
        UUID runId = party.map(ExpeditionPartyManager.PartySnapshot::partyId).orElse(initiatorId);
        UUID partyId = party.map(ExpeditionPartyManager.PartySnapshot::partyId).orElse(null);

        participants.forEach(this::abandonContract);

        ActiveExpeditionRun run = ActiveExpeditionRun.from(runId, partyId, participants, contract);
        activeRuns.put(runId, run);
        participants.forEach(memberId -> {
            runAssignments.put(memberId, runId);
            acceptedContracts.put(memberId, contract);
        });
        return run;
    }

    private void clearRun(UUID runId) {
        ActiveExpeditionRun removed = activeRuns.remove(runId);
        if (removed == null) {
            return;
        }
        synchronized (removed) {
            removed.participants.forEach(memberId -> {
                runAssignments.remove(memberId, runId);
                acceptedContracts.remove(memberId);
            });
            removed.participants.clear();
        }
    }

    private VortexMenuBuilder.MenuDefinition buildBoard(Player player) {
        MythicRotation rotation = currentRotation();
        List<ExpeditionContract> contracts = contractsFor(player);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Expedition Board"), 6);

        builder.button(
            4,
            neutralIcon(
                Material.CLOCK,
                "Weekly Rotation " + rotation.rotationId,
                List.of(
                    "Affixes: " + friendlyModifiers(rotation.affixes),
                    "Score Bonus: x" + String.format(Locale.US, "%.2f", rotation.scoreBonus)
                )
            ),
            context -> CompletableFuture.completedFuture(null)
        );

        int slot = 10;
        for (ExpeditionContract contract : contracts) {
            if (slot >= 44) {
                break;
            }
            builder.button(slot++, iconFor(contract), context -> {
                ActiveExpeditionRun run = acceptContract(context.player(), contract);
                run.participants.forEach(memberId -> {
                    Player member = Bukkit.getPlayer(memberId);
                    if (member != null && member.isOnline()) {
                        String prefix = run.partyRun ? "Party accepted contract: " : "Accepted contract: ";
                        member.sendMessage(Component.text(prefix + contract.title()));
                    }
                });
                return CompletableFuture.completedFuture(null);
            }, true);
        }

        acceptedContract(player.getUniqueId()).ifPresentOrElse(contract -> builder.button(49, acceptedIcon(player.getUniqueId(), contract), context -> {
            activeRun(context.player().getUniqueId()).ifPresent(run -> context.player().sendMessage(Component.text(
                "Active run: " + run.killCount() + "/" + run.targetKills()
                    + " kills | Score " + String.format(Locale.US, "%.1f", run.score())
                    + " | Timer " + formatDuration(run.elapsedSeconds()) + "/" + formatDuration(run.timeLimitSeconds())
                    + " | Party " + run.participantCount()
            )));
            return CompletableFuture.completedFuture(null);
        }), () -> builder.button(49, neutralIcon(Material.MAP, "No Active Contract", List.of("Pick one from the board.")), context -> {
            context.player().sendMessage(Component.text("No expedition contract is currently assigned."));
            return CompletableFuture.completedFuture(null);
        }));

        builder.button(53, neutralIcon(Material.COMPASS, "Refresh Board", List.of("Rebuild this menu from live fractures.")), context -> {
            openBoard(context.player());
            return CompletableFuture.completedFuture(null);
        });

        if (contracts.isEmpty()) {
            builder.button(22, neutralIcon(Material.BARRIER, "No Active Fractures", List.of("The world is stable for now.")), context -> {
                context.player().sendMessage(Component.text("No active fracture contracts are available."));
                return CompletableFuture.completedFuture(null);
            });
        }

        return builder.build();
    }

    private ExpeditionContract toContract(FractureRegionSnapshot fracture, MythicRotation rotation) {
        ExpeditionContractTier tier = switch (fracture.state()) {
            case UNSTABLE -> ExpeditionContractTier.PATROL;
            case CORRUPTED -> ExpeditionContractTier.PURGE;
            case OVERRUN -> ExpeditionContractTier.CATACLYSM;
            case BOSS_BREACH -> ExpeditionContractTier.BREACH;
            case PURIFIED -> ExpeditionContractTier.PATROL;
        };

        String title = switch (tier) {
            case PATROL -> "Scout the unstable frontier";
            case PURGE -> "Purge the corrupted front";
            case CATACLYSM -> "Contain the overrun collapse";
            case BREACH -> "Enter the boss breach";
        };

        int targetKills = switch (tier) {
            case PATROL -> 10;
            case PURGE -> 18;
            case CATACLYSM -> 28;
            case BREACH -> 38;
        };
        targetKills += Math.max(0, fracture.state().severity() - 1) * 4;
        targetKills += Math.max(0, (int) Math.round((Math.max(0.0D, fracture.threatScore()) - 40.0D) / 40.0D));

        long timeLimitSeconds = switch (tier) {
            case PATROL -> 600L;
            case PURGE -> 780L;
            case CATACLYSM -> 960L;
            case BREACH -> 1140L;
        };

        LinkedHashSet<ThreatModifier> activeModifiers = new LinkedHashSet<>(fracture.modifiers());
        activeModifiers.addAll(rotation.affixes);

        double multiplier = switch (tier) {
            case PATROL -> 1.00D;
            case PURGE -> 1.18D;
            case CATACLYSM -> 1.35D;
            case BREACH -> 1.55D;
        };
        multiplier += activeModifiers.size() * 0.05D;
        multiplier += Math.min(200.0D, Math.max(0.0D, fracture.threatScore())) / 500.0D;
        multiplier = Math.min(3.50D, Math.max(0.75D, multiplier * rotation.scoreBonus));

        String objective = switch (tier) {
            case PATROL -> "Defeat " + targetKills + " fractured mobs within " + formatDuration(timeLimitSeconds) + ".";
            case PURGE -> "Eliminate " + targetKills + " corrupted enemies before the timer expires.";
            case CATACLYSM -> "Cut down " + targetKills + " overrun forces to stabilize the zone.";
            case BREACH -> "Launch the private breach and defeat the champion before the timer expires.";
        };

        int recommendedPartySize = switch (tier) {
            case PATROL -> 1;
            case PURGE -> 2;
            case CATACLYSM -> 4;
            case BREACH -> 5;
        };

        String rewardPreview = fractureLootService.previewRewardName(fracture.state()) + " + Mythic XP";
        UUID contractId = UUID.nameUUIDFromBytes((
            fracture.key().worldId()
                + ":" + fracture.key().chunkX()
                + ":" + fracture.key().chunkZ()
                + ":" + fracture.state()
                + ":" + rotation.rotationId
                + ":" + targetKills
        ).getBytes(StandardCharsets.UTF_8));

        return new ExpeditionContract(
            contractId,
            fracture,
            tier,
            title,
            objective,
            recommendedPartySize,
            rewardPreview,
            targetKills,
            timeLimitSeconds,
            multiplier,
            rotation.rotationId,
            tier == ExpeditionContractTier.BREACH,
            Set.copyOf(activeModifiers)
        );
    }

    private ItemStack iconFor(ExpeditionContract contract) {
        Material material = switch (contract.tier()) {
            case PATROL -> Material.COPPER_INGOT;
            case PURGE -> Material.ECHO_SHARD;
            case CATACLYSM -> Material.NETHERITE_SCRAP;
            case BREACH -> Material.NETHER_STAR;
        };
        List<String> loreLines = List.of(
            "Objective: " + contract.objective(),
            "Party Size: " + contract.recommendedPartySize(),
            contract.breachFinale() ? "Finale: Private breach boss" : "Target Kills: " + contract.targetKills(),
            "Time Limit: " + formatDuration(contract.timeLimitSeconds()),
            "Score Multiplier: x" + String.format(Locale.US, "%.2f", contract.scoreMultiplier()),
            "Rotation: " + contract.rotationId(),
            "Affixes: " + friendlyModifiers(contract.activeModifiers()),
            "Reward: " + contract.rewardPreview(),
            "Threat Score: " + String.format(Locale.US, "%.1f", contract.fracture().threatScore())
        );
        return neutralIcon(material, contract.title(), loreLines);
    }

    private ItemStack acceptedIcon(UUID playerId, ExpeditionContract contract) {
        Optional<ExpeditionRunSnapshot> run = activeRun(playerId);
        if (run.isEmpty()) {
            return neutralIcon(
                Material.WRITABLE_BOOK,
                "Accepted: " + contract.title(),
                List.of(contract.objective(), "Reward Preview: " + contract.rewardPreview())
            );
        }
        ExpeditionRunSnapshot snapshot = run.get();
        return neutralIcon(
            Material.WRITABLE_BOOK,
            "Accepted: " + contract.title(),
            List.of(
                snapshot.breachFinale()
                    ? "Finale: " + (snapshot.breachLaunched() ? "Breach active" : "Ready to launch")
                    : "Progress: " + snapshot.killCount() + "/" + snapshot.targetKills() + " kills",
                "Score: " + String.format(Locale.US, "%.1f", snapshot.score()),
                "Timer: " + formatDuration(snapshot.elapsedSeconds()) + "/" + formatDuration(snapshot.timeLimitSeconds()),
                "Participants: " + snapshot.participantCount(),
                "Reward Preview: " + contract.rewardPreview()
            )
        );
    }

    private ItemStack neutralIcon(Material material, String title, List<String> loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private void registerLeaderboard(UUID playerId, String playerName, ExpeditionCompletion completion) {
        ConcurrentMap<UUID, ExpeditionLeaderboardEntry> leaderboard = weeklyLeaderboard.computeIfAbsent(
            completion.rotationId(),
            ignored -> new ConcurrentHashMap<>()
        );

        leaderboard.compute(playerId, (ignored, existing) -> {
            ExpeditionLeaderboardEntry candidate = new ExpeditionLeaderboardEntry(
                playerId,
                playerName,
                completion.rotationId(),
                completion.finalScore(),
                completion.clearSeconds(),
                completion.scoreTier(),
                System.currentTimeMillis()
            );
            if (existing == null) {
                return candidate;
            }
            return candidate.bestScore() > existing.bestScore() ? candidate : existing;
        });

        if (leaderboard.size() > LEADERBOARD_MAX_ENTRIES) {
            List<ExpeditionLeaderboardEntry> sorted = leaderboard.values().stream()
                .sorted(Comparator
                    .comparingDouble(ExpeditionLeaderboardEntry::bestScore).reversed()
                    .thenComparingLong(ExpeditionLeaderboardEntry::clearSeconds)
                    .thenComparingLong(ExpeditionLeaderboardEntry::completedAtEpochMillis)
                )
                .toList();

            for (int index = LEADERBOARD_MAX_ENTRIES; index < sorted.size(); index++) {
                ExpeditionLeaderboardEntry entry = sorted.get(index);
                leaderboard.remove(entry.playerId(), entry);
            }
        }

        if (weeklyLeaderboard.size() > LEADERBOARD_RETENTION_WEEKS) {
            List<String> rotationIds = new ArrayList<>(weeklyLeaderboard.keySet());
            rotationIds.sort(String::compareTo);
            while (rotationIds.size() > LEADERBOARD_RETENTION_WEEKS) {
                String oldest = rotationIds.remove(0);
                weeklyLeaderboard.remove(oldest);
            }
        }
    }

    private double distanceSquared(Player player, FractureRegionSnapshot snapshot) {
        if (player.getWorld() == null || !player.getWorld().getUID().equals(snapshot.key().worldId())) {
            return Double.MAX_VALUE;
        }

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        long dx = playerChunkX - snapshot.key().chunkX();
        long dz = playerChunkZ - snapshot.key().chunkZ();
        return (dx * dx) + (dz * dz);
    }

    private MythicRotation currentRotation() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        WeekFields weekFields = WeekFields.ISO;
        int week = now.get(weekFields.weekOfWeekBasedYear());
        int year = now.get(weekFields.weekBasedYear());
        String rotationId = year + "-W" + String.format(Locale.US, "%02d", week);

        MythicRotation existing = cachedRotation;
        if (existing != null && existing.rotationId.equals(rotationId)) {
            return existing;
        }

        synchronized (this) {
            MythicRotation refreshed = cachedRotation;
            if (refreshed != null && refreshed.rotationId.equals(rotationId)) {
                return refreshed;
            }
            MythicRotation created = createRotation(rotationId, year, week);
            cachedRotation = created;
            return created;
        }
    }

    private MythicRotation createRotation(String rotationId, int year, int week) {
        long seed = (((long) year) << 32) ^ ((long) week * 1_315_423_911L) ^ 0x9E3779B97F4A7C15L;
        Random random = new Random(seed);
        List<ThreatModifier> pool = new ArrayList<>(List.of(ThreatModifier.values()));
        java.util.Collections.shuffle(pool, random);

        int modifierCount = Math.min(pool.size(), 2 + random.nextInt(2));
        LinkedHashSet<ThreatModifier> modifiers = new LinkedHashSet<>();
        for (int index = 0; index < modifierCount; index++) {
            modifiers.add(pool.get(index));
        }

        double scoreBonus = 1.08D + (modifierCount * 0.05D) + (random.nextDouble() * 0.12D);
        return new MythicRotation(rotationId, Set.copyOf(modifiers), scoreBonus);
    }

    private String friendlyModifiers(Set<ThreatModifier> modifiers) {
        if (modifiers.isEmpty()) {
            return "None";
        }
        return modifiers.stream()
            .map(threatModifier -> NameFormat.friendly(threatModifier.name()))
            .collect(Collectors.joining(", "));
    }

    private String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private boolean isOnline(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline();
    }

    private static final class ActiveExpeditionRun {

        private final UUID runId;
        private final UUID partyId;
        @SuppressWarnings("unused")
        private final FractureRegionKey fractureKey;
        private final ExpeditionContract contract;
        private final boolean partyRun;
        private final long startedAtEpochMillis;
        private final LinkedHashSet<UUID> participants;
        private boolean breachLaunched;
        private int killCount;
        private double score;

        private ActiveExpeditionRun(
            UUID runId,
            UUID partyId,
            FractureRegionKey fractureKey,
            ExpeditionContract contract,
            boolean partyRun,
            long startedAtEpochMillis,
            Set<UUID> participants
        ) {
            this.runId = runId;
            this.partyId = partyId;
            this.fractureKey = fractureKey;
            this.contract = contract;
            this.partyRun = partyRun;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.participants = new LinkedHashSet<>(participants);
            this.breachLaunched = false;
            this.killCount = 0;
            this.score = 0.0D;
        }

        private static ActiveExpeditionRun from(UUID runId, UUID partyId, Set<UUID> participants, ExpeditionContract contract) {
            return new ActiveExpeditionRun(
                runId,
                partyId,
                contract.fracture().key(),
                contract,
                participants.size() > 1,
                System.currentTimeMillis(),
                participants
            );
        }
    }

    private record MythicRotation(
        String rotationId,
        Set<ThreatModifier> affixes,
        double scoreBonus
    ) {
    }
}
