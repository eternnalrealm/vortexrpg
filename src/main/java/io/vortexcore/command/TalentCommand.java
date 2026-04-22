package io.vortexcore.command;

import io.vortexcore.talent.TalentNodeDefinition;
import io.vortexcore.talent.TalentService;
import io.vortexcore.talent.TalentTreeDefinition;
import io.vortexcore.talent.TalentTreeMenu;
import io.vortexcore.ui.MessageService;
import io.vortexcore.progression.VortexAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class TalentCommand implements CommandExecutor, TabCompleter {

    private final TalentService talentService;
    private final TalentTreeMenu talentTreeMenu;
    private final MessageService messageService;

    public TalentCommand(TalentService talentService, TalentTreeMenu talentTreeMenu, MessageService messageService) {
        this.talentService = talentService;
        this.talentTreeMenu = talentTreeMenu;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (!player.hasPermission("vortex.player.talent.open")) {
                messageService.send(player, "<red>You do not have permission.</red>");
                return true;
            }
            talentTreeMenu.open(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "points" -> {
                if (!player.hasPermission("vortex.player.talent.points")) {
                    messageService.send(player, "<red>You do not have permission.</red>");
                    return true;
                }
                talentService.talentData(player.getUniqueId()).thenAccept(data ->
                    messageService.send(player,
                        "<gold>Talent Points: <bold>" + talentService.talentPointsAvailable(data) + "</bold></gold>"
                    )
                );
            }
            case "info" -> {
                if (!player.hasPermission("vortex.player.talent.info")) {
                    messageService.send(player, "<red>You do not have permission.</red>");
                    return true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /talent info <nodeId></red>");
                    return true;
                }
                String nodeId = args[1].toLowerCase(Locale.ROOT);
                TalentNodeDefinition found = null;
                for (TalentTreeDefinition tree : talentService.registry().all().values()) {
                    Optional<TalentNodeDefinition> opt = tree.find(nodeId);
                    if (opt.isPresent()) {
                        found = opt.get();
                        break;
                    }
                }
                if (found == null) {
                    messageService.send(player, "<red>Unknown talent node: " + nodeId + "</red>");
                    return true;
                }
                messageService.send(player, "<gold><bold>" + found.displayName() + "</bold></gold>");
                messageService.send(player, "<gray>" + found.description() + "</gray>");
                messageService.send(player, "<yellow>Cost: " + found.cost() + " point(s)</yellow>");
                for (Map.Entry<VortexAttribute, Double> entry : found.statBonuses().entrySet()) {
                    messageService.send(player,
                        "<green>+" + formatDouble(entry.getValue()) + " " + formatAttribute(entry.getKey()) + "</green>"
                    );
                }
                if (found.hasGrantedAbility()) {
                    messageService.send(player, "<aqua>Ability: " + found.grantedAbility() + "</aqua>");
                }
                if (found.hasPrerequisites()) {
                    messageService.send(player,
                        "<gray>Requires: " + String.join(", ", found.prerequisites()) + "</gray>"
                    );
                }
            }
            case "reset" -> {
                if (!player.hasPermission("vortex.player.talent.reset")) {
                    messageService.send(player, "<red>You do not have permission.</red>");
                    return true;
                }
                talentService.resetTree(player).thenAccept(success -> {
                    if (!success) {
                        messageService.send(player, "<red>Nothing to reset or transaction failed.</red>");
                    }
                });
            }
            case "admin" -> {
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /talent admin [grant|reload]</red>");
                    return true;
                }
                String adminSub = args[1].toLowerCase(Locale.ROOT);
                switch (adminSub) {
                    case "grant" -> {
                        if (!player.hasPermission("vortex.admin.talent.grant")) {
                            messageService.send(player, "<red>You do not have permission.</red>");
                            return true;
                        }
                        if (args.length < 4) {
                            messageService.send(player, "<red>Usage: /talent admin grant <player> <amount></red>");
                            return true;
                        }
                        Player target = Bukkit.getPlayer(args[2]);
                        if (target == null) {
                            messageService.send(player, "<red>Player not found.</red>");
                            return true;
                        }
                        int amount;
                        try {
                            amount = Integer.parseInt(args[3]);
                        } catch (NumberFormatException ignored) {
                            messageService.send(player, "<red>Invalid amount.</red>");
                            return true;
                        }
                        if (amount <= 0) {
                            messageService.send(player, "<red>Amount must be positive.</red>");
                            return true;
                        }
                        talentService.grantTalentPoints(target, amount);
                        messageService.send(player,
                            "<green>Granted " + amount + " talent point(s) to " + target.getName() + ".</green>"
                        );
                    }
                    case "reload" -> {
                        if (!player.hasPermission("vortex.admin.talent.reload")) {
                            messageService.send(player, "<red>You do not have permission.</red>");
                            return true;
                        }
                        var result = talentService.registry().reload();
                        messageService.send(player,
                            "<green>Talent registry reloaded. Loaded " + result.loaded() + " tree(s).</green>"
                        );
                        result.notes().forEach(note ->
                            messageService.send(player, "<gray>" + note + "</gray>")
                        );
                    }
                    default -> messageService.send(player, "<red>Unknown admin subcommand.</red>");
                }
            }
            default -> messageService.send(player,
                "<red>Usage: /talent [open|points|info|reset|admin]</red>"
            );
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "points", "info", "reset", "admin"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            List<String> nodeIds = new ArrayList<>();
            talentService.registry().all().values().forEach(tree ->
                nodeIds.addAll(tree.nodes().keySet())
            );
            return filter(nodeIds, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("grant", "reload"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("grant")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private String formatAttribute(VortexAttribute attribute) {
        String raw = attribute.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private String formatDouble(double value) {
        return value == (long) value ? Long.toString((long) value) : String.format(Locale.US, "%.1f", value);
    }
}
