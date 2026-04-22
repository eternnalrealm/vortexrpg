package io.vortexcore.command;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.ClassForgeService;
import io.vortexcore.rpg.ClassProfileDefinition;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ClassCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.class.open";
    private static final String PERM_INFO = "vortex.player.class.info";
    private static final String PERM_CHOOSE = "vortex.player.class.choose";
    private static final String PERM_SPECS = "vortex.player.class.specs";
    private static final String PERM_ASCEND = "vortex.player.class.ascend";
    private static final String PERM_SWAP = "vortex.player.class.swap";
    private static final String PERM_KIT = "vortex.player.class.kit";
    private static final String PERM_ADMIN_FORGE = "vortex.admin.classes";
    private static final String PERM_ADMIN_SET = "vortex.admin.class.set";
    private static final String PERM_ADMIN_KIT = "vortex.admin.class.kit";

    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final ClassForgeService classForgeService;
    private final MessageService messageService;

    public ClassCommand(
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        ClassForgeService classForgeService,
        MessageService messageService
    ) {
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.classForgeService = classForgeService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return handleAdminCommand(sender, label, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use class commands.");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            classSkillService.openClassMenu(player, profile);
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            if (deny(player, PERM_INFO)) {
                return true;
            }
            messageService.send(player, classSkillService.summaryLine(profile));
            classSkillService.ascensionStatusLines(profile).forEach(line -> messageService.send(player, line));
            return true;
        }

        if (args[0].equalsIgnoreCase("specs")) {
            if (deny(player, PERM_SPECS)) {
                return true;
            }
            classSkillService.openAscensionMenu(player, profile);
            return true;
        }

        if (args[0].equalsIgnoreCase("choose") && args.length >= 2) {
            if (deny(player, PERM_CHOOSE)) {
                return true;
            }
            ClassProfileDefinition classProfile = classSkillService.classProfile(args[1]).orElse(null);
            if (classProfile == null) {
                messageService.send(player, "Unknown class profile. Use /" + label + " open to browse available classes.");
                return true;
            }

            classSkillService.chooseClass(player, profile, classProfile.id())
                .thenRun(() -> messageService.send(player, "<green>Class selected: " + classProfile.displayName() + "</green>"))
                .exceptionally(error -> {
                    messageService.send(player, "<red>" + friendlyError(error) + "</red>");
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("ascend") && args.length >= 2) {
            if (deny(player, PERM_ASCEND)) {
                return true;
            }
            classSkillService.ascend(player, profile, args[1])
                .thenAccept(result -> messageService.send(player, result.message()))
                .exceptionally(error -> {
                    messageService.send(player, "<red>" + friendlyError(error) + "</red>");
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("swap")) {
            if (deny(player, PERM_SWAP)) {
                return true;
            }
            if (!classSkillService.hasClass(profile)) {
                messageService.send(player, "<yellow>You don't have a class yet. Use <white>/" + label + " open</white> to pick one.</yellow>");
                return true;
            }
            classSkillService.openClassSwapMenu(player, profile);
            return true;
        }

        if (args[0].equalsIgnoreCase("kit")) {
            if (deny(player, PERM_KIT)) {
                return true;
            }
            if (!classSkillService.hasClass(profile)) {
                messageService.send(player,
                    "<yellow>You don't have a class yet. Choose one with <white>/" + label + " open</white> first.</yellow>");
                return true;
            }
            classSkillService.grantClassKit(player, profile)
                .ifPresentOrElse(
                    ignored -> {},
                    () -> messageService.send(player, "<red>No class is set — pick one first.</red>")
                );
            return true;
        }

        messageService.send(player, "Usage: /" + label + " [open|info|specs|choose <class>|ascend <spec>|swap|kit]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            ArrayList<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("open");
            }
            if (sender.hasPermission(PERM_INFO)) {
                suggestions.add("info");
            }
            if (sender.hasPermission(PERM_CHOOSE)) {
                suggestions.add("choose");
            }
            if (sender.hasPermission(PERM_SPECS)) {
                suggestions.add("specs");
            }
            if (sender.hasPermission(PERM_ASCEND)) {
                suggestions.add("ascend");
            }
            if (sender.hasPermission(PERM_SWAP)) {
                suggestions.add("swap");
            }
            if (sender.hasPermission(PERM_KIT)) {
                suggestions.add("kit");
            }
            if (sender.hasPermission(PERM_ADMIN_SET) || sender.hasPermission(PERM_ADMIN_KIT) || sender.hasPermission(PERM_ADMIN_FORGE)) {
                suggestions.add("admin");
            }
            return suggestions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("choose") && sender.hasPermission(PERM_CHOOSE)) {
            return filter(new ArrayList<>(classSkillService.registeredClassIds()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ascend") && sender instanceof Player player && sender.hasPermission(PERM_ASCEND)) {
            UnifiedPlayer profile = playerProfileService.profile(player);
            return classSkillService.ascensionsFor(profile).stream().map(ClassSkillService.ClassAscension::id).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")
                && (sender.hasPermission(PERM_ADMIN_SET) || sender.hasPermission(PERM_ADMIN_KIT) || sender.hasPermission(PERM_ADMIN_FORGE))) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(PERM_ADMIN_SET)) options.add("set");
            if (sender.hasPermission(PERM_ADMIN_KIT)) options.add("kit");
            if (sender.hasPermission(PERM_ADMIN_FORGE)) options.add("forge");
            return filter(options, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("kit")
                && sender.hasPermission(PERM_ADMIN_KIT)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("kit")
                && sender.hasPermission(PERM_ADMIN_KIT)) {
            return filter(new ArrayList<>(classSkillService.registeredClassIds()), args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("set") && sender.hasPermission(PERM_ADMIN_SET)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("set") && sender.hasPermission(PERM_ADMIN_SET)) {
            return filter(new ArrayList<>(classSkillService.registeredClassIds()), args[3]);
        }
        return List.of();
    }

    private boolean handleAdminCommand(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("forge")) {
            if (!sender.hasPermission(PERM_ADMIN_FORGE)) {
                messageService.send(sender, "<red>You do not have permission: " + PERM_ADMIN_FORGE + "</red>");
                return true;
            }
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "<red>Only players can open the class forge.</red>");
                return true;
            }
            classForgeService.openBrowser(player);
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("kit")) {
            if (!sender.hasPermission(PERM_ADMIN_KIT)) {
                messageService.send(sender, "<red>You do not have permission: " + PERM_ADMIN_KIT + "</red>");
                return true;
            }
            if (args.length < 4) {
                messageService.send(sender, "<red>Usage: /" + label + " admin kit <player> <class></red>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                messageService.send(sender, "<red>Player not found.</red>");
                return true;
            }
            ClassProfileDefinition classProfile = classSkillService.classProfile(args[3]).orElse(null);
            if (classProfile == null) {
                messageService.send(sender, "<red>Unknown class profile.</red>");
                return true;
            }
            classSkillService.grantClassKitTo(target, classProfile.archetype());
            messageService.send(sender, "<green>Issued " + classProfile.displayName() + " kit to " + target.getName() + ".</green>");
            return true;
        }

        if (!sender.hasPermission(PERM_ADMIN_SET)) {
            messageService.send(sender, "<red>You do not have permission: " + PERM_ADMIN_SET + "</red>");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("set")) {
            messageService.send(sender, "<red>Usage: /" + label + " admin [forge|set <player> <class>|kit <player> <class>]</red>");
            return true;
        }
        if (args.length < 4) {
            messageService.send(sender, "<red>Usage: /" + label + " admin set <player> <class></red>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            messageService.send(sender, "<red>Player not found.</red>");
            return true;
        }

        ClassProfileDefinition classProfile = classSkillService.classProfile(args[3]).orElse(null);
        if (classProfile == null) {
            messageService.send(sender, "<red>Unknown class profile.</red>");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(target);
        classSkillService.forceSetClass(target, profile, classProfile.id())
            .thenRun(() -> {
                messageService.send(sender, "<green>Set " + target.getName() + " to class " + classProfile.displayName() + ".</green>");
                if (!sender.getName().equalsIgnoreCase(target.getName())) {
                    messageService.send(target, "<yellow>Your class was changed by an administrator to " + classProfile.displayName() + ".</yellow>");
                }
            })
            .exceptionally(error -> {
                messageService.send(sender, "<red>" + friendlyError(error) + "</red>");
                return null;
            });
        return true;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private boolean deny(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        messageService.send(player, "<red>You do not have permission: " + permission + "</red>");
        return true;
    }

    private String friendlyError(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? "That class action could not be completed." : message;
    }
}
