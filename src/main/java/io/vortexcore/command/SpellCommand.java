package io.vortexcore.command;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellForgeService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class SpellCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_CAST = "vortex.player.cast";
    private static final String PERM_EQUIP = "vortex.player.skills.equip";
    private static final String PERM_FORGE = "vortex.admin.spells";

    private final PlayerProfileService playerProfileService;
    private final SpellbookService spellbookService;
    private final SpellForgeService spellForgeService;
    private final MessageService messageService;

    public SpellCommand(
        PlayerProfileService playerProfileService,
        SpellbookService spellbookService,
        SpellForgeService spellForgeService,
        MessageService messageService
    ) {
        this.playerProfileService = playerProfileService;
        this.spellbookService = spellbookService;
        this.spellForgeService = spellForgeService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use spell commands.");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (args.length == 0 || args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("menu")) {
            if (deny(player, PERM_CAST)) {
                return true;
            }
            spellbookService.openSpellMenu(player, profile);
            return true;
        }

        if (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("help")) {
            if (deny(player, PERM_CAST)) {
                return true;
            }
            sendOverview(player, profile);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "list", "spells" -> {
                if (deny(player, PERM_CAST)) {
                    return true;
                }
                sendSpellList(player, profile);
                return true;
            }
            case "equip" -> {
                if (deny(player, PERM_EQUIP)) {
                    return true;
                }
                if (args.length < 2) {
                    messageService.send(player, "Usage: /" + label + " equip <spell>");
                    return true;
                }
                try {
                    spellbookService.setActiveSpell(profile, args[1]);
                    messageService.send(player, "<green>Active spell set to " + spellbookService.spellLabel(args[1]) + "</green>");
                } catch (IllegalArgumentException exception) {
                    messageService.send(player, "<red>" + exception.getMessage() + "</red>");
                }
                return true;
            }
            case "cast" -> {
                if (deny(player, PERM_CAST)) {
                    return true;
                }
                String spellId = args.length >= 2 ? args[1] : null;
                spellbookService.cast(player, profile, spellId).exceptionally(error -> null);
                return true;
            }
            case "forge", "gui", "editor" -> {
                if (deny(player, PERM_FORGE)) {
                    return true;
                }
                spellForgeService.openBrowser(player);
                return true;
            }
            default -> {
                if (deny(player, PERM_CAST)) {
                    return true;
                }
                spellbookService.cast(player, profile, args[0]).exceptionally(error -> null);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (args.length == 1) {
            ArrayList<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_CAST)) {
                suggestions.add("open");
                suggestions.add("menu");
                suggestions.add("info");
                suggestions.add("list");
                suggestions.add("cast");
                suggestions.add("equip");
                suggestions.add("help");
                suggestions.addAll(spellbookService.availableSpells(profile));
            }
            if (sender.hasPermission(PERM_FORGE)) {
                suggestions.add("forge");
                suggestions.add("gui");
                suggestions.add("editor");
            }
            return suggestions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("cast") || args[0].equalsIgnoreCase("equip"))) {
            return new ArrayList<>(spellbookService.availableSpells(profile));
        }
        return List.of();
    }

    private void sendOverview(Player player, UnifiedPlayer profile) {
        Optional<String> activeSpellId = spellbookService.activeSpellId(profile);
        String activeLine = activeSpellId
            .map(id -> "<green>" + spellbookService.spellLabel(id) + "</green> <dark_gray>(" + id + ")</dark_gray>")
            .orElse("<gray>None</gray>");
        messageService.send(player, "<gradient:#8B5CF6:#3B82F6><bold>Spellbook</bold></gradient>");
        messageService.send(player, "<white>Active spell:</white> " + activeLine);
        sendSpellList(player, profile);
        messageService.send(player, "<gray>/spell cast</gray> <dark_gray>casts your active spell</dark_gray>");
        messageService.send(player, "<gray>/spell <spellId></gray> <dark_gray>casts a specific spell</dark_gray>");
        messageService.send(player, "<gray>/spell equip <spellId></gray> <dark_gray>sets your active spell</dark_gray>");
        if (player.hasPermission(PERM_FORGE)) {
            messageService.send(player, "<gray>/spell forge</gray> <dark_gray>opens the spell forge GUI</dark_gray>");
        }
    }

    private void sendSpellList(Player player, UnifiedPlayer profile) {
        Optional<String> activeSpellId = spellbookService.activeSpellId(profile);
        String joined = spellbookService.availableSpells(profile).stream()
            .map(spellId -> spellId.equals(activeSpellId.orElse(null))
                ? "<green>* " + spellbookService.spellLabel(spellId) + "</green>"
                : "<gray>" + spellbookService.spellLabel(spellId) + "</gray>")
            .reduce((left, right) -> left + "<dark_gray>, </dark_gray>" + right)
            .orElse("<gray>None</gray>");
        messageService.send(player, "<white>Unlocked spells:</white> " + joined);
    }

    private boolean deny(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        messageService.send(player, "<red>You do not have permission: " + permission + "</red>");
        return true;
    }
}