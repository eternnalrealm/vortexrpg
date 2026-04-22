package io.vortexcore.integration.protection;

import io.vortexcore.integration.protection.worldguard.WorldGuardProtectionHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class ProtectionHookService {

    public static final String BYPASS_PERMISSION = "vortex.bypass.region-checks";

    @SuppressWarnings("unused")
    private final Logger logger;
    private final boolean enabled;
    private final List<ProtectionHook> hooks;
    private final List<String> notes;

    public ProtectionHookService(
        Logger logger,
        JavaPlugin plugin,
        boolean enabled,
        boolean worldGuardEnabled
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
        this.hooks = new ArrayList<>();
        this.notes = new ArrayList<>();

        if (!enabled) {
            notes.add("Protection hooks disabled in config.");
            return;
        }

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (worldGuardEnabled) {
            if (pluginManager.getPlugin("WorldGuard") == null) {
                notes.add("WorldGuard not installed.");
            } else {
                try {
                    hooks.add(new WorldGuardProtectionHook());
                    notes.add("WorldGuard hooks active.");
                } catch (Throwable throwable) {
                    logger.warning("Unable to bootstrap WorldGuard protection hooks: " + throwable.getMessage());
                    notes.add("WorldGuard detected but failed to initialize.");
                }
            }
        } else {
            notes.add("WorldGuard hooks disabled in config.");
        }

        if (hooks.isEmpty()) {
            notes.add("No external region protection providers are active.");
        }
    }

    public ProtectionCheckResult check(Player player, Location location, ProtectionAction action) {
        if (!enabled || player == null || location == null || location.getWorld() == null) {
            return ProtectionCheckResult.pass();
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return ProtectionCheckResult.pass();
        }

        for (ProtectionHook hook : hooks) {
            ProtectionCheckResult result = hook.check(player, location, action);
            if (!result.allowed()) {
                return result;
            }
        }
        return ProtectionCheckResult.pass();
    }

    public boolean allows(Player player, Location location, ProtectionAction action) {
        return check(player, location, action).allowed();
    }

    public StatusSnapshot statusSnapshot() {
        return new StatusSnapshot(
            enabled,
            hooks.stream().map(ProtectionHook::providerName).toList(),
            List.copyOf(notes)
        );
    }

    public record StatusSnapshot(boolean enabled, List<String> providers, List<String> notes) {

        public String describeLine() {
            String providerSummary = providers.isEmpty() ? "none" : String.join(", ", providers);
            return "Protection -> enabled: " + enabled + " | providers: " + providerSummary;
        }

        public List<String> describeLines() {
            ArrayList<String> lines = new ArrayList<>();
            lines.add(describeLine());
            notes.stream()
                .filter(note -> note != null && !note.isBlank())
                .map(note -> "Protection -> " + note)
                .forEach(lines::add);
            return List.copyOf(lines);
        }
    }

    public static String denyMessage(ProtectionCheckResult result, ProtectionAction action) {
        if (result == null || result.allowed()) {
            return "";
        }
        String provider = result.provider() == null || result.provider().isBlank() ? "Protection" : result.provider();
        String reason = result.message() == null || result.message().isBlank()
            ? provider + " blocked " + action.displayName() + "."
            : result.message();
        return reason.substring(0, 1).toUpperCase(Locale.ROOT) + reason.substring(1);
    }
}
