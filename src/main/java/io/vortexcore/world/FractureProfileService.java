package io.vortexcore.world;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

public final class FractureProfileService {

    private final Logger logger;
    private final File profilesFile;
    private final Map<String, FractureProfile> profiles;
    private volatile FractureProfile fallbackProfile;

    public FractureProfileService(Plugin plugin, Logger logger) {
        this.logger = logger;
        this.profilesFile = new File(plugin.getDataFolder(), "fractures.yml");
        this.profiles = new LinkedHashMap<>();
        this.fallbackProfile = FractureProfile.fallback();
    }

    public ReloadResult reload() {
        profiles.clear();
        fallbackProfile = FractureProfile.fallback();

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(profilesFile);
        ConfigurationSection profilesSection = configuration.getConfigurationSection("profiles");
        if (profilesSection == null) {
            profiles.put(fallbackProfile.id(), fallbackProfile);
            return new ReloadResult(1, 0, List.of("No profiles section found. Loaded fallback fracture profile."));
        }

        int loaded = 0;
        int skipped = 0;
        ArrayList<String> messages = new ArrayList<>();
        for (String rawId : profilesSection.getKeys(false)) {
            ConfigurationSection section = profilesSection.getConfigurationSection(rawId);
            if (section == null) {
                skipped++;
                messages.add(rawId + ": not a configuration section.");
                continue;
            }
            try {
                FractureProfile profile = parseProfile(rawId, section);
                profiles.put(profile.id(), profile);
                loaded++;
            } catch (IllegalArgumentException exception) {
                skipped++;
                messages.add(rawId + ": " + exception.getMessage());
                logger.warning("Unable to load fracture profile " + rawId + ": " + exception.getMessage());
            }
        }

        if (profiles.isEmpty()) {
            profiles.put(fallbackProfile.id(), fallbackProfile);
            messages.add("All configured fracture profiles failed. Loaded fallback profile.");
            loaded = 1;
        } else {
            fallbackProfile = profiles.values().iterator().next();
        }
        return new ReloadResult(loaded, skipped, List.copyOf(messages));
    }

    public Optional<FractureProfile> profile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profiles.get(normalize(id)));
    }

    public FractureProfile resolve(String id) {
        return profile(id).orElse(fallbackProfile);
    }

    public FractureProfile defaultProfile() {
        return fallbackProfile;
    }

    public List<String> profileIds() {
        return List.copyOf(profiles.keySet());
    }

    public List<String> describeLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Fracture profiles: " + profiles.size());
        profiles.values().forEach(profile -> lines.add(
            profile.id()
                + " -> mobs=" + (profile.mobTypes().isEmpty() ? "any" : profile.mobTypes().size())
                + ", boss=" + profile.bossType().name().toLowerCase(Locale.ROOT)
                + ", mobEffects=" + profile.mobEffects().size()
                + ", bossEffects=" + profile.bossEffects().size()
        ));
        return List.copyOf(lines);
    }

    private FractureProfile parseProfile(String rawId, ConfigurationSection section) {
        String id = normalize(rawId);
        if (id.isBlank()) {
            throw new IllegalArgumentException("profile id cannot be blank");
        }

        LinkedHashMap<String, EntityType> mobTypes = new LinkedHashMap<>();
        for (String rawMob : section.getStringList("mob-types")) {
            EntityType entityType = parseEntityType(rawMob, true);
            mobTypes.put(entityType.name(), entityType);
        }

        EntityType bossType = parseEntityType(section.getString("boss-type", "wither_skeleton"), true);
        return new FractureProfile(
            id,
            section.getString("display-name", friendlyName(id)),
            Set.copyOf(mobTypes.values()),
            bossType,
            clamp(section.getDouble("mob-health-multiplier", 1.0D), 0.25D, 8.0D),
            clamp(section.getDouble("mob-damage-multiplier", 1.0D), 0.25D, 8.0D),
            Math.max(0.0D, section.getDouble("mob-armor-bonus", 0.0D)),
            clamp(section.getDouble("boss-health-multiplier", 1.0D), 0.25D, 12.0D),
            clamp(section.getDouble("boss-damage-multiplier", 1.0D), 0.25D, 12.0D),
            normalizeIds(section.getStringList("mob-effects")),
            normalizeIds(section.getStringList("boss-effects"))
        );
    }

    private EntityType parseEntityType(String raw, boolean requireMonster) {
        try {
            EntityType entityType = EntityType.valueOf(normalize(raw).toUpperCase(Locale.ROOT));
            if (!entityType.isAlive() || !entityType.isSpawnable()) {
                throw new IllegalArgumentException("entity type " + raw + " is not spawnable");
            }
            if (requireMonster && entityType.getEntityClass() != null
                && org.bukkit.entity.Monster.class.isAssignableFrom(entityType.getEntityClass()) == false) {
                throw new IllegalArgumentException("entity type " + raw + " is not a monster");
            }
            return entityType;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown entity type " + raw);
        }
    }

    private List<String> normalizeIds(List<String> raw) {
        return raw.stream().map(this::normalize).filter(token -> !token.isBlank()).distinct().toList();
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private String friendlyName(String raw) {
        String[] parts = normalize(raw).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ReloadResult(int loaded, int skipped, List<String> messages) {
    }
}
