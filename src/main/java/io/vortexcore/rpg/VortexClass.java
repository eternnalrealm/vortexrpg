package io.vortexcore.rpg;

import io.vortexcore.progression.VortexAttribute;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

public enum VortexClass {
    KNIGHT(
        "Knight",
        "Front-line bruiser built around strength and vitality.",
        Material.NETHERITE_SWORD,
        "fortify",
        stats(6.0D, 6.0D, 1.0D, 3.0D, 1.0D, 3.0D)
    ),
    MAGE(
        "Mage",
        "Arcane glass-cannon with explosive intelligence and wisdom scaling.",
        Material.BLAZE_ROD,
        "arcane_bolt",
        stats(1.0D, 2.0D, 6.0D, 2.0D, 7.0D, 5.0D)
    ),
    RANGER(
        "Ranger",
        "Mobile striker with dexterity pressure and reliable sustained damage.",
        Material.BOW,
        "hunter_mark",
        stats(4.0D, 4.0D, 3.0D, 7.0D, 2.0D, 2.0D)
    ),
    CLERIC(
        "Cleric",
        "Support archetype with powerful spirit scaling and strong sustain.",
        Material.GOLDEN_APPLE,
        "mend",
        stats(2.0D, 4.0D, 6.0D, 2.0D, 3.0D, 7.0D)
    ),
    BEASTMASTER(
        "Beastmaster",
        "Wild fighter who bonds with beasts for savage frontline pressure.",
        Material.LEAD,
        "beast_call",
        stats(4.0D, 5.0D, 2.0D, 3.0D, 1.0D, 6.0D)
    ),
    ENGINEER(
        "Engineer",
        "Gadget specialist who deploys devices and controls the battlefield.",
        Material.REDSTONE_TORCH,
        "deploy_turret",
        stats(2.0D, 3.0D, 5.0D, 5.0D, 6.0D, 1.0D)
    );

    private final String displayName;
    private final String description;
    private final Material icon;
    private final String starterSpellId;
    private final Map<VortexAttribute, Double> baseStats;

    VortexClass(String displayName, String description, Material icon, String starterSpellId, Map<VortexAttribute, Double> baseStats) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.starterSpellId = starterSpellId;
        this.baseStats = Map.copyOf(baseStats);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    public String starterSpellId() {
        return starterSpellId;
    }

    public Map<VortexAttribute, Double> baseStats() {
        return baseStats;
    }

    public static VortexClass parse(String input) {
        for (VortexClass value : values()) {
            if (value.name().equalsIgnoreCase(input) || value.displayName.equalsIgnoreCase(input)) {
                return value;
            }
        }
        return null;
    }

    public String commandId() {
        return name().toLowerCase(Locale.ROOT);
    }

    private static Map<VortexAttribute, Double> stats(
        double strength,
        double vitality,
        double wisdom,
        double dexterity,
        double intelligence,
        double spirit
    ) {
        EnumMap<VortexAttribute, Double> values = new EnumMap<>(VortexAttribute.class);
        values.put(VortexAttribute.STRENGTH, strength);
        values.put(VortexAttribute.VITALITY, vitality);
        values.put(VortexAttribute.WISDOM, wisdom);
        values.put(VortexAttribute.DEXTERITY, dexterity);
        values.put(VortexAttribute.INTELLIGENCE, intelligence);
        values.put(VortexAttribute.SPIRIT, spirit);
        return values;
    }
}
