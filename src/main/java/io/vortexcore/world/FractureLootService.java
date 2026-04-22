package io.vortexcore.world;

import io.vortexcore.economy.DynamicMarketManager;
import io.vortexcore.item.CustomItemDefinition;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.loot.GaussianLootGenerator;
import io.vortexcore.loot.LootProfile;
import io.vortexcore.loot.LootRoll;
import java.util.ArrayList;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class FractureLootService {

    private final GaussianLootGenerator lootGenerator;
    private final DynamicMarketManager marketManager;
    private final CustomItemService customItemService;
    private final double baseDropChance;
    private final double bossDropChance;
    private final NamespacedKey rarityKey;
    private final NamespacedKey fractureStateKey;

    public FractureLootService(
        Plugin plugin,
        CustomItemService customItemService,
        GaussianLootGenerator lootGenerator,
        DynamicMarketManager marketManager,
        double baseDropChance,
        double bossDropChance
    ) {
        this.customItemService = customItemService;
        this.lootGenerator = lootGenerator;
        this.marketManager = marketManager;
        this.baseDropChance = baseDropChance;
        this.bossDropChance = bossDropChance;
        this.rarityKey = new NamespacedKey(plugin, "fracture_rarity");
        this.fractureStateKey = new NamespacedKey(plugin, "fracture_state");

        registerMarketItems();
        registerCustomItems();
    }

    public Optional<FractureRewardDrop> rollDrop(FractureRegionSnapshot fracture, LivingEntity entity) {
        double dropChance = fracture.state() == FractureState.BOSS_BREACH
            ? bossDropChance
            : Math.min(1.0D, baseDropChance + (fracture.state().severity() * 0.12D));

        if (ThreadLocalRandom.current().nextDouble() > dropChance) {
            return Optional.empty();
        }

        String itemId = rewardItemId(fracture.state());
        int itemLevel = Math.max(1, (int) Math.round(fracture.threatScore() / 15.0D) + fracture.state().severity() + entity.getType().ordinal() % 4);
        LootRoll roll = lootGenerator.roll(itemId, itemLevel, buildProfiles(fracture, entity));
        ItemStack item = buildItem(fracture, roll);
        return Optional.of(new FractureRewardDrop(roll, item));
    }

    public String previewRewardName(FractureState state) {
        return NameFormat.friendly(rewardItemId(state));
    }

    public List<ItemStack> buildBreachCacheRewards(FractureRegionSnapshot fracture, ExpeditionScoreTier scoreTier) {
        int rewardCount = switch (scoreTier) {
            case S -> 3;
            case A -> 2;
            case B, C -> 1;
        };

        int baseLevel = Math.max(1, (int) Math.round(fracture.threatScore() / 12.0D) + (fracture.state().severity() * 2));
        List<ItemStack> rewards = new ArrayList<>();
        for (int index = 0; index < rewardCount; index++) {
            LootRoll roll = lootGenerator.roll("breach_heart", baseLevel + (index * 2), buildBreachProfiles(fracture, scoreTier, index));
            rewards.add(buildItem(fracture, roll));
        }
        return List.copyOf(rewards);
    }

    private void registerMarketItems() {
        marketManager.registerItem("fracture_shard", 18.0D, 180L);
        marketManager.registerItem("tainted_essence", 42.0D, 120L);
        marketManager.registerItem("stabilized_relic", 85.0D, 72L);
        marketManager.registerItem("breach_heart", 240.0D, 16L);
    }

    private List<LootProfile> buildProfiles(FractureRegionSnapshot fracture, LivingEntity entity) {
        double severity = fracture.state().severity();
        double modifierBonus = fracture.modifiers().size() * 0.35D;
        double entityBonus = Math.max(0.0D, entity.getHealth() / 10.0D);

        List<LootProfile> profiles = new ArrayList<>();
        profiles.add(new LootProfile("potency", 4.0D + severity, 0.85D, 1.4D + modifierBonus, 4.5D));
        profiles.add(new LootProfile("resilience", 3.0D + severity, 0.65D, 1.2D + modifierBonus, 3.8D));
        profiles.add(new LootProfile("focus", 2.5D + modifierBonus, 0.55D, 1.1D + (severity * 0.15D), 3.4D));
        profiles.add(new LootProfile("haste", 1.5D + (entityBonus * 0.1D), 0.35D, 0.8D + (severity * 0.08D), 2.4D));

        if (fracture.state() == FractureState.BOSS_BREACH) {
            profiles.add(new LootProfile("mythic_charge", 6.5D, 1.15D, 1.8D, 5.2D));
        }

        return profiles;
    }

    private List<LootProfile> buildBreachProfiles(FractureRegionSnapshot fracture, ExpeditionScoreTier scoreTier, int rewardIndex) {
        double severity = fracture.state().severity();
        double rankBonus = switch (scoreTier) {
            case S -> 2.1D;
            case A -> 1.4D;
            case B -> 0.9D;
            case C -> 0.5D;
        };
        double rewardOffset = rewardIndex * 0.35D;

        List<LootProfile> profiles = new ArrayList<>();
        profiles.add(new LootProfile("mythic_charge", 7.5D + rankBonus, 1.15D, 1.75D + rewardOffset, 5.4D));
        profiles.add(new LootProfile("potency", 5.5D + severity + rankBonus, 0.95D, 1.6D, 4.8D));
        profiles.add(new LootProfile("resilience", 4.5D + severity + (rankBonus * 0.75D), 0.75D, 1.4D, 4.2D));
        profiles.add(new LootProfile("focus", 3.5D + rankBonus, 0.70D, 1.25D, 3.8D));
        profiles.add(new LootProfile("haste", 2.5D + (rankBonus * 0.45D), 0.45D, 0.95D, 2.9D));
        return profiles;
    }

    private ItemStack buildItem(FractureRegionSnapshot fracture, LootRoll roll) {
        ItemStack item = customItemService.create(roll.itemId(), quantityFor(roll));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName(roll)));
        meta.lore(buildLore(fracture, roll));
        meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, roll.rarity().name());
        meta.getPersistentDataContainer().set(fractureStateKey, PersistentDataType.STRING, fracture.state().name());
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> buildLore(FractureRegionSnapshot fracture, LootRoll roll) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Rarity: " + NameFormat.friendly(roll.rarity().name())));
        lore.add(Component.text("Source: " + NameFormat.friendly(fracture.state().name()) + " fracture"));
        lore.add(Component.text("Quality Score: " + String.format(Locale.US, "%.2f", roll.qualityScore())));
        roll.rolledStats().forEach((key, value) -> lore.add(Component.text(NameFormat.friendly(key) + ": " + String.format(Locale.US, "%.2f", value))));
        return List.copyOf(lore);
    }

    private int quantityFor(LootRoll roll) {
        return switch (roll.rarity()) {
            case COMMON -> 1;
            case UNCOMMON, RARE -> 2;
            case EPIC, LEGENDARY -> 3;
            case GOD_TIER -> 4;
        };
    }

    private String rewardItemId(FractureState state) {
        return switch (state) {
            case UNSTABLE -> "fracture_shard";
            case CORRUPTED -> "tainted_essence";
            case OVERRUN -> "stabilized_relic";
            case BOSS_BREACH -> "breach_heart";
            case PURIFIED -> "fracture_shard";
        };
    }

    private String displayName(LootRoll roll) {
        return NameFormat.friendly(roll.rarity().name()) + " " + NameFormat.friendly(roll.itemId());
    }

    private void registerCustomItems() {
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "fracture_shard",
            Material.AMETHYST_SHARD,
            "Fracture Shard",
            List.of("Raw instability condensed from minor fractures."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "tainted_essence",
            Material.ECHO_SHARD,
            "Tainted Essence",
            List.of("Volatile essence harvested from corrupted fracture lanes."),
            null,
            true,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "stabilized_relic",
            Material.NETHERITE_SCRAP,
            "Stabilized Relic",
            List.of("A hardened remnant worthy of high-end Vortex crafting."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "breach_heart",
            Material.NETHER_STAR,
            "Breach Heart",
            List.of("The signature core pulled from a mythic breach finale."),
            null,
            true,
            null,
            Map.of()
        ));
    }

    public record FractureRewardDrop(LootRoll roll, ItemStack item) {
    }
}
