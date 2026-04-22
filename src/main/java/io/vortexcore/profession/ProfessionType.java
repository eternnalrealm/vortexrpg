package io.vortexcore.profession;

import java.util.Locale;
import org.bukkit.Material;

public enum ProfessionType {

    SWORDSMANSHIP("swordsmanship", "Swordsmanship", Material.NETHERITE_SWORD, Category.COMBAT, null),
    AXEMANSHIP("axemanship", "Axemanship", Material.NETHERITE_AXE, Category.COMBAT, null),
    ARCHERY("archery", "Archery", Material.BOW, Category.COMBAT, null),
    SPELLCRAFT("spellcraft", "Spellcraft", Material.BLAZE_ROD, Category.COMBAT, null),
    DEFENSE("defense", "Defense", Material.SHIELD, Category.COMBAT, null),
    DUAL_WIELD("dual_wield", "Dual Wield", Material.IRON_SWORD, Category.COMBAT, null),
    BEAST_MASTERY("beast_mastery", "Beast Mastery", Material.LEAD, Category.COMBAT, null),
    MINING("mining", "Mining", Material.IRON_PICKAXE, Category.GATHERING, null),
    FORAGING("foraging", "Woodcutting", Material.DIAMOND_AXE, Category.GATHERING, null),
    FARMING("farming", "Farming", Material.GOLDEN_HOE, Category.GATHERING, null),
    FISHING("fishing", "Fishing", Material.FISHING_ROD, Category.GATHERING, null),
    SMITHING("smithing", "Weaponsmithing", Material.NETHERITE_SWORD, Category.CRAFTING, Material.SMITHING_TABLE),
    FLETCHING("fletching", "Fletching", Material.BOW, Category.CRAFTING, Material.FLETCHING_TABLE),
    ARMORSMITHING("armorsmithing", "Armorsmithing", Material.NETHERITE_CHESTPLATE, Category.CRAFTING, Material.ANVIL),
    ENGINEERING("engineering", "Engineering", Material.REDSTONE, Category.CRAFTING, Material.CRAFTING_TABLE),
    ALCHEMY("alchemy", "Alchemy", Material.BREWING_STAND, Category.CRAFTING, Material.BREWING_STAND),
    COOKING("cooking", "Cooking", Material.SMOKER, Category.CRAFTING, Material.SMOKER);

    private final String id;
    private final String displayName;
    private final Material icon;
    private final Category category;
    private final Material stationMaterial;

    ProfessionType(String id, String displayName, Material icon, Category category, Material stationMaterial) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.category = category;
        this.stationMaterial = stationMaterial;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public Category category() {
        return category;
    }

    public Material stationMaterial() {
        return stationMaterial;
    }

    public boolean craftingProfession() {
        return category == Category.CRAFTING;
    }

    public static ProfessionType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ProfessionType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }

        return switch (normalized) {
            case "sword", "blade", "swords" -> SWORDSMANSHIP;
            case "axe", "axes" -> AXEMANSHIP;
            case "archery", "archer" -> ARCHERY;
            case "magic", "staff", "spell", "spellcraft" -> SPELLCRAFT;
            case "tank", "guard", "shield" -> DEFENSE;
            case "dagger", "daggers", "dual", "dualwield", "dual_wield" -> DUAL_WIELD;
            case "pet", "pets", "beast", "beastmaster", "beast_mastery" -> BEAST_MASTERY;
            case "smith", "forge" -> SMITHING;
            case "weapon", "weapons", "weaponsmith", "weaponsmithing" -> SMITHING;
            case "fletcher", "bow", "bows", "bowcraft" -> FLETCHING;
            case "armor", "armour", "armorer", "armorsmith", "armoursmith", "plate" -> ARMORSMITHING;
            case "engineering", "engineer", "gadget", "gadgets", "machines" -> ENGINEERING;
            case "brew", "brewing" -> ALCHEMY;
            case "wood", "woodcutting", "lumber", "logging" -> FORAGING;
            case "farm", "farming", "crops" -> FARMING;
            case "cook", "kitchen" -> COOKING;
            default -> null;
        };
    }

    public enum Category {
        COMBAT,
        GATHERING,
        CRAFTING
    }
}
