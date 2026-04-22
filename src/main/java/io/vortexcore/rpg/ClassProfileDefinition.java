package io.vortexcore.rpg;

import io.vortexcore.progression.VortexAttribute;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

public record ClassProfileDefinition(
    String id,
    VortexClass archetype,
    String displayName,
    String description,
    Material icon,
    String starterSpellId,
    Map<VortexAttribute, Double> baseStats,
    boolean builtIn
) {

    public ClassProfileDefinition {
        id = id == null ? null : id.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null || displayName.isBlank() ? "Unnamed Class" : displayName;
        description = description == null || description.isBlank() ? "No class description." : description;
        icon = icon == null ? Material.BARRIER : icon;
        starterSpellId = starterSpellId == null ? null : starterSpellId.trim().toLowerCase(Locale.ROOT);
        EnumMap<VortexAttribute, Double> stats = new EnumMap<>(VortexAttribute.class);
        if (baseStats != null) {
            stats.putAll(baseStats);
        }
        for (VortexAttribute attribute : VortexAttribute.values()) {
            stats.putIfAbsent(attribute, 0.0D);
        }
        baseStats = Map.copyOf(stats);
    }

    public String commandId() {
        return id;
    }

    public static ClassProfileDefinition fromArchetype(VortexClass archetype) {
        return new ClassProfileDefinition(
            archetype.commandId(),
            archetype,
            archetype.displayName(),
            archetype.description(),
            archetype.icon(),
            archetype.starterSpellId(),
            archetype.baseStats(),
            true
        );
    }
}