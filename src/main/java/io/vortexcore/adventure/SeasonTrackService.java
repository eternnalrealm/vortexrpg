package io.vortexcore.adventure;

import io.vortexcore.cosmetic.PrestigeCosmeticService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import io.vortexcore.world.ExpeditionCompletion;
import io.vortexcore.world.ExpeditionScoreTier;
import io.vortexcore.world.MythicBreachManager;
import io.vortexcore.world.RiftSpawnController;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class SeasonTrackService implements
    RiftSpawnController.ExpeditionCompletionListener,
    MythicBreachManager.BreachCompletionListener {

    private static final String SEASON_ID_KEY = "season.id";
    private static final String SEASON_RENOWN_KEY = "season.renown";
    private static final String SEASON_CLAIMED_LEVELS_KEY = "season.claimed_levels";

    private final VortexMenuBuilder menuBuilder;
    private final PlayerProfileService playerProfileService;
    private final PrestigeCosmeticService prestigeCosmeticService;
    private final MessageService messageService;
    private final int renownPerLevel;
    private final int adventureDailyRenown;
    private final int adventureWeeklyRenown;
    private final int expeditionSRenown;
    private final int expeditionARenown;
    private final int expeditionBRenown;
    private final int expeditionCRenown;
    private final int breachSRenown;
    private final int breachARenown;
    private final int breachBRenown;
    private final int breachCRenown;
    private final List<SeasonRewardLevel> rewardLevels;

    public SeasonTrackService(
        VortexMenuBuilder menuBuilder,
        PlayerProfileService playerProfileService,
        PrestigeCosmeticService prestigeCosmeticService,
        MessageService messageService,
        int renownPerLevel,
        int adventureDailyRenown,
        int adventureWeeklyRenown,
        int expeditionSRenown,
        int expeditionARenown,
        int expeditionBRenown,
        int expeditionCRenown,
        int breachSRenown,
        int breachARenown,
        int breachBRenown,
        int breachCRenown
    ) {
        this.menuBuilder = menuBuilder;
        this.playerProfileService = playerProfileService;
        this.prestigeCosmeticService = prestigeCosmeticService;
        this.messageService = messageService;
        this.renownPerLevel = Math.max(25, renownPerLevel);
        this.adventureDailyRenown = Math.max(1, adventureDailyRenown);
        this.adventureWeeklyRenown = Math.max(1, adventureWeeklyRenown);
        this.expeditionSRenown = Math.max(1, expeditionSRenown);
        this.expeditionARenown = Math.max(1, expeditionARenown);
        this.expeditionBRenown = Math.max(1, expeditionBRenown);
        this.expeditionCRenown = Math.max(1, expeditionCRenown);
        this.breachSRenown = Math.max(1, breachSRenown);
        this.breachARenown = Math.max(1, breachARenown);
        this.breachBRenown = Math.max(1, breachBRenown);
        this.breachCRenown = Math.max(1, breachCRenown);
        this.rewardLevels = List.of(
            reward(1, "Field Ledger I", "Open the season path with your first renown push.", Material.PAPER, 140, 6, null, "starlit_stride", null, null),
            reward(2, "Field Ledger II", "Steady objective work funds the next leap.", Material.WRITABLE_BOOK, 180, 8, null, null, null, null),
            reward(3, "Ember Pulse", "Unlock a cast aura that flashes on successful spells.", Material.BLAZE_POWDER, 220, 10, null, null, "emberpulse", null),
            reward(4, "Rift Walker", "Unlock a permanent prestige title.", Material.NAME_TAG, 260, 12, "rift_walker", null, null, null),
            reward(5, "Frontier Standard", "Claim your first breach banner cosmetic.", Material.BLUE_BANNER, 320, 16, null, null, null, "frontier_standard"),
            reward(6, "Verdant Wake", "A brighter trail for veteran runners.", Material.MOSS_BLOCK, 380, 18, null, "verdant_wake", null, null),
            reward(7, "War Chest", "A deeper season payout for active players.", Material.CHEST, 440, 22, null, null, null, null),
            reward(8, "Astral Flare", "Unlock a higher-tier cast aura.", Material.AMETHYST_CLUSTER, 520, 26, null, null, "astral_flare", null),
            reward(9, "Breachborne", "A late-season title for frontline clears.", Material.NETHER_STAR, 620, 30, "breachborne", null, null, null),
            reward(10, "Breach Standard", "The top-end breach banner cosmetic.", Material.RED_BANNER, 760, 36, null, null, null, "breach_standard")
        );
    }

    public void openTrack(Player player) {
        menuBuilder.open(player, buildMenu(player));
    }

    public List<String> statusLines(Player player) {
        SeasonSnapshot snapshot = snapshot(playerProfileService.profile(player));
        List<String> lines = new ArrayList<>();
        lines.add("Season: " + snapshot.seasonId());
        lines.add("Renown: " + snapshot.renown() + " | Level: " + snapshot.currentLevel() + "/" + rewardLevels.size());
        lines.add("To Next: " + snapshot.renownToNextLevel());
        lines.add("Claimed Levels: " + snapshot.claimedLevels().size() + "/" + rewardLevels.size());
        return List.copyOf(lines);
    }

    public String currentSeasonId() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        int quarter = ((now.getMonthValue() - 1) / 3) + 1;
        return now.getYear() + "-Q" + quarter;
    }

    public int currentLevel(UnifiedPlayer profile) {
        return snapshot(profile).currentLevel();
    }

    public long renown(UnifiedPlayer profile) {
        return snapshot(profile).renown();
    }

    public long renownToNextLevel(UnifiedPlayer profile) {
        return snapshot(profile).renownToNextLevel();
    }

    public void grantAdventureRenown(Player player, boolean weekly) {
        grantRenown(player, weekly ? adventureWeeklyRenown : adventureDailyRenown, weekly ? "weekly Adventure Board" : "daily Adventure Board");
    }

    public void grantRenown(Player player, long amount, String reason) {
        if (amount <= 0L) {
            return;
        }
        UnifiedPlayer profile = playerProfileService.profile(player);
        SeasonSnapshot before = snapshot(profile);
        long updatedRenown = before.renown() + amount;
        profile.putMetadata(SEASON_RENOWN_KEY, Long.toString(updatedRenown));
        SeasonSnapshot after = snapshot(profile);
        messageService.send(player, "Season renown +" + amount + " from " + reason + ".");
        if (after.currentLevel() > before.currentLevel()) {
            messageService.send(player, "Season track advanced to level " + after.currentLevel() + ".");
        }
    }

    @Override
    public void onExpeditionComplete(java.util.UUID playerId, ExpeditionCompletion completion) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        grantRenown(player, renownForTier(completion.scoreTier(), false), "mythic expedition clear");
    }

    @Override
    public void onBreachClear(java.util.UUID playerId, ExpeditionCompletion completion, long tokenReward) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        grantRenown(player, renownForTier(completion.scoreTier(), true), "breach finale clear");
    }

    private VortexMenuBuilder.MenuDefinition buildMenu(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        SeasonSnapshot snapshot = snapshot(profile);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Season Track"), 6);
        builder.button(4, icon(Material.NETHER_STAR, "Season " + snapshot.seasonId(), List.of(
            "Renown: " + snapshot.renown(),
            "Level: " + snapshot.currentLevel() + "/" + rewardLevels.size(),
            "To Next: " + snapshot.renownToNextLevel(),
            "Adventure Board, expeditions, and breaches all feed this track."
        )), context -> CompletableFuture.completedFuture(null));

        int[] slots = {10, 11, 12, 13, 14, 15, 28, 29, 30, 31};
        for (int index = 0; index < Math.min(slots.length, rewardLevels.size()); index++) {
            SeasonRewardLevel reward = rewardLevels.get(index);
            builder.button(slots[index], rewardIcon(snapshot, reward), context -> {
                claimLevel(context.player(), reward.level());
                context.open(buildMenu(context.player()));
                return CompletableFuture.completedFuture(null);
            });
        }

        builder.button(49, icon(Material.BOOK, "Season Notes", List.of(
            "Adventure Board claims add renown.",
            "Expedition clears add renown based on score tier.",
            "Breach clears add the biggest renown spikes.",
            "Unlocked cosmetics stay on your profile after the season rotates."
        )), context -> CompletableFuture.completedFuture(null));

        return builder.build();
    }

    private void claimLevel(Player player, int level) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        SeasonSnapshot snapshot = snapshot(profile);
        SeasonRewardLevel reward = rewardLevels.stream().filter(entry -> entry.level() == level).findFirst().orElse(null);
        if (reward == null) {
            messageService.send(player, "That season reward no longer exists.");
            return;
        }
        if (snapshot.claimedLevels().contains(level)) {
            messageService.send(player, "Season level " + level + " is already claimed.");
            return;
        }
        if (snapshot.currentLevel() < level) {
            messageService.send(player, "Reach season level " + level + " first.");
            return;
        }

        Set<Integer> claimed = new LinkedHashSet<>(snapshot.claimedLevels());
        claimed.add(level);
        profile.putMetadata(SEASON_CLAIMED_LEVELS_KEY, encodeClaims(claimed));

        playerProfileService.grantExperience(player, reward.experienceReward(), "Season Track");
        playerProfileService.grantBreachTokens(player, reward.tokenReward(), "Season Track");
        if (reward.titleId() != null) {
            playerProfileService.unlockTitle(profile, reward.titleId(), true);
        }
        if (reward.trailId() != null) {
            playerProfileService.unlockTrail(profile, reward.trailId(), true);
        }
        if (reward.auraId() != null) {
            playerProfileService.unlockAura(profile, reward.auraId(), true);
        }
        if (reward.bannerId() != null) {
            playerProfileService.unlockBanner(profile, reward.bannerId(), true);
            ItemStack banner = prestigeCosmeticService.buildBannerItem(reward.bannerId());
            player.getInventory().addItem(banner).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        messageService.send(player, "Claimed season level " + level + ": " + reward.title() + ".");
    }

    private ItemStack rewardIcon(SeasonSnapshot snapshot, SeasonRewardLevel reward) {
        boolean claimed = snapshot.claimedLevels().contains(reward.level());
        boolean unlocked = snapshot.currentLevel() >= reward.level();
        List<String> lore = new ArrayList<>();
        lore.add(reward.description());
        lore.add("Rewards: +" + reward.experienceReward() + " XP, +" + reward.tokenReward() + " breach tokens");
        if (reward.titleId() != null) {
            lore.add("Title: " + NameFormat.friendly(reward.titleId()));
        }
        if (reward.trailId() != null) {
            lore.add("Trail: " + NameFormat.friendly(reward.trailId()));
        }
        if (reward.auraId() != null) {
            lore.add("Cast Aura: " + NameFormat.friendly(reward.auraId()));
        }
        if (reward.bannerId() != null) {
            lore.add("Banner: " + NameFormat.friendly(reward.bannerId()));
        }
        lore.add(claimed ? "Claimed." : (unlocked ? "Click to claim." : "Unlock by earning more renown."));
        return icon(reward.icon(), "Level " + reward.level() + " | " + reward.title(), lore);
    }

    private SeasonSnapshot snapshot(UnifiedPlayer profile) {
        String liveSeasonId = currentSeasonId();
        String storedSeasonId = profile.metadata().get(SEASON_ID_KEY);
        if (!liveSeasonId.equals(storedSeasonId)) {
            profile.putMetadata(SEASON_ID_KEY, liveSeasonId);
            profile.putMetadata(SEASON_RENOWN_KEY, "0");
            profile.putMetadata(SEASON_CLAIMED_LEVELS_KEY, "");
        }
        long renown = readLong(profile.metadata().get(SEASON_RENOWN_KEY));
        int currentLevel = Math.min(rewardLevels.size(), (int) (renown / renownPerLevel));
        long renownToNext = currentLevel >= rewardLevels.size() ? 0L : Math.max(0L, ((long) (currentLevel + 1) * renownPerLevel) - renown);
        return new SeasonSnapshot(liveSeasonId, renown, currentLevel, renownToNext, parseClaims(profile.metadata().get(SEASON_CLAIMED_LEVELS_KEY)));
    }

    private int renownForTier(ExpeditionScoreTier tier, boolean breach) {
        return switch (tier) {
            case S -> breach ? breachSRenown : expeditionSRenown;
            case A -> breach ? breachARenown : expeditionARenown;
            case B -> breach ? breachBRenown : expeditionBRenown;
            case C -> breach ? breachCRenown : expeditionCRenown;
        };
    }

    private SeasonRewardLevel reward(
        int level,
        String title,
        String description,
        Material icon,
        int experienceReward,
        long tokenReward,
        String titleId,
        String trailId,
        String auraId,
        String bannerId
    ) {
        return new SeasonRewardLevel(level, title, description, icon, experienceReward, tokenReward, titleId, trailId, auraId, bannerId);
    }

    private Set<Integer> parseClaims(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Integer> claims = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            try {
                claims.add(Integer.parseInt(token.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return Set.copyOf(claims);
    }

    private String encodeClaims(Set<Integer> claims) {
        return claims.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }

    private long readLong(String raw) {
        try {
            return Long.parseLong(raw == null ? "0" : raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private record SeasonSnapshot(String seasonId, long renown, int currentLevel, long renownToNextLevel, Set<Integer> claimedLevels) {
    }

    private record SeasonRewardLevel(
        int level,
        String title,
        String description,
        Material icon,
        int experienceReward,
        long tokenReward,
        String titleId,
        String trailId,
        String auraId,
        String bannerId
    ) {
    }
}
