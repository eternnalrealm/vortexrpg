package io.vortexcore.command;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class SkillsCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.skills.open";
    private static final String PERM_POINTS = "vortex.player.skills.points";
    private static final String PERM_UNLOCK = "vortex.player.skills.unlock";
    private static final String PERM_SPELLS = "vortex.player.skills.spells";
    private static final String PERM_EQUIP = "vortex.player.skills.equip";

    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final SpellbookService spellbookService;
    private final MessageService messageService;

    public SkillsCommand(PlayerProfileService playerProfileService, ClassSkillService classSkillService, SpellbookService spellbookService, MessageService messageService) {
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.spellbookService = spellbookService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use skill commands.");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            classSkillService.openSkillMenu(player, profile);
            return true;
        }

        if (args[0].equalsIgnoreCase("points")) {
            if (deny(player, PERM_POINTS)) {
                return true;
            }
            messageService.send(player, "Unspent skill points: " + classSkillService.unspentSkillPoints(profile));
            return true;
        }

        if (args[0].equalsIgnoreCase("unlock") && args.length >= 2) {
            if (deny(player, PERM_UNLOCK)) {
                return true;
            }
            classSkillService.unlockSkill(player, profile, args[1])
                .thenAccept(result -> messageService.send(player, result.message()));
            return true;
        }

        if (args[0].equalsIgnoreCase("spells")) {
            if (deny(player, PERM_SPELLS)) {
                return true;
            }
            String joined = spellbookService.availableSpells(profile).stream().map(spellbookService::spellLabel).reduce((left, right) -> left + ", " + right).orElse("None");
            messageService.send(player, "Unlocked spells: " + joined);
            return true;
        }

        if (args[0].equalsIgnoreCase("equip") && args.length >= 2) {
            if (deny(player, PERM_EQUIP)) {
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

        messageService.send(player, "Usage: /" + label + " [open|points|unlock <skill>|spells|equip <spell>]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("open");
            }
            if (sender.hasPermission(PERM_POINTS)) {
                suggestions.add("points");
            }
            if (sender.hasPermission(PERM_UNLOCK)) {
                suggestions.add("unlock");
            }
            if (sender.hasPermission(PERM_SPELLS)) {
                suggestions.add("spells");
            }
            if (sender.hasPermission(PERM_EQUIP)) {
                suggestions.add("equip");
            }
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("unlock") && sender.hasPermission(PERM_UNLOCK)) {
            List<String> suggestions = new ArrayList<>();
            classSkillService.skillsFor(profile).forEach(skill -> suggestions.add(skill.id()));
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("equip") && sender.hasPermission(PERM_EQUIP)) {
            return new ArrayList<>(spellbookService.availableSpells(profile));
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
