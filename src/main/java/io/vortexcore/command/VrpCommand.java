package io.vortexcore.command;

import io.vortexcore.ui.MessageService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * /vrp — Unified VortexRPG command hub.
 * Routes every sub-command to its dedicated handler so players and admins
 * only ever need one root label. All old commands remain registered as
 * aliases for backward compatibility.
 */
public final class VrpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
        "class", "skills", "stats", "cast", "quest", "talent",
        "professions", "adventure", "fracture", "expeditions",
        "boss", "dungeon", "auction", "party", "admin"
    );

    private final MessageService messageService;
    private final CommandExecutor classCmd;
    private final CommandExecutor skillsCmd;
    private final CommandExecutor statsCmd;
    private final CommandExecutor castCmd;
    private final CommandExecutor questCmd;
    private final CommandExecutor talentCmd;
    private final CommandExecutor professionsCmd;
    private final CommandExecutor adventureCmd;
    private final CommandExecutor fractureCmd;
    private final CommandExecutor expeditionsCmd;
    private final CommandExecutor bossCmd;
    private final CommandExecutor dungeonCmd;
    private final CommandExecutor auctionCmd;
    private final CommandExecutor partyCmd;
    private final CommandExecutor adminCmd;

    public VrpCommand(
        MessageService messageService,
        CommandExecutor classCmd,
        CommandExecutor skillsCmd,
        CommandExecutor statsCmd,
        CommandExecutor castCmd,
        CommandExecutor questCmd,
        CommandExecutor talentCmd,
        CommandExecutor professionsCmd,
        CommandExecutor adventureCmd,
        CommandExecutor fractureCmd,
        CommandExecutor expeditionsCmd,
        CommandExecutor bossCmd,
        CommandExecutor dungeonCmd,
        CommandExecutor auctionCmd,
        CommandExecutor partyCmd,
        CommandExecutor adminCmd
    ) {
        this.messageService = messageService;
        this.classCmd = classCmd;
        this.skillsCmd = skillsCmd;
        this.statsCmd = statsCmd;
        this.castCmd = castCmd;
        this.questCmd = questCmd;
        this.talentCmd = talentCmd;
        this.professionsCmd = professionsCmd;
        this.adventureCmd = adventureCmd;
        this.fractureCmd = fractureCmd;
        this.expeditionsCmd = expeditionsCmd;
        this.bossCmd = bossCmd;
        this.dungeonCmd = dungeonCmd;
        this.auctionCmd = auctionCmd;
        this.partyCmd = partyCmd;
        this.adminCmd = adminCmd;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        CommandExecutor executor = resolve(sub);
        if (executor == null) {
            messageService.send(sender, "<red>Unknown sub-command. Type <white>/vrp</white> for the full list.</red>");
            return true;
        }
        return executor.onCommand(sender, command, "vrp " + sub, subArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length >= 2) {
            CommandExecutor executor = resolve(args[0].toLowerCase(Locale.ROOT));
            if (executor instanceof TabCompleter completer) {
                return completer.onTabComplete(sender, command, "vrp " + args[0], Arrays.copyOfRange(args, 1, args.length));
            }
        }
        return List.of();
    }

    private CommandExecutor resolve(String sub) {
        return switch (sub) {
            case "class"       -> classCmd;
            case "skills"      -> skillsCmd;
            case "stats"       -> statsCmd;
            case "cast"        -> castCmd;
            case "quest"       -> questCmd;
            case "talent"      -> talentCmd;
            case "professions" -> professionsCmd;
            case "adventure"   -> adventureCmd;
            case "fracture"    -> fractureCmd;
            case "expeditions" -> expeditionsCmd;
            case "boss"        -> bossCmd;
            case "dungeon"     -> dungeonCmd;
            case "auction"     -> auctionCmd;
            case "party"       -> partyCmd;
            case "admin"       -> adminCmd;
            default            -> null;
        };
    }

    private void sendHelp(CommandSender sender) {
        messageService.send(sender, "<gradient:#8B5CF6:#3B82F6><bold>VortexRPG</bold></gradient> <dark_gray>\u2014</dark_gray> <gray>Unified Command Hub</gray>");
        messageService.send(sender, "  <gold>/vrp class</gold>        <gray>Class selection, ascension and swap</gray>");
        messageService.send(sender, "  <gold>/vrp skills</gold>       <gray>Skill atlas and spell management</gray>");
        messageService.send(sender, "  <gold>/vrp stats</gold>        <gray>Character sheet and progression</gray>");
        messageService.send(sender, "  <gold>/vrp cast</gold>         <gray>Cast your equipped spell</gray>");
        messageService.send(sender, "  <gold>/vrp quest</gold>        <gray>Quest board and tracking</gray>");
        messageService.send(sender, "  <gold>/vrp talent</gold>       <gray>Talent tree and passive upgrades</gray>");
        messageService.send(sender, "  <gold>/vrp professions</gold>  <gray>Profession ledger and crafting</gray>");
        messageService.send(sender, "  <gold>/vrp adventure</gold>    <gray>Adventure board and season track</gray>");
        messageService.send(sender, "  <gold>/vrp expeditions</gold>  <gray>Expedition contracts and breach parties</gray>");
        messageService.send(sender, "  <gold>/vrp fracture</gold>     <gray>Fracture rift tracking and gateways</gray>");
        messageService.send(sender, "  <gold>/vrp boss</gold>         <gray>World boss encounters</gray>");
        messageService.send(sender, "  <gold>/vrp dungeon</gold>      <gray>Instanced dungeons</gray>");
        messageService.send(sender, "  <gold>/vrp auction</gold>      <gray>Auction house</gray>");
        messageService.send(sender, "  <gold>/vrp party</gold>        <gray>Party management</gray>");
        if (sender.hasPermission("vortex.admin")) {
            messageService.send(sender, "  <gold>/vrp admin</gold>        <red>Admin toolkit</red>");
        }
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
