package io.vortexcore.boss;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

public final class BossRegistry {

    private final Plugin plugin;
    private final Logger logger;
    private Map<String, BossDefinition> bosses;

    public BossRegistry(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.bosses = Map.of();
    }

    public ReloadResult reload() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "bosses.yml");
        if (!file.exists()) {
            plugin.saveResource("bosses.yml", false);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bosses");
        if (root == null) {
            bosses = Map.of();
            return new ReloadResult(0, 0, List.of("No 'bosses' section found in bosses.yml."));
        }

        Map<String, BossDefinition> parsed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int skipped = 0;

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                skipped++;
                notes.add("Skipped boss '" + id + "': invalid section.");
                continue;
            }

            try {
                BossDefinition definition = parseBoss(id, section);
                parsed.put(id, definition);
            } catch (Exception exception) {
                skipped++;
                notes.add("Skipped boss '" + id + "': " + exception.getMessage());
            }
        }

        this.bosses = Map.copyOf(parsed);
        return new ReloadResult(parsed.size(), skipped, List.copyOf(notes));
    }

    public Optional<BossDefinition> get(String id) {
        return Optional.ofNullable(bosses.get(id));
    }

    public Map<String, BossDefinition> all() {
        return bosses;
    }

    public void saveBoss(BossDefinition def) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "bosses.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = yaml.getConfigurationSection("bosses");
        if (root == null) {
            root = yaml.createSection("bosses");
        }

        ConfigurationSection section = root.createSection(def.id());
        section.set("display-name", def.displayName());
        section.set("entity-type", def.entityType().name());
        section.set("max-health", def.maxHealth());
        section.set("damage-multiplier", def.damageMultiplier());
        section.set("speed-multiplier", def.speedMultiplier());
        section.set("min-level", def.minLevel());
        section.set("spawn-cooldown-seconds", def.spawnCooldownSeconds());
        section.set("despawn-after-seconds", def.despawnAfterSeconds());
        section.set("summon-item", def.summonItem().name());
        section.set("arena-radius", def.arenaRadius());

        List<Map<String, Object>> phaseList = new ArrayList<>();
        for (BossDefinition.BossPhase phase : def.phases()) {
            Map<String, Object> phaseMap = new LinkedHashMap<>();
            phaseMap.put("threshold", phase.threshold());
            phaseMap.put("abilities", new ArrayList<>(phase.abilities()));
            phaseMap.put("speed-multiplier", phase.speedMultiplier());
            phaseMap.put("damage-multiplier", phase.damageMultiplier());
            phaseList.add(phaseMap);
        }
        section.set("phases", phaseList);

        List<Map<String, Object>> lootList = new ArrayList<>();
        for (BossDefinition.BossLootProfile lp : def.lootProfiles()) {
            Map<String, Object> lootMap = new LinkedHashMap<>();
            lootMap.put("item-id", lp.itemId());
            lootMap.put("base-value", lp.baseValue());
            lootMap.put("scale-per-level", lp.scalePerLevel());
            lootMap.put("spread", lp.spread());
            lootList.add(lootMap);
        }
        section.set("loot-profiles", lootList);

        try {
            yaml.save(file);
        } catch (java.io.IOException exception) {
            logger.warning("Failed to save boss '" + def.id() + "' to bosses.yml: " + exception.getMessage());
        }
    }

    public boolean deleteBoss(String id) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "bosses.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bosses");
        if (root == null || !root.contains(id)) {
            return false;
        }
        root.set(id, null);
        try {
            yaml.save(file);
            return true;
        } catch (java.io.IOException exception) {
            logger.warning("Failed to delete boss '" + id + "' from bosses.yml: " + exception.getMessage());
            return false;
        }
    }

    private BossDefinition parseBoss(String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
        EntityType entityType = parseEntityType(section.getString("entity-type", "ZOMBIE"));
        double maxHealth = section.getDouble("max-health", 200.0D);
        double damageMultiplier = section.getDouble("damage-multiplier", 1.0D);
        double speedMultiplier = section.getDouble("speed-multiplier", 1.0D);
        int minLevel = section.getInt("min-level", 1);
        long spawnCooldownSeconds = section.getLong("spawn-cooldown-seconds", 300L);
        long despawnAfterSeconds = section.getLong("despawn-after-seconds", 180L);
        Material summonItem = parseMaterial(section.getString("summon-item", "NETHER_STAR"));
        int arenaRadius = section.getInt("arena-radius", 30);

        List<BossDefinition.BossPhase> phases = new ArrayList<>();
        List<?> phaseList = section.getList("phases");
        if (phaseList != null) {
            for (Object entry : phaseList) {
                if (entry instanceof Map<?, ?> map) {
                    double threshold = toDouble(map.get("threshold"), 1.0D);
                    List<String> abilities = toStringList(map.get("abilities"));
                    double phaseSpeed = toDouble(map.get("speed-multiplier"), speedMultiplier);
                    double phaseDamage = toDouble(map.get("damage-multiplier"), damageMultiplier);
                    phases.add(new BossDefinition.BossPhase(threshold, List.copyOf(abilities), phaseSpeed, phaseDamage));
                }
            }
        }
        if (phases.isEmpty()) {
            phases.add(new BossDefinition.BossPhase(1.0D, List.of(), speedMultiplier, damageMultiplier));
        }
        phases.sort((a, b) -> Double.compare(b.threshold(), a.threshold()));

        List<BossDefinition.BossLootProfile> lootProfiles = new ArrayList<>();
        List<?> lootList = section.getList("loot-profiles");
        if (lootList != null) {
            for (Object entry : lootList) {
                if (entry instanceof Map<?, ?> map) {
                    Object rawItemId = map.get("item-id");
                    String itemId = rawItemId != null ? String.valueOf(rawItemId) : "unknown";
                    double baseValue = toDouble(map.get("base-value"), 10.0D);
                    double scalePerLevel = toDouble(map.get("scale-per-level"), 0.5D);
                    double spread = toDouble(map.get("spread"), 2.0D);
                    lootProfiles.add(new BossDefinition.BossLootProfile(itemId, baseValue, scalePerLevel, spread));
                }
            }
        }

        return new BossDefinition(
            id, displayName, entityType, maxHealth, damageMultiplier, speedMultiplier,
            minLevel, spawnCooldownSeconds, despawnAfterSeconds,
            Collections.unmodifiableList(phases),
            Collections.unmodifiableList(lootProfiles),
            summonItem, arenaRadius
        );
    }

    private EntityType parseEntityType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warning("Unknown entity type '" + name + "', defaulting to ZOMBIE.");
            return EntityType.ZOMBIE;
        }
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Material.NETHER_STAR;
        }
    }

    private double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    public record ReloadResult(int loaded, int skipped, List<String> notes) {
    }
}
