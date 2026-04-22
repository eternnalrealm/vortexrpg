package io.vortexcore.command;

import io.vortexcore.adventure.SeasonTrackService;
import io.vortexcore.cosmetic.PrestigeCosmeticService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.profession.ProfessionService;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.ProfileMenuService;
import io.vortexcore.support.NameFormat;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class StatsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "vortex.player.stats";
    private static final String PERM_ADMIN_XP = "vortex.admin.stats.xp";

    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final ProfessionService professionService;
    private final SeasonTrackService seasonTrackService;
    private final PrestigeCosmeticService prestigeCosmeticService;
    private final ProfileMenuService profileMenuService;
    private final MessageService messageService;

    public StatsCommand(
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        ProfessionService professionService,
        SeasonTrackService seasonTrackService,
        PrestigeCosmeticService prestigeCosmeticService,
        ProfileMenuService profileMenuService,
        MessageService messageService
    ) {
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.professionService = professionService;
        this.seasonTrackService = seasonTrackService;
        this.prestigeCosmeticService = prestigeCosmeticService;
        this.profileMenuService = profileMenuService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return handleAdminCommand(sender, label, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can inspect RPG stats.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messageService.send(player, "<red>You do not have permission: " + PERMISSION + "</red>");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            profileMenuService.open(player);
            return true;
        }
        if (!args[0].equalsIgnoreCase("text")) {
            messageService.send(player, "Usage: /" + label + " [open|text]");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        messageService.send(player, classSkillService.summaryLine(profile));
        messageService.send(player, "Experience: " + profile.experience() + " | To Next Level: " + playerProfileService.experienceToNextLevel(profile));
        classSkillService.ascensionStatusLines(profile).forEach(line -> messageService.send(player, line));
        messageService.send(player,
            "Title: " + playerProfileService.activeTitle(profile).map(NameFormat::friendly).orElse("None")
                + " | Breach Tokens: " + playerProfileService.breachTokenBalance(profile)
        );
        messageService.send(player, prestigeCosmeticService.cosmeticSummary(profile));
        messageService.send(player, professionService.summaryLine(profile));
        professionService.masteryLines(profile).forEach(line -> messageService.send(player, line));
        messageService.send(player,
            "Season: " + seasonTrackService.currentSeasonId()
                + " | Renown: " + seasonTrackService.renown(profile)
                + " | Level: " + seasonTrackService.currentLevel(profile)
                + " | To Next: " + seasonTrackService.renownToNextLevel(profile)
        );
        for (VortexAttribute attribute : VortexAttribute.values()) {
            double total = profile.stat(attribute).current();
            messageService.send(player, NameFormat.friendly(attribute.name()) + ": " + String.format(Locale.US, "%.1f", total));
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission(PERM_ADMIN_XP)) {
                return filter(List.of("open", "text", "admin"), args[0]);
            }
            return filter(List.of("open", "text"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(PERM_ADMIN_XP)) {
            return filter(List.of("xp"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("xp") && sender.hasPermission(PERM_ADMIN_XP)) {
            return filter(List.of("add", "remove", "set"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("xp") && sender.hasPermission(PERM_ADMIN_XP)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[3]);
        }
        return java.util.List.of();
    }

    private boolean handleAdminCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_XP)) {
            messageService.send(sender, "<red>You do not have permission: " + PERM_ADMIN_XP + "</red>");
            return true;
        }
        if (args.length < 5 || !args[1].equalsIgnoreCase("xp")) {
            messageService.send(sender, "<red>Usage: /" + label + " admin xp <add|remove|set> <player> <amount></red>");
            return true;
        }

        String mode = args[2].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayer(args[3]);
        if (target == null) {
            messageService.send(sender, "<red>Player not found.</red>");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[4]);
        } catch (NumberFormatException ignored) {
            messageService.send(sender, "<red>Invalid amount.</red>");
            return true;
        }

        try {
            PlayerProfileService.ExperienceAdjustmentResult result = switch (mode) {
                case "add" -> playerProfileService.addExperience(target, amount, "an administrator");
                case "remove" -> playerProfileService.removeExperience(target, amount, "an administrator");
                case "set" -> playerProfileService.setExperience(target, amount, "an administrator");
                default -> null;
            };

            if (result == null) {
                messageService.send(sender, "<red>Usage: /" + label + " admin xp <add|remove|set> <player> <amount></red>");
                return true;
            }

            messageService.send(sender,
                "<green>Updated " + target.getName() + " XP:</green> <gray>" + result.previousExperience() + " -> " + result.newExperience()
                    + " | Level " + result.previousLevel() + " -> " + result.newLevel() + "</gray>"
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            messageService.send(sender, "<red>" + exception.getMessage() + "</red>");
        }
        return true;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

}
