package io.vortexcore.adventure;

import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.profession.ProfessionType;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import io.vortexcore.world.ExpeditionCompletion;
import io.vortexcore.world.MythicBreachManager;
import io.vortexcore.world.RiftSpawnController;
import io.vortexcore.world.WorldThreatManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class AdventureBoardService implements
    Listener,
    ProfessionService.ProfessionActivityListener,
    SpellbookService.SpellCastListener,
    RiftSpawnController.ExpeditionCompletionListener,
    MythicBreachManager.BreachCompletionListener {

    private static final String DAILY_ROTATION_KEY = "adventure.daily.rotation";
    private static final String DAILY_PROGRESS_KEY = "adventure.daily.progress";
    private static final String DAILY_CLAIMED_KEY = "adventure.daily.claimed";
    private static final String WEEKLY_ROTATION_KEY = "adventure.weekly.rotation";
    private static final String WEEKLY_PROGRESS_KEY = "adventure.weekly.progress";
    private static final String WEEKLY_CLAIMED_KEY = "adventure.weekly.claimed";
    private static final String PATHFINDER_TITLE_ID = "pathfinder";

    private final VortexMenuBuilder menuBuilder;
    private final PlayerProfileService playerProfileService;
    private final WorldThreatManager worldThreatManager;
    private final SeasonTrackService seasonTrackService;
    private final ClassSkillService classSkillService;
    private final MessageService messageService;
    private final ProtectionHookService protectionHookService;

    public AdventureBoardService(
        VortexMenuBuilder menuBuilder,
        PlayerProfileService playerProfileService,
        WorldThreatManager worldThreatManager,
        SeasonTrackService seasonTrackService,
        ClassSkillService classSkillService,
        MessageService messageService,
        ProtectionHookService protectionHookService
    ) {
        this.menuBuilder = menuBuilder;
        this.playerProfileService = playerProfileService;
        this.worldThreatManager = worldThreatManager;
        this.seasonTrackService = seasonTrackService;
        this.classSkillService = classSkillService;
        this.messageService = messageService;
        this.protectionHookService = protectionHookService;
    }

    public void openBoard(Player player) {
        menuBuilder.open(player, buildMenu(player));
    }

    public void openSeasonTrack(Player player) {
        seasonTrackService.openTrack(player);
    }

    public List<String> statusLines(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        BoardState daily = boardState(profile, BoardScope.DAILY);
        BoardState weekly = boardState(profile, BoardScope.WEEKLY);

        List<String> lines = new ArrayList<>();
        lines.add("Daily Rotation: " + daily.rotationId());
        daily.objectives().forEach(objective -> lines.add(statusLine(daily, objective)));
        lines.add("Weekly Rotation: " + weekly.rotationId());
        weekly.objectives().forEach(objective -> lines.add(statusLine(weekly, objective)));
        lines.addAll(seasonTrackService.statusLines(player));
        return List.copyOf(lines);
    }

    public String dailyRotationId(Player player) {
        return boardState(playerProfileService.profile(player), BoardScope.DAILY).rotationId();
    }

    public String weeklyRotationId(Player player) {
        return boardState(playerProfileService.profile(player), BoardScope.WEEKLY).rotationId();
    }

    @Override
    public void onGather(Player player, ProfessionType profession, long amount) {
        switch (profession) {
            case SMITHING, ALCHEMY, COOKING -> {
            }
            default -> {
                return;
            }
        }
    }

    @Override
    public void onCraft(Player player, ProfessionType profession, String itemId, int crafts) {
        recordProgress(player, ObjectiveType.PROFESSION_CRAFTS, profession.id(), crafts);
    }

    @Override
    public void onSpellCast(Player player, String spellId) {
        recordProgress(player, ObjectiveType.SPELL_CASTS, null, 1);
    }

    @Override
    public void onExpeditionComplete(UUID playerId, ExpeditionCompletion completion) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            recordProgress(player, ObjectiveType.EXPEDITION_CLEARS, null, 1);
        }
    }

    @Override
    public void onBreachClear(UUID playerId, ExpeditionCompletion completion, long tokenReward) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            recordProgress(player, ObjectiveType.BREACH_CLEARS, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        recordProgress(killer, ObjectiveType.MONSTER_KILLS, null, 1);
        if (worldThreatManager.fractureAt(monster.getLocation()).isPresent()) {
            recordProgress(killer, ObjectiveType.FRACTURED_KILLS, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!protectionHookService.check(event.getPlayer(), event.getBlock().getLocation(), ProtectionAction.GATHER).allowed()) {
            return;
        }
        Material type = event.getBlock().getType();
        if (isMiningBlock(type)) {
            recordProgress(event.getPlayer(), ObjectiveType.MINING_NODES, null, 1);
        } else if (isForagingBlock(type)) {
            recordProgress(event.getPlayer(), ObjectiveType.FORAGING_HARVESTS, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() != null) {
            recordProgress(event.getPlayer(), ObjectiveType.FISHING_CATCHES, null, 1);
        }
    }

    private void recordProgress(Player player, ObjectiveType type, String qualifier, int amount) {
        if (amount <= 0) {
            return;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        updateScopeProgress(player, profile, boardState(profile, BoardScope.DAILY), type, qualifier, amount);
        updateScopeProgress(player, profile, boardState(profile, BoardScope.WEEKLY), type, qualifier, amount);
    }

    private void updateScopeProgress(Player player, UnifiedPlayer profile, BoardState state, ObjectiveType type, String qualifier, int amount) {
        Map<String, Integer> progress = new LinkedHashMap<>(state.progress());
        boolean changed = false;

        for (BoardObjective objective : state.objectives()) {
            if (!objective.type().equals(type) || !qualifierMatches(objective.qualifier(), qualifier)) {
                continue;
            }
            int current = progress.getOrDefault(objective.id(), 0);
            int updated = Math.min(objective.target(), current + amount);
            if (updated == current) {
                continue;
            }
            progress.put(objective.id(), updated);
            changed = true;
            if (current < objective.target() && updated >= objective.target()) {
                messageService.send(player, scopeLabel(state.scope()) + " objective complete: " + objective.title() + ".");
            }
        }

        if (changed) {
            writeProgress(profile, state.scope(), progress);
        }
    }

    private VortexMenuBuilder.MenuDefinition buildMenu(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        BoardState daily = boardState(profile, BoardScope.DAILY);
        BoardState weekly = boardState(profile, BoardScope.WEEKLY);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Adventure Board"), 6);

        builder.button(4, icon(Material.WRITABLE_BOOK, "Adventure Board", List.of(
            "Daily Rotation: " + daily.rotationId(),
            "Weekly Rotation: " + weekly.rotationId(),
            "Claim completed objectives for XP and breach tokens.",
            "Weekly sweep unlocks the Pathfinder title once."
        )), context -> CompletableFuture.completedFuture(null));

        int[] dailySlots = {10, 11, 12};
        int[] weeklySlots = {28, 29, 30};

        for (int index = 0; index < Math.min(dailySlots.length, daily.objectives().size()); index++) {
            BoardObjective objective = daily.objectives().get(index);
            builder.button(dailySlots[index], objectiveIcon(daily, objective), context -> {
                handleObjectiveClick(context.player(), daily.scope(), objective.id());
                context.open(buildMenu(context.player()));
                return CompletableFuture.completedFuture(null);
            });
        }

        for (int index = 0; index < Math.min(weeklySlots.length, weekly.objectives().size()); index++) {
            BoardObjective objective = weekly.objectives().get(index);
            builder.button(weeklySlots[index], objectiveIcon(weekly, objective), context -> {
                handleObjectiveClick(context.player(), weekly.scope(), objective.id());
                context.open(buildMenu(context.player()));
                return CompletableFuture.completedFuture(null);
            });
        }

        builder.button(22, icon(Material.CLOCK, "Daily Objectives", List.of(
            "Rotation resets every UTC day.",
            "Daily board blends combat, gathering, and profession orders."
        )), context -> CompletableFuture.completedFuture(null));
        builder.button(31, icon(Material.NETHER_STAR, "Weekly Objectives", List.of(
            "Rotation resets every UTC ISO week.",
            "Weekly board pushes expeditions, breaches, and long-term progression."
        )), context -> CompletableFuture.completedFuture(null));
        builder.button(40, icon(Material.GLOW_ITEM_FRAME, "Season Track", List.of(
            "Adventure Board claims feed season renown.",
            "Expedition and breach clears also advance the track.",
            "Click to open the current season reward path."
        )), context -> {
            seasonTrackService.openTrack(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(49, icon(Material.BOOK, "Board Notes", List.of(
            "Commands: /adventure open, /adventure status, and /adventure season.",
            "Daily rewards: fast XP and token injections.",
            "Weekly rewards: larger progression pushes and a title unlock.",
            "Season renown converts those loops into a longer reward track.",
            "Some objectives are class-locked and only appear for matching classes."
        )), context -> CompletableFuture.completedFuture(null));

        return builder.build();
    }

    private void handleObjectiveClick(Player player, BoardScope scope, String objectiveId) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        BoardState state = boardState(profile, scope);
        BoardObjective objective = state.objectives().stream().filter(entry -> entry.id().equals(objectiveId)).findFirst().orElse(null);
        if (objective == null) {
            messageService.send(player, "That objective is no longer active.");
            return;
        }
        if (objective.requiredClass() != null && classSkillService.selectedClass(profile).orElse(null) != objective.requiredClass()) {
            messageService.send(player, "Class required: " + objective.requiredClass().displayName() + ".");
            return;
        }

        int progress = state.progress().getOrDefault(objective.id(), 0);
        if (state.claimed().contains(objective.id())) {
            messageService.send(player, objective.title() + " is already claimed.");
            return;
        }
        if (progress < objective.target()) {
            messageService.send(player,
                objective.title() + " progress: " + progress + "/" + objective.target() + "."
            );
            return;
        }

        Set<String> claimed = new LinkedHashSet<>(state.claimed());
        claimed.add(objective.id());
        writeClaimed(profile, scope, claimed);

        playerProfileService.grantExperience(player, objective.experienceReward(), "Adventure Board");
        playerProfileService.grantBreachTokens(player, objective.tokenReward(), "Adventure Board");
        seasonTrackService.grantRenown(player, objective.renownReward(), scope == BoardScope.WEEKLY ? "weekly Adventure Board" : "daily Adventure Board");
        messageService.send(player,
            "Claimed " + objective.title() + " for +" + objective.experienceReward()
                + " XP, +" + objective.tokenReward() + " breach tokens, and +" + objective.renownReward() + " season renown."
        );

        if (scope == BoardScope.WEEKLY) {
            BoardState refreshed = boardState(profile, scope);
            boolean allClaimed = refreshed.objectives().stream().allMatch(entry -> refreshed.claimed().contains(entry.id()));
            if (allClaimed && !playerProfileService.hasUnlockedTitle(profile, PATHFINDER_TITLE_ID)) {
                playerProfileService.unlockTitle(profile, PATHFINDER_TITLE_ID, false);
                messageService.send(player, "Weekly board sweep complete. Pathfinder title unlocked.");
            }
        }
    }

    private ItemStack objectiveIcon(BoardState state, BoardObjective objective) {
        int progress = state.progress().getOrDefault(objective.id(), 0);
        boolean claimed = state.claimed().contains(objective.id());
        List<String> lore = new ArrayList<>();
        lore.add(objective.description());
        if (objective.requiredClass() != null) {
            lore.add("Class Required: " + objective.requiredClass().displayName());
        }
        lore.add("Progress: " + progress + "/" + objective.target());
        lore.add("Reward: +" + objective.experienceReward() + " XP, +" + objective.tokenReward() + " breach tokens, +" + objective.renownReward() + " renown");
        lore.add(claimed ? "Claimed." : (progress >= objective.target() ? "Click to claim." : "Keep progressing."));
        return icon(objective.icon(), objective.title(), lore);
    }

    private String statusLine(BoardState state, BoardObjective objective) {
        int progress = state.progress().getOrDefault(objective.id(), 0);
        String claim = state.claimed().contains(objective.id()) ? "Claimed" : (progress >= objective.target() ? "Ready" : "In Progress");
        String requiredClass = objective.requiredClass() == null ? "" : " | Class: " + objective.requiredClass().displayName();
        return scopeLabel(state.scope()) + " | " + objective.title() + requiredClass + " | " + progress + "/" + objective.target() + " | " + claim;
    }

    private boolean qualifierMatches(String objectiveQualifier, String progressQualifier) {
        if (objectiveQualifier == null || objectiveQualifier.isBlank()) {
            return true;
        }
        return objectiveQualifier.equalsIgnoreCase(progressQualifier == null ? "" : progressQualifier);
    }

    private BoardState boardState(UnifiedPlayer profile, BoardScope scope) {
        String currentRotationId = currentRotationId(scope);
        if (!currentRotationId.equals(profile.metadata().get(rotationKey(scope)))) {
            profile.putMetadata(rotationKey(scope), currentRotationId);
            profile.putMetadata(progressKey(scope), "");
            profile.putMetadata(claimedKey(scope), "");
        }
        return new BoardState(
            scope,
            currentRotationId,
            objectives(profile, scope, currentRotationId),
            parseProgress(profile.metadata().get(progressKey(scope))),
            parseClaims(profile.metadata().get(claimedKey(scope)))
        );
    }

    private List<BoardObjective> objectives(UnifiedPlayer profile, BoardScope scope, String rotationId) {
        return switch (scope) {
            case DAILY -> List.of(
                pick(rotationId + ":combat", eligibleObjectives(profile, dailyCombatPool())),
                pick(rotationId + ":gather", eligibleObjectives(profile, dailyGatherPool())),
                pick(rotationId + ":craft", eligibleObjectives(profile, dailyCraftPool()))
            );
            case WEEKLY -> List.of(
                pick(rotationId + ":endgame", eligibleObjectives(profile, weeklyEndgamePool())),
                pick(rotationId + ":mastery", eligibleObjectives(profile, weeklyMasteryPool())),
                pick(rotationId + ":supply", eligibleObjectives(profile, weeklySupplyPool()))
            );
        };
    }

    private List<BoardObjective> eligibleObjectives(UnifiedPlayer profile, List<BoardObjective> pool) {
        VortexClass selectedClass = classSkillService.selectedClass(profile).orElse(null);
        List<BoardObjective> filtered = pool.stream()
            .filter(objective -> objective.requiredClass() == null || objective.requiredClass() == selectedClass)
            .toList();
        return filtered.isEmpty()
            ? pool.stream().filter(objective -> objective.requiredClass() == null).toList()
            : filtered;
    }

    private BoardObjective pick(String seed, List<BoardObjective> pool) {
        return pool.stream()
            .sorted(Comparator.comparingLong(objective -> weight(seed, objective.id())))
            .findFirst()
            .orElseThrow();
    }

    private long weight(String seed, String objectiveId) {
        UUID hash = UUID.nameUUIDFromBytes((seed + ":" + objectiveId).getBytes(StandardCharsets.UTF_8));
        return hash.getMostSignificantBits() ^ hash.getLeastSignificantBits();
    }

    private List<BoardObjective> dailyCombatPool() {
        return List.of(
            objective("daily_slayer", BoardScope.DAILY, ObjectiveType.MONSTER_KILLS, null, "Frontier Slayer", "Defeat hostile monsters across the frontier.", Material.IRON_SWORD, 20, 90, 4L),
            objective("daily_fracture_hunt", BoardScope.DAILY, ObjectiveType.FRACTURED_KILLS, null, "Fracture Sweep", "Cull fractured enemies in unstable regions.", Material.CRYING_OBSIDIAN, 12, 120, 6L),
            objective("daily_spell_drill", BoardScope.DAILY, ObjectiveType.SPELL_CASTS, null, "Spell Drill", "Cast your class spells in live combat.", Material.ENCHANTED_BOOK, 10, 95, 5L),
            objective("daily_knight_patrol", BoardScope.DAILY, ObjectiveType.MONSTER_KILLS, null, "Bulwark Patrol", "Hold the line and clear frontline hostiles.", Material.SHIELD, 18, 110, 5L, VortexClass.KNIGHT),
            objective("daily_mage_calibration", BoardScope.DAILY, ObjectiveType.SPELL_CASTS, null, "Arcane Calibration", "Run a live casting drill for mage fieldwork.", Material.BLAZE_ROD, 14, 112, 5L, VortexClass.MAGE),
            objective("daily_ranger_sightline", BoardScope.DAILY, ObjectiveType.MONSTER_KILLS, null, "Sightline Sweep", "Pick off roaming threats before they reach camp.", Material.BOW, 18, 108, 5L, VortexClass.RANGER),
            objective("daily_cleric_rite", BoardScope.DAILY, ObjectiveType.SPELL_CASTS, null, "Sanctified Rite", "Maintain healer readiness through controlled spell rotations.", Material.GOLDEN_APPLE, 14, 112, 5L, VortexClass.CLERIC)
        );
    }

    private List<BoardObjective> dailyGatherPool() {
        return List.of(
            objective("daily_mining_run", BoardScope.DAILY, ObjectiveType.MINING_NODES, null, "Ore Run", "Mine ore or stone nodes for the guild.", Material.IRON_PICKAXE, 24, 85, 4L),
            objective("daily_foraging_run", BoardScope.DAILY, ObjectiveType.FORAGING_HARVESTS, null, "Timber Route", "Gather logs or mature crops for field camps.", Material.DIAMOND_AXE, 28, 80, 4L),
            objective("daily_angler_run", BoardScope.DAILY, ObjectiveType.FISHING_CATCHES, null, "Angler Circuit", "Land fresh catches for expedition cooks.", Material.FISHING_ROD, 8, 75, 4L)
        );
    }

    private List<BoardObjective> dailyCraftPool() {
        return List.of(
            objective("daily_smith_order", BoardScope.DAILY, ObjectiveType.PROFESSION_CRAFTS, ProfessionType.SMITHING.id(), "Forge Order", "Complete smithing work orders for the war camp.", Material.SMITHING_TABLE, 1, 110, 8L),
            objective("daily_alchemy_order", BoardScope.DAILY, ObjectiveType.PROFESSION_CRAFTS, ProfessionType.ALCHEMY.id(), "Field Mixtures", "Brew support consumables for active parties.", Material.BREWING_STAND, 2, 105, 8L),
            objective("daily_cooking_order", BoardScope.DAILY, ObjectiveType.PROFESSION_CRAFTS, ProfessionType.COOKING.id(), "Camp Kitchen", "Prepare field rations for the next push.", Material.SMOKER, 2, 95, 7L)
        );
    }

    private List<BoardObjective> weeklyEndgamePool() {
        return List.of(
            objective("weekly_expedition_push", BoardScope.WEEKLY, ObjectiveType.EXPEDITION_CLEARS, null, "Expedition Vanguard", "Finish mythic expedition contracts as a field team.", Material.MAP, 2, 240, 16L),
            objective("weekly_breach_push", BoardScope.WEEKLY, ObjectiveType.BREACH_CLEARS, null, "Breachbreaker", "Seal an active breach and survive the finale.", Material.NETHER_STAR, 1, 360, 22L),
            objective("weekly_fracture_push", BoardScope.WEEKLY, ObjectiveType.FRACTURED_KILLS, null, "Pressure Relief", "Suppress a heavy wave of fractured enemies.", Material.OBSIDIAN, 80, 220, 14L)
        );
    }

    private List<BoardObjective> weeklyMasteryPool() {
        return List.of(
            objective("weekly_spell_mastery", BoardScope.WEEKLY, ObjectiveType.SPELL_CASTS, null, "Spell Discipline", "Cast through a full week of field encounters.", Material.BOOK, 45, 210, 10L),
            objective("weekly_crafter_guild", BoardScope.WEEKLY, ObjectiveType.PROFESSION_CRAFTS, null, "Craftsmen's Guild", "Complete multiple station recipes for the campaign.", Material.ANVIL, 6, 250, 14L),
            objective("weekly_hunter_guild", BoardScope.WEEKLY, ObjectiveType.MONSTER_KILLS, null, "Monster Census", "Thin the frontier and report your kills.", Material.CROSSBOW, 75, 205, 10L),
            objective("weekly_knight_linehold", BoardScope.WEEKLY, ObjectiveType.FRACTURED_KILLS, null, "Lineholder's Oath", "Break a heavy fractured push as the frontline anchor.", Material.NETHERITE_CHESTPLATE, 48, 255, 12L, VortexClass.KNIGHT),
            objective("weekly_mage_burstcycle", BoardScope.WEEKLY, ObjectiveType.SPELL_CASTS, null, "Burst Cycle Audit", "Maintain mage cast discipline across multiple encounters.", Material.END_CRYSTAL, 55, 255, 12L, VortexClass.MAGE),
            objective("weekly_ranger_huntchain", BoardScope.WEEKLY, ObjectiveType.MONSTER_KILLS, null, "Hunt Chain", "Sustain a ranger elimination route through the frontier.", Material.CROSSBOW, 85, 248, 12L, VortexClass.RANGER),
            objective("weekly_cleric_watch", BoardScope.WEEKLY, ObjectiveType.SPELL_CASTS, null, "Watchkeeper's Liturgy", "Keep healing cadence sharp for the next breach push.", Material.BEACON, 55, 255, 12L, VortexClass.CLERIC)
        );
    }

    private List<BoardObjective> weeklySupplyPool() {
        return List.of(
            objective("weekly_mining_manifest", BoardScope.WEEKLY, ObjectiveType.MINING_NODES, null, "Mining Manifest", "Pull raw material stock for the guild war chest.", Material.DIAMOND_PICKAXE, 96, 210, 10L),
            objective("weekly_foraging_manifest", BoardScope.WEEKLY, ObjectiveType.FORAGING_HARVESTS, null, "Foraging Manifest", "Deliver a full set of gathered supplies.", Material.NETHERITE_AXE, 96, 205, 10L),
            objective("weekly_angler_manifest", BoardScope.WEEKLY, ObjectiveType.FISHING_CATCHES, null, "Angler Manifest", "Supply expedition kitchens with rare catches.", Material.COD_BUCKET, 24, 190, 9L)
        );
    }

    private BoardObjective objective(
        String id,
        BoardScope scope,
        ObjectiveType type,
        String qualifier,
        String title,
        String description,
        Material icon,
        int target,
        int experienceReward,
        long tokenReward
    ) {
        int renownReward = scope == BoardScope.WEEKLY ? 40 : 25;
        return new BoardObjective(id, scope, type, qualifier, title, description, icon, target, experienceReward, tokenReward, renownReward, null);
    }

    private BoardObjective objective(
        String id,
        BoardScope scope,
        ObjectiveType type,
        String qualifier,
        String title,
        String description,
        Material icon,
        int target,
        int experienceReward,
        long tokenReward,
        VortexClass requiredClass
    ) {
        int renownReward = scope == BoardScope.WEEKLY ? 40 : 25;
        return new BoardObjective(id, scope, type, qualifier, title, description, icon, target, experienceReward, tokenReward, renownReward, requiredClass);
    }

    private String currentRotationId(BoardScope scope) {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        if (scope == BoardScope.DAILY) {
            return now.toString();
        }
        WeekFields fields = WeekFields.ISO;
        return now.get(fields.weekBasedYear()) + "-W" + String.format(Locale.US, "%02d", now.get(fields.weekOfWeekBasedYear()));
    }

    private String rotationKey(BoardScope scope) {
        return scope == BoardScope.DAILY ? DAILY_ROTATION_KEY : WEEKLY_ROTATION_KEY;
    }

    private String progressKey(BoardScope scope) {
        return scope == BoardScope.DAILY ? DAILY_PROGRESS_KEY : WEEKLY_PROGRESS_KEY;
    }

    private String claimedKey(BoardScope scope) {
        return scope == BoardScope.DAILY ? DAILY_CLAIMED_KEY : WEEKLY_CLAIMED_KEY;
    }

    private void writeProgress(UnifiedPlayer profile, BoardScope scope, Map<String, Integer> progress) {
        profile.putMetadata(progressKey(scope), encodeProgress(progress));
    }

    private void writeClaimed(UnifiedPlayer profile, BoardScope scope, Set<String> claimed) {
        profile.putMetadata(claimedKey(scope), String.join(",", claimed));
    }

    private Map<String, Integer> parseProgress(String raw) {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String token : raw.split(";")) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            try {
                parsed.put(pair[0].trim(), Integer.parseInt(pair[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }

    private Set<String> parseClaims(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> claims = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token.trim();
            if (!normalized.isBlank()) {
                claims.add(normalized);
            }
        }
        return Set.copyOf(claims);
    }

    private String encodeProgress(Map<String, Integer> progress) {
        return progress.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + ";" + right)
            .orElse("");
    }

    private String scopeLabel(BoardScope scope) {
        return scope == BoardScope.DAILY ? "Daily" : "Weekly";
    }

    private boolean isMiningBlock(Material material) {
        return switch (material) {
            case STONE, DEEPSLATE, COBBLESTONE, COBBLED_DEEPSLATE,
                COAL_ORE, DEEPSLATE_COAL_ORE, IRON_ORE, DEEPSLATE_IRON_ORE,
                COPPER_ORE, DEEPSLATE_COPPER_ORE, GOLD_ORE, DEEPSLATE_GOLD_ORE,
                REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                NETHER_QUARTZ_ORE, NETHER_GOLD_ORE, ANCIENT_DEBRIS, OBSIDIAN -> true;
            default -> false;
        };
    }

    private boolean isForagingBlock(Material material) {
        return switch (material) {
            case OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG, CRIMSON_STEM, WARPED_STEM,
                WHEAT, CARROTS, POTATOES, BEETROOT, NETHER_WART -> true;
            default -> false;
        };
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private record BoardState(BoardScope scope, String rotationId, List<BoardObjective> objectives, Map<String, Integer> progress, Set<String> claimed) {
    }

    private record BoardObjective(
        String id,
        BoardScope scope,
        ObjectiveType type,
        String qualifier,
        String title,
        String description,
        Material icon,
        int target,
        int experienceReward,
        long tokenReward,
        int renownReward,
        VortexClass requiredClass
    ) {
    }

    private enum BoardScope {
        DAILY,
        WEEKLY
    }

    private enum ObjectiveType {
        MONSTER_KILLS,
        FRACTURED_KILLS,
        MINING_NODES,
        FORAGING_HARVESTS,
        FISHING_CATCHES,
        SPELL_CASTS,
        PROFESSION_CRAFTS,
        EXPEDITION_CLEARS,
        BREACH_CLEARS
    }
}
