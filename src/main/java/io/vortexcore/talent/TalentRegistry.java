package io.vortexcore.talent;

import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.VortexClass;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class TalentRegistry {

    private final Plugin plugin;
    private final Logger logger;
    private volatile Map<VortexClass, TalentTreeDefinition> trees;

    public TalentRegistry(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.trees = Map.of();
    }

    public ReloadResult reload() {
        File file = new File(plugin.getDataFolder(), "talents.yml");
        if (!file.exists()) {
            plugin.saveResource("talents.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection talentsSection = config.getConfigurationSection("talents");
        if (talentsSection == null) {
            logger.warning("talents.yml is missing the 'talents' root section.");
            return new ReloadResult(0, List.of("Missing 'talents' root section in talents.yml"));
        }

        Map<VortexClass, TalentTreeDefinition> loaded = new EnumMap<>(VortexClass.class);
        List<String> notes = new ArrayList<>();

        for (String classKey : talentsSection.getKeys(false)) {
            VortexClass vortexClass = VortexClass.parse(classKey);
            if (vortexClass == null) {
                notes.add("Unknown class key '" + classKey + "' in talents.yml — skipped.");
                continue;
            }

            ConfigurationSection nodesSection = talentsSection.getConfigurationSection(classKey + ".nodes");
            if (nodesSection == null) {
                notes.add("Class '" + classKey + "' has no nodes section — skipped.");
                continue;
            }

            Map<String, TalentNodeDefinition> nodes = new LinkedHashMap<>();
            for (String nodeKey : nodesSection.getKeys(false)) {
                ConfigurationSection nodeSection = nodesSection.getConfigurationSection(nodeKey);
                if (nodeSection == null) {
                    continue;
                }

                try {
                    String displayName = nodeSection.getString("display-name", nodeKey);
                    String description = nodeSection.getString("description", "");
                    int row = nodeSection.getInt("row", 0);
                    int column = nodeSection.getInt("column", 0);
                    int cost = nodeSection.getInt("cost", 1);
                    List<String> prerequisites = nodeSection.getStringList("prerequisites");
                    String grantedAbility = nodeSection.getString("granted-ability");

                    Map<VortexAttribute, Double> statBonuses = new EnumMap<>(VortexAttribute.class);
                    ConfigurationSection bonusSection = nodeSection.getConfigurationSection("stat-bonuses");
                    if (bonusSection != null) {
                        for (String bonusKey : bonusSection.getKeys(false)) {
                            try {
                                VortexAttribute attribute = VortexAttribute.valueOf(bonusKey.toUpperCase(Locale.ROOT));
                                statBonuses.put(attribute, bonusSection.getDouble(bonusKey));
                            } catch (IllegalArgumentException ignored) {
                                notes.add("Unknown attribute '" + bonusKey + "' in node '" + nodeKey + "' of class '" + classKey + "'.");
                            }
                        }
                    }

                    nodes.put(nodeKey, new TalentNodeDefinition(
                        nodeKey, displayName, description,
                        Math.max(0, Math.min(4, row)),
                        Math.max(0, Math.min(8, column)),
                        prerequisites, cost, statBonuses, grantedAbility
                    ));
                } catch (Exception exception) {
                    logger.warning("Failed to parse talent node '" + nodeKey + "' for class '" + classKey + "': " + exception.getMessage());
                }
            }

            if (!nodes.isEmpty()) {
                loaded.put(vortexClass, new TalentTreeDefinition(vortexClass, nodes));
            }
        }

        this.trees = Collections.unmodifiableMap(loaded);
        return new ReloadResult(loaded.size(), notes);
    }

    public Optional<TalentTreeDefinition> find(VortexClass vortexClass) {
        return Optional.ofNullable(trees.get(vortexClass));
    }

    public Map<VortexClass, TalentTreeDefinition> all() {
        return trees;
    }

    public record ReloadResult(int loaded, List<String> notes) {
    }
}
