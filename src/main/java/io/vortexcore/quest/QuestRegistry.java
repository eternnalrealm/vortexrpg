package io.vortexcore.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class QuestRegistry {

    private final Plugin plugin;
    private final Logger logger;
    private volatile Map<String, QuestDefinition> definitions;

    public QuestRegistry(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.definitions = Map.of();
    }

    public ReloadResult reload() {
        Map<String, QuestDefinition> loaded = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        java.io.File questFile = new java.io.File(plugin.getDataFolder(), "quests.yml");
        if (!questFile.exists()) {
            plugin.saveResource("quests.yml", false);
        }

        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(questFile);
        } catch (Exception exception) {
            notes.add("Failed to load quests.yml: " + exception.getMessage());
            this.definitions = Map.of();
            return new ReloadResult(0, notes);
        }

        ConfigurationSection questsSection = yaml.getConfigurationSection("quests");
        if (questsSection == null) {
            notes.add("No 'quests' section found in quests.yml.");
            this.definitions = Map.of();
            return new ReloadResult(0, notes);
        }

        for (String questId : questsSection.getKeys(false)) {
            ConfigurationSection section = questsSection.getConfigurationSection(questId);
            if (section == null) {
                notes.add("Skipping invalid quest entry: " + questId);
                continue;
            }

            try {
                QuestDefinition definition = parseQuest(questId, section);
                loaded.put(questId.toLowerCase(Locale.ROOT), definition);
            } catch (Exception exception) {
                logger.warning("Failed to parse quest '" + questId + "': " + exception.getMessage());
                notes.add("Failed to parse quest '" + questId + "': " + exception.getMessage());
            }
        }

        this.definitions = Collections.unmodifiableMap(loaded);
        return new ReloadResult(loaded.size(), notes);
    }

    public Optional<QuestDefinition> find(String questId) {
        return Optional.ofNullable(definitions.get(questId.toLowerCase(Locale.ROOT)));
    }

    public Map<String, QuestDefinition> all() {
        return definitions;
    }

    public List<QuestDefinition> availableFor(int playerLevel, java.util.Set<String> completedQuests) {
        return definitions.values().stream()
            .filter(quest -> !quest.hasLevelRequirement() || playerLevel >= quest.requiredLevel())
            .filter(quest -> !quest.hasPrerequisite() || completedQuests.contains(quest.requiredQuest().toLowerCase(Locale.ROOT)))
            .toList();
    }

    private QuestDefinition parseQuest(String questId, ConfigurationSection section) {
        String name = section.getString("name", questId);
        String description = section.getString("description", "");
        int requiredLevel = section.getInt("required-level", 0);
        String requiredQuest = section.getString("required-quest", null);
        String npcId = section.getString("npc", null);
        long cooldownSeconds = section.getLong("cooldown-seconds", 0L);
        boolean repeatable = section.getBoolean("repeatable", false);
        String chainNext = section.getString("chain-next", null);

        List<QuestObjectiveDefinition> objectives = new ArrayList<>();
        ConfigurationSection objectivesSection = section.getConfigurationSection("objectives");
        if (objectivesSection != null) {
            for (String objId : objectivesSection.getKeys(false)) {
                ConfigurationSection objSection = objectivesSection.getConfigurationSection(objId);
                if (objSection == null) continue;
                objectives.add(new QuestObjectiveDefinition(
                    objId,
                    QuestObjectiveType.valueOf(objSection.getString("type", "KILL_MOB").toUpperCase(Locale.ROOT)),
                    objSection.getString("target", ""),
                    objSection.getInt("amount", 1),
                    objSection.getString("description", "")
                ));
            }
        }

        List<QuestRewardDefinition> rewards = new ArrayList<>();
        if (section.isList("rewards")) {
            for (Object rewardObj : section.getList("rewards", List.of())) {
                if (rewardObj instanceof Map<?, ?> rewardMap) {
                    Object typeObj = rewardMap.get("type");
                    Object valueObj = rewardMap.get("value");
                    Object amountObj = rewardMap.get("amount");
                    String typeStr = (typeObj != null ? String.valueOf(typeObj) : "EXPERIENCE").toUpperCase(Locale.ROOT);
                    String valueStr = valueObj != null ? String.valueOf(valueObj) : "";
                    double amountVal = amountObj instanceof Number num ? num.doubleValue() : 0.0D;
                    rewards.add(new QuestRewardDefinition(
                        QuestRewardType.valueOf(typeStr),
                        valueStr,
                        amountVal
                    ));
                }
            }
        }

        return new QuestDefinition(
            questId.toLowerCase(Locale.ROOT),
            name,
            description,
            requiredLevel,
            requiredQuest,
            npcId,
            cooldownSeconds,
            repeatable,
            List.copyOf(objectives),
            List.copyOf(rewards),
            chainNext
        );
    }

    public record ReloadResult(int loaded, List<String> notes) {
    }
}
