package io.vortexcore.support;

import io.vortexcore.config.ResourceMigrationService;
import io.vortexcore.diagnostics.DiagnosticsTracker;
import io.vortexcore.integration.citizens.CitizensIntegrationService;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.persistence.PlayerDataRepository;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

@SuppressWarnings("deprecation")
public final class SupportDumpService {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withLocale(Locale.US)
        .withZone(ZoneOffset.UTC);

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final ResourceMigrationService resourceMigrationService;
    private final PlayerDataRepository playerDataRepository;
    private final DiagnosticsTracker diagnosticsTracker;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final ProtectionHookService protectionHookService;
    private final CitizensIntegrationService citizensIntegrationService;
    private final String dumpDirectoryName;
    private final Set<String> redactedPaths;

    public SupportDumpService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        ResourceMigrationService resourceMigrationService,
        PlayerDataRepository playerDataRepository,
        DiagnosticsTracker diagnosticsTracker,
        VaultEconomyBridge vaultEconomyBridge,
        ProtectionHookService protectionHookService,
        CitizensIntegrationService citizensIntegrationService,
        String dumpDirectoryName,
        List<String> redactedPaths
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.resourceMigrationService = resourceMigrationService;
        this.playerDataRepository = playerDataRepository;
        this.diagnosticsTracker = diagnosticsTracker;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.protectionHookService = protectionHookService;
        this.citizensIntegrationService = citizensIntegrationService;
        this.dumpDirectoryName = dumpDirectoryName == null || dumpDirectoryName.isBlank() ? "support" : dumpDirectoryName;
        LinkedHashSet<String> sensitive = new LinkedHashSet<>();
        if (redactedPaths != null) {
            redactedPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.toLowerCase(Locale.ROOT))
                .forEach(sensitive::add);
        }
        if (sensitive.isEmpty()) {
            sensitive.add("npc.api-key");
        }
        this.redactedPaths = Set.copyOf(sensitive);
    }

    public CompletableFuture<Path> writeDump(String requestedBy, List<String> validationLines) {
        List<String> dumpLines = buildDumpLines(requestedBy, validationLines);
        CompletableFuture<Path> future = new CompletableFuture<>();
        Path directory = plugin.getDataFolder().toPath().resolve(dumpDirectoryName);
        Path target = directory.resolve("vortex-support-" + FILE_TIMESTAMP.format(Instant.now()) + ".txt");

        scheduler.runAsync("support-dump-write", () -> {
            try {
                Files.createDirectories(directory);
                Files.writeString(target, String.join(System.lineSeparator(), dumpLines), StandardCharsets.UTF_8);
                future.complete(target);
            } catch (IOException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private List<String> buildDumpLines(String requestedBy, List<String> validationLines) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("VortexRPG Support Dump");
        lines.add("Generated UTC: " + Instant.now());
        lines.add("Requested by: " + (requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy));
        lines.add("");
        lines.add("[Runtime]");
        lines.add("Plugin: " + plugin.getDescription().getName() + " " + plugin.getDescription().getVersion());
        lines.add("Server: " + plugin.getServer().getName() + " | " + plugin.getServer().getVersion());
        lines.add("Bukkit: " + plugin.getServer().getBukkitVersion());
        lines.add("Java: " + System.getProperty("java.version"));
        lines.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        lines.add("Online players: " + plugin.getServer().getOnlinePlayers().size());
        lines.add("Loaded worlds: " + plugin.getServer().getWorlds().size());
        lines.add("Loaded plugins: " + plugin.getServer().getPluginManager().getPlugins().length);
        lines.add("");
        lines.add("[Integrations]");
        lines.add(vaultEconomyBridge == null ? "Vault -> unavailable" : vaultEconomyBridge.statusSnapshot().describeLine());
        lines.add(protectionHookService == null ? "Protection -> unavailable" : protectionHookService.statusSnapshot().describeLine());
        lines.add(citizensIntegrationService == null ? "Citizens -> not active" : citizensIntegrationService.statusSnapshot().describeLine());
        lines.add("PlaceholderAPI -> installed: " + (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null));
        lines.add("");
        lines.add("[Resources]");
        if (resourceMigrationService == null || resourceMigrationService.statusLines().isEmpty()) {
            lines.add("Resource migration data unavailable.");
        } else {
            lines.addAll(resourceMigrationService.statusLines());
        }
        lines.add("");
        lines.add("[Diagnostics]");
        if (diagnosticsTracker == null) {
            lines.add("Diagnostics tracker unavailable.");
        } else {
            lines.addAll(diagnosticsTracker.snapshot().describeLines(5));
        }
        lines.add("");
        lines.add("[Persistence]");
        if (playerDataRepository == null) {
            lines.add("External persistence unavailable. Local mode or startup fallback.");
        } else {
            lines.addAll(playerDataRepository.statusSnapshot().describeLines());
        }
        lines.add("");
        lines.add("[Validation]");
        lines.addAll(validationLines == null ? List.of("Validation snapshot unavailable.") : validationLines);
        lines.add("");
        lines.add("[Sanitized Config]");
        flattenConfig(plugin.getConfig(), "", lines);
        return List.copyOf(lines);
    }

    private void flattenConfig(ConfigurationSection section, String prefix, List<String> output) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                flattenConfig(child, path, output);
                continue;
            }
            output.add(path + " = " + sanitize(path, section.get(key)));
        }
    }

    private String sanitize(String path, Object value) {
        if (value == null) {
            return "null";
        }
        if (redactedPaths.contains(path.toLowerCase(Locale.ROOT))) {
            return "<redacted>";
        }
        return value.toString();
    }
}
