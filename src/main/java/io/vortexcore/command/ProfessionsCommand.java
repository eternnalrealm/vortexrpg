package io.vortexcore.command;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.profession.ProfessionType;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ProfessionsCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.professions.open";
    private static final String PERM_STATS = "vortex.player.professions.stats";
    private static final String PERM_STATION = "vortex.player.professions.station";

    private final PlayerProfileService playerProfileService;
    private final ProfessionService professionService;
    private final MessageService messageService;

    public ProfessionsCommand(PlayerProfileService playerProfileService, ProfessionService professionService, MessageService messageService) {
        this.playerProfileService = playerProfileService;
        this.professionService = professionService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use profession commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            professionService.openLedger(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            if (deny(player, PERM_STATS)) {
                return true;
            }
            UnifiedPlayer profile = playerProfileService.profile(player);
            messageService.send(player, professionService.summaryLine(profile));
            professionService.detailLines(profile).forEach(line -> messageService.send(player, line));
            return true;
        }

        if (args[0].equalsIgnoreCase("station")) {
            if (deny(player, PERM_STATION)) {
                return true;
            }
            if (args.length < 2) {
                messageService.send(player, "Usage: /" + label + " station <smithing|fletching|armorsmithing|engineering|alchemy|cooking>");
                return true;
            }

            ProfessionType profession = ProfessionType.parse(args[1]);
            if (profession == null || !profession.craftingProfession()) {
                messageService.send(player, "Choose smithing, fletching, armorsmithing, engineering, alchemy, or cooking.");
                return true;
            }

            professionService.openStationMenu(player, profession);
            return true;
        }

        if (args[0].equalsIgnoreCase("companion") || args[0].equalsIgnoreCase("beast")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            professionService.openCompanionHud(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("engineering") || args[0].equalsIgnoreCase("gadgets")) {
            if (deny(player, PERM_STATION)) {
                return true;
            }
            professionService.openEngineeringConsole(player);
            return true;
        }

        messageService.send(player, 
            "Usage: /" + label + " [open|stats|companion|engineering|station <smithing|fletching|armorsmithing|engineering|alchemy|cooking>]"
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("open");
            }
            if (sender.hasPermission(PERM_STATS)) {
                suggestions.add("stats");
            }
            if (sender.hasPermission(PERM_STATION)) {
                suggestions.add("station");
                suggestions.add("engineering");
                suggestions.add("gadgets");
            }
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("companion");
                suggestions.add("beast");
            }
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("station") && sender.hasPermission(PERM_STATION)) {
            return professionService.stationNames();
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
