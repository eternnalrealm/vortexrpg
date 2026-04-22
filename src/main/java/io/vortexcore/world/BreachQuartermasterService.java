package io.vortexcore.world;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class BreachQuartermasterService {

    private static final String PURCHASE_META_PREFIX = "quartermaster.purchases.";

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final PlayerProfileService playerProfileService;
    private final FractureLootService fractureLootService;
    private final MessageService messageService;

    public BreachQuartermasterService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        PlayerProfileService playerProfileService,
        FractureLootService fractureLootService,
        MessageService messageService
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.playerProfileService = playerProfileService;
        this.fractureLootService = fractureLootService;
        this.messageService = messageService;
    }

    public void openQuartermaster(Player player) {
        menuBuilder.open(player, buildMenu(player));
    }

    public String currentRotationId() {
        return currentRotation().rotationId();
    }

    private VortexMenuBuilder.MenuDefinition buildMenu(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        Rotation rotation = currentRotation();
        List<QuartermasterOffer> offers = rotation.offers();
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Breach Quartermaster"), 6);

        builder.button(4, icon(Material.NETHER_STAR, "Breach Quartermaster", List.of(
            "Rotation: " + rotation.rotationId(),
            "Breach Tokens: " + playerProfileService.breachTokenBalance(profile),
            "Active Title: " + playerProfileService.activeTitle(profile).map(NameFormat::friendly).orElse("None"),
            "Featured stock rotates weekly."
        )), context -> CompletableFuture.completedFuture(null));

        int[] slots = {19, 20, 21, 23, 24, 25, 28, 29, 30, 32, 33, 34};
        for (int index = 0; index < Math.min(slots.length, offers.size()); index++) {
            QuartermasterOffer offer = offers.get(index);
            builder.button(slots[index], iconForOffer(profile, rotation.rotationId(), offer), context ->
                purchaseOrEquip(context.player(), rotation.rotationId(), offer)
                    .whenComplete((ignored, error) -> context.open(buildMenu(context.player())))
            );
        }

        builder.button(49, icon(Material.BOOK, "Rotation Notes", List.of(
            "Core stock: Memory Prism, Veteran Cache, Nullstone Totem.",
            "Featured stock: rotating titles, premium caches, and cosmetics.",
            "Titles can be re-equipped for free when featured and already owned."
        )), context -> CompletableFuture.completedFuture(null));

        return builder.build();
    }

    private CompletableFuture<Void> purchaseOrEquip(Player player, String rotationId, QuartermasterOffer offer) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "quartermaster-purchase-" + player.getUniqueId(), () -> {
            UnifiedPlayer profile = playerProfileService.profile(player);

            if (offer.rewardKind() == RewardKind.TITLE && playerProfileService.hasUnlockedTitle(profile, offer.rewardId())) {
                playerProfileService.equipTitle(profile, offer.rewardId());
                messageService.send(player, "Equipped title: " + offer.displayName() + ".");
                future.complete(null);
                return;
            }

            Map<String, Integer> purchases = parsePurchases(profile.metadata().get(purchaseKey(rotationId)));
            int purchased = purchases.getOrDefault(offer.id(), 0);
            if (purchased >= offer.weeklyLimit()) {
                messageService.send(player, "Weekly stock exhausted for " + offer.displayName() + ".");
                future.complete(null);
                return;
            }

            if (!playerProfileService.spendBreachTokens(profile, offer.tokenCost())) {
                messageService.send(player,
                    "You need " + offer.tokenCost() + " breach tokens for " + offer.displayName() + "."
                );
                future.complete(null);
                return;
            }

            purchases.put(offer.id(), purchased + 1);
            profile.putMetadata(purchaseKey(rotationId), encodePurchases(purchases));

            deliverReward(player, profile, offer);
            messageService.send(player,
                "Purchased " + offer.displayName() + " for " + offer.tokenCost()
                    + " breach tokens. Remaining: " + playerProfileService.breachTokenBalance(profile) + "."
            );
            future.complete(null);
        });
        return future;
    }

    private void deliverReward(Player player, UnifiedPlayer profile, QuartermasterOffer offer) {
        switch (offer.rewardKind()) {
            case EXPERIENCE -> playerProfileService.grantExperience(player, offer.experienceReward(), "the Breach Quartermaster");
            case LOOT_CACHE -> {
                List<ItemStack> rewards = fractureLootService.buildBreachCacheRewards(syntheticBreach(offer.syntheticThreatScore()), offer.scoreTier());
                giveItems(player, rewards);
            }
            case TITLE -> {
                playerProfileService.unlockTitle(profile, offer.rewardId(), true);
                messageService.send(player, "Unlocked title: " + offer.displayName() + ".");
            }
            case ITEM -> giveItems(player, List.of(buildQuartermasterItem(offer)));
        }
    }

    private void giveItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
            overflow.values().forEach(remaining -> player.getWorld().dropItemNaturally(player.getLocation(), remaining));
        }
    }

    private ItemStack buildQuartermasterItem(QuartermasterOffer offer) {
        ItemStack item = new ItemStack(offer.rewardMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(offer.displayName()));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(offer.description()));
        lore.add(Component.text("Quartermaster Stock"));
        if (offer.rewardMaterial() == Material.TOTEM_OF_UNDYING) {
            lore.add(Component.text("A rare field relic pulled from breach salvage."));
        }
        if (offer.rewardMaterial() == Material.BLACK_BANNER) {
            lore.add(Component.text("A cosmetic war trophy for your stronghold."));
        }
        meta.lore(List.copyOf(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack iconForOffer(UnifiedPlayer profile, String rotationId, QuartermasterOffer offer) {
        int purchased = parsePurchases(profile.metadata().get(purchaseKey(rotationId))).getOrDefault(offer.id(), 0);
        boolean ownedTitle = offer.rewardKind() == RewardKind.TITLE && playerProfileService.hasUnlockedTitle(profile, offer.rewardId());
        List<String> lore = new ArrayList<>();
        lore.add(offer.description());
        lore.add("Cost: " + offer.tokenCost() + " breach tokens");
        lore.add("Weekly Stock: " + Math.max(0, offer.weeklyLimit() - purchased) + "/" + offer.weeklyLimit());
        if (offer.rewardKind() == RewardKind.LOOT_CACHE) {
            lore.add("Reward: " + NameFormat.friendly(offer.scoreTier().name()) + " breach cache");
        }
        if (offer.rewardKind() == RewardKind.EXPERIENCE) {
            lore.add("Reward: +" + offer.experienceReward() + " RPG XP");
        }
        if (offer.rewardKind() == RewardKind.TITLE) {
            lore.add(ownedTitle ? "Owned. Click to equip." : "Unlocks a cosmetic title.");
        }
        if (offer.featured()) {
            lore.add("Featured this week.");
        } else {
            lore.add("Core stock.");
        }
        return icon(offer.icon(), offer.displayName(), lore);
    }

    private Rotation currentRotation() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        WeekFields fields = WeekFields.ISO;
        int year = now.get(fields.weekBasedYear());
        int week = now.get(fields.weekOfWeekBasedYear());
        String rotationId = year + "-W" + String.format(Locale.US, "%02d", week);

        List<QuartermasterOffer> offers = new ArrayList<>();
        offers.add(OfferTemplates.MEMORY_PRISM.toOffer(false));
        offers.add(OfferTemplates.VETERAN_CACHE.toOffer(false));
        offers.add(OfferTemplates.NULLSTONE_TOTEM.toOffer(false));

        List<OfferTemplates> featuredPool = new ArrayList<>(List.of(
            OfferTemplates.SOVEREIGN_CACHE,
            OfferTemplates.FRACTURE_STANDARD,
            OfferTemplates.PHASEWALKER_TITLE,
            OfferTemplates.BREACHLORD_TITLE
        ));
        featuredPool.sort(Comparator.comparing(template -> rotationWeight(rotationId, template.id())));
        featuredPool.stream().limit(2).map(template -> template.toOffer(true)).forEach(offers::add);
        return new Rotation(rotationId, List.copyOf(offers));
    }

    private long rotationWeight(String rotationId, String offerId) {
        UUID weightId = UUID.nameUUIDFromBytes((rotationId + ":" + offerId).getBytes(StandardCharsets.UTF_8));
        return weightId.getMostSignificantBits() ^ weightId.getLeastSignificantBits();
    }

    private FractureRegionSnapshot syntheticBreach(double threatScore) {
        long now = System.currentTimeMillis();
        FractureProfile profile = FractureProfile.fallback();
        return new FractureRegionSnapshot(
            new FractureRegionKey(UUID.nameUUIDFromBytes("quartermaster-breach".getBytes(StandardCharsets.UTF_8)), 0, 0),
            "quartermaster-breach",
            profile,
            ThreatSource.ADMIN_FORCED,
            FractureState.BOSS_BREACH,
            Set.of(),
            threatScore,
            0.0D,
            now,
            now
        );
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String purchaseKey(String rotationId) {
        return PURCHASE_META_PREFIX + rotationId;
    }

    private Map<String, Integer> parsePurchases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String token : raw.split(";")) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            try {
                parsed.put(pair[0].trim().toLowerCase(Locale.ROOT), Integer.parseInt(pair[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }

    private String encodePurchases(Map<String, Integer> purchases) {
        return purchases.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + ";" + right)
            .orElse("");
    }

    private enum RewardKind {
        EXPERIENCE,
        LOOT_CACHE,
        TITLE,
        ITEM
    }

    private record Rotation(String rotationId, List<QuartermasterOffer> offers) {
    }

    private record QuartermasterOffer(
        String id,
        String rewardId,
        String displayName,
        String description,
        Material icon,
        Material rewardMaterial,
        int tokenCost,
        int weeklyLimit,
        RewardKind rewardKind,
        ExpeditionScoreTier scoreTier,
        int experienceReward,
        double syntheticThreatScore,
        boolean featured
    ) {
    }

    private enum OfferTemplates {
        MEMORY_PRISM(
            "memory_prism",
            "memory_prism",
            "Memory Prism",
            "Refined breach memory converted into instant RPG experience.",
            Material.EXPERIENCE_BOTTLE,
            Material.EXPERIENCE_BOTTLE,
            14,
            3,
            RewardKind.EXPERIENCE,
            ExpeditionScoreTier.B,
            550,
            260.0D
        ),
        VETERAN_CACHE(
            "veteran_cache",
            "veteran_cache",
            "Veteran Cache",
            "A compact cache of breach salvage tuned for regular mythic runners.",
            Material.ENDER_CHEST,
            Material.ENDER_CHEST,
            20,
            2,
            RewardKind.LOOT_CACHE,
            ExpeditionScoreTier.A,
            0,
            295.0D
        ),
        NULLSTONE_TOTEM(
            "nullstone_totem",
            "nullstone_totem",
            "Nullstone Totem",
            "A practical anti-void relic salvaged from collapsed breach cores.",
            Material.TOTEM_OF_UNDYING,
            Material.TOTEM_OF_UNDYING,
            24,
            1,
            RewardKind.ITEM,
            ExpeditionScoreTier.B,
            0,
            0.0D
        ),
        SOVEREIGN_CACHE(
            "sovereign_cache",
            "sovereign_cache",
            "Sovereign Cache",
            "Premium breach salvage reserved for parties that keep climbing.",
            Material.NETHER_STAR,
            Material.NETHER_STAR,
            38,
            1,
            RewardKind.LOOT_CACHE,
            ExpeditionScoreTier.S,
            0,
            360.0D
        ),
        FRACTURE_STANDARD(
            "fracture_standard",
            "fracture_standard",
            "Fracture Standard",
            "A ceremonial war banner marking a breach party's victories.",
            Material.BLACK_BANNER,
            Material.BLACK_BANNER,
            12,
            2,
            RewardKind.ITEM,
            ExpeditionScoreTier.C,
            0,
            0.0D
        ),
        PHASEWALKER_TITLE(
            "phasewalker_title",
            "phasewalker",
            "Phasewalker",
            "Unlocks a cosmetic title earned by surviving unstable finales.",
            Material.AMETHYST_SHARD,
            Material.AMETHYST_SHARD,
            18,
            1,
            RewardKind.TITLE,
            ExpeditionScoreTier.C,
            0,
            0.0D
        ),
        BREACHLORD_TITLE(
            "breachlord_title",
            "breachlord",
            "Breachlord",
            "Unlocks a prestige title for players living in endgame rotations.",
            Material.WITHER_ROSE,
            Material.WITHER_ROSE,
            32,
            1,
            RewardKind.TITLE,
            ExpeditionScoreTier.C,
            0,
            0.0D
        );

        private final String id;
        private final String rewardId;
        private final String displayName;
        private final String description;
        private final Material icon;
        private final Material rewardMaterial;
        private final int tokenCost;
        private final int weeklyLimit;
        private final RewardKind rewardKind;
        private final ExpeditionScoreTier scoreTier;
        private final int experienceReward;
        private final double syntheticThreatScore;

        OfferTemplates(
            String id,
            String rewardId,
            String displayName,
            String description,
            Material icon,
            Material rewardMaterial,
            int tokenCost,
            int weeklyLimit,
            RewardKind rewardKind,
            ExpeditionScoreTier scoreTier,
            int experienceReward,
            double syntheticThreatScore
        ) {
            this.id = id;
            this.rewardId = rewardId;
            this.displayName = displayName;
            this.description = description;
            this.icon = icon;
            this.rewardMaterial = rewardMaterial;
            this.tokenCost = tokenCost;
            this.weeklyLimit = weeklyLimit;
            this.rewardKind = rewardKind;
            this.scoreTier = scoreTier;
            this.experienceReward = experienceReward;
            this.syntheticThreatScore = syntheticThreatScore;
        }

        private String id() {
            return id;
        }

        private QuartermasterOffer toOffer(boolean featured) {
            return new QuartermasterOffer(
                id,
                rewardId,
                displayName,
                description,
                icon,
                rewardMaterial,
                tokenCost,
                weeklyLimit,
                rewardKind,
                scoreTier,
                experienceReward,
                syntheticThreatScore,
                featured
            );
        }
    }
}
