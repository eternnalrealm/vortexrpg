package io.vortexcore.command;

import io.vortexcore.adventure.AdventureBoardService;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class AdventureCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.adventure.open";
    private static final String PERM_STATUS = "vortex.player.adventure.status";
    private static final String PERM_SEASON = "vortex.player.adventure.season";

    private final AdventureBoardService adventureBoardService;
    private final MessageService messageService;

    public AdventureCommand(AdventureBoardService adventureBoardService, MessageService messageService) {
        this.adventureBoardService = adventureBoardService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use adventure board commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            adventureBoardService.openBoard(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (deny(player, PERM_STATUS)) {
                return true;
            }
            adventureBoardService.statusLines(player).forEach(line -> messageService.send(player, line));
            return true;
        }

        if (args[0].equalsIgnoreCase("season")) {
            if (deny(player, PERM_SEASON)) {
                return true;
            }
            adventureBoardService.openSeasonTrack(player);
            return true;
        }

        messageService.send(player, "Usage: /" + label + " [open|status|season]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("open");
            }
            if (sender.hasPermission(PERM_STATUS)) {
                suggestions.add("status");
            }
            if (sender.hasPermission(PERM_SEASON)) {
                suggestions.add("season");
            }
            return suggestions;
        }
        return List.of();
    }

    private boolean deny(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        messageService.send(player, "<red>You do not have permission: " + permission + "</red>");
        return true;
    }
}
