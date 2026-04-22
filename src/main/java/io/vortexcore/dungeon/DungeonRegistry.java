package io.vortexcore.dungeon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

public final class DungeonRegistry {

    private final Plugin plugin;
    private Map<String, DungeonDefinition> dungeons;

    public DungeonRegistry(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.dungeons = Map.of();
    }

    public ReloadResult reload() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "dungeons.yml");
        if (!file.exists()) {
            plugin.saveResource("dungeons.yml", false);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("dungeons");
        if (root == null) {
            dungeons = Map.of();
            return new ReloadResult(0, 0, List.of("No 'dungeons' section found in dungeons.yml."));
        }

        Map<String, DungeonDefinition> parsed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int skipped = 0;

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                skipped++;
                notes.add("Skipped dungeon '" + id + "': invalid section.");
                continue;
            }

            try {
                DungeonDefinition definition = parseDungeon(id, section);
                parsed.put(id, definition);
            } catch (Exception exception) {
                skipped++;
                notes.add("Skipped dungeon '" + id + "': " + exception.getMessage());
            }
        }

        this.dungeons = Map.copyOf(parsed);
        return new ReloadResult(parsed.size(), skipped, List.copyOf(notes));
    }

    public Optional<DungeonDefinition> get(String id) {
        return Optional.ofNullable(dungeons.get(id));
    }

    public Map<String, DungeonDefinition> all() {
        return dungeons;
    }

    private DungeonDefinition parseDungeon(String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
        String templateName = section.getString("template-name", id);
        int minLevel = section.getInt("min-level", 1);
        int maxPartySize = section.getInt("max-party-size", 4);
        long timeLimitSeconds = section.getLong("time-limit-seconds", 600);
        String bossId = section.getString("boss-id", "");
        List<String> rewardItemIds = section.getStringList("reward-items");
        double completionReward = section.getDouble("completion-reward", 500.0);

        List<DungeonDefinition.Wave> waves = new ArrayList<>();
        ConfigurationSection wavesSection = section.getConfigurationSection("waves");
        if (wavesSection != null) {
            for (String waveKey : wavesSection.getKeys(false)) {
                ConfigurationSection waveSection = wavesSection.getConfigurationSection(waveKey);
                if (waveSection == null) continue;

                int waveNumber = waves.size() + 1;
                EntityType mobType;
                try {
                    mobType = EntityType.valueOf(waveSection.getString("mob-type", "ZOMBIE").toUpperCase());
                } catch (IllegalArgumentException exception) {
                    mobType = EntityType.ZOMBIE;
                }
                int mobCount = waveSection.getInt("mob-count", 5);
                double mobHealth = waveSection.getDouble("mob-health", 40.0);
                double mobDamageMultiplier = waveSection.getDouble("mob-damage-multiplier", 1.0);
                long spawnDelayTicks = waveSection.getLong("spawn-delay-ticks", 40L);

                waves.add(new DungeonDefinition.Wave(waveNumber, mobType, mobCount, mobHealth, mobDamageMultiplier, spawnDelayTicks));
            }
        }

        if (waves.isEmpty()) {
            waves.add(new DungeonDefinition.Wave(1, EntityType.ZOMBIE, 5, 40.0, 1.0, 40L));
        }

        return new DungeonDefinition(
            id, displayName, templateName, minLevel, maxPartySize, timeLimitSeconds,
            Collections.unmodifiableList(waves), bossId, List.copyOf(rewardItemIds), completionReward
        );
    }

    public record ReloadResult(int loaded, int skipped, List<String> notes) {
    }
}
