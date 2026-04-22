package io.vortexcore.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourceMigrationService {

    private static final String VERSION_KEY = "resource-version";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withLocale(Locale.US)
        .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final List<String> managedResources;
    private final Map<String, ResourceStatus> statuses;

    public ResourceMigrationService(JavaPlugin plugin, List<String> managedResources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedResources = List.copyOf(managedResources);
        this.statuses = new LinkedHashMap<>();
    }

    public MigrationReport migrateAll() {
        LinkedHashMap<String, ResourceStatus> results = new LinkedHashMap<>();
        for (String resourceName : managedResources) {
            ResourceStatus status = migrateResource(resourceName);
            results.put(resourceName, status);
            statuses.put(resourceName, status);
        }
        return new MigrationReport(Map.copyOf(results));
    }

    public ResourceStatus status(String resourceName) {
        return statuses.get(resourceName);
    }

    public List<String> statusLines() {
        return statuses.values().stream().map(ResourceStatus::describeLine).toList();
    }

    private ResourceStatus migrateResource(String resourceName) {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path targetPath = dataFolder.resolve(resourceName);

        try {
            Files.createDirectories(dataFolder);
            YamlConfiguration defaults = loadPackagedYaml(resourceName);
            int targetVersion = Math.max(1, defaults.getInt(VERSION_KEY, 1));

            if (Files.notExists(targetPath)) {
                copyPackagedResource(resourceName, targetPath);
                return new ResourceStatus(
                    resourceName,
                    targetVersion,
                    targetVersion,
                    true,
                    false,
                    null,
                    List.of("Created default " + resourceName + ".")
                );
            }

            YamlConfiguration existing = YamlConfiguration.loadConfiguration(targetPath.toFile());
            int currentVersion = Math.max(0, existing.getInt(VERSION_KEY, 0));
            if (currentVersion > targetVersion) {
                return new ResourceStatus(
                    resourceName,
                    currentVersion,
                    targetVersion,
                    false,
                    false,
                    null,
                    List.of(resourceName + " is newer than this plugin build.")
                );
            }

            MergeSummary mergeSummary = mergeMissing(existing, defaults);
            boolean versionUpgrade = currentVersion < targetVersion;
            if (!versionUpgrade && mergeSummary.addedKeys() == 0) {
                return new ResourceStatus(resourceName, currentVersion, targetVersion, false, false, null, List.of("No migration required."));
            }

            Path backupPath = createBackup(resourceName, targetPath, currentVersion, targetVersion);
            existing.set(VERSION_KEY, targetVersion);
            existing.save(targetPath.toFile());

            List<String> notes = new ArrayList<>();
            if (versionUpgrade) {
                notes.add("Upgraded " + resourceName + " from v" + currentVersion + " to v" + targetVersion + ".");
            }
            if (mergeSummary.addedKeys() > 0) {
                notes.add("Merged " + mergeSummary.addedKeys() + " missing key(s).");
            }

            return new ResourceStatus(resourceName, currentVersion, targetVersion, false, true, backupPath, List.copyOf(notes));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to migrate resource " + resourceName + ": " + exception.getMessage(), exception);
        }
    }

    private YamlConfiguration loadPackagedYaml(String resourceName) throws IOException {
        InputStream resourceStream = plugin.getResource(resourceName);
        if (resourceStream == null) {
            throw new IOException("Missing packaged resource " + resourceName);
        }

        try (InputStream stream = resourceStream; Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private void copyPackagedResource(String resourceName, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                throw new IOException("Missing packaged resource " + resourceName);
            }
            Files.copy(stream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path createBackup(String resourceName, Path targetPath, int currentVersion, int targetVersion) throws IOException {
        Path backupDirectory = plugin.getDataFolder().toPath().resolve("backups").resolve("migrations");
        Files.createDirectories(backupDirectory);

        String fileName = resourceName.replace('/', '_').replace('\\', '_');
        String stampedName = fileName
            + ".v"
            + currentVersion
            + "-to-v"
            + targetVersion
            + "."
            + BACKUP_TIMESTAMP.format(Instant.now())
            + ".bak";
        Path backupPath = backupDirectory.resolve(stampedName);
        Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        return backupPath;
    }

    private MergeSummary mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        int addedKeys = 0;
        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);
            if (defaultValue instanceof ConfigurationSection defaultSection) {
                ConfigurationSection existingSection = target.getConfigurationSection(key);
                if (existingSection == null) {
                    existingSection = target.createSection(key);
                    addedKeys++;
                }
                addedKeys += mergeMissing(existingSection, defaultSection).addedKeys();
                continue;
            }

            if (!target.contains(key)) {
                target.set(key, defaultValue);
                addedKeys++;
            }
        }
        return new MergeSummary(addedKeys);
    }

    public record MigrationReport(Map<String, ResourceStatus> statuses) {

        public List<String> describeLines() {
            return statuses.values().stream().map(ResourceStatus::describeLine).toList();
        }
    }

    public record ResourceStatus(
        String resourceName,
        int previousVersion,
        int targetVersion,
        boolean created,
        boolean migrated,
        Path backupPath,
        List<String> notes
    ) {

        public String describeLine() {
            String state = created ? "created" : migrated ? "migrated" : "ready";
            String suffix = backupPath == null ? "" : " | backup " + backupPath.getFileName();
            return "Resource " + resourceName + " -> v" + previousVersion + " to v" + targetVersion + " | " + state + suffix;
        }
    }

    private record MergeSummary(int addedKeys) {
    }
}
