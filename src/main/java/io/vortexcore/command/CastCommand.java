package io.vortexcore.command;

import io.vortexcore.player.UnifiedPlayer;
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

public final class CastCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "vortex.player.cast";

    private final PlayerProfileService playerProfileService;
    private final SpellbookService spellbookService;
    private final MessageService messageService;

    public CastCommand(PlayerProfileService playerProfileService, SpellbookService spellbookService, MessageService messageService) {
        this.playerProfileService = playerProfileService;
        this.spellbookService = spellbookService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can cast Vortex spells.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messageService.send(player, "<red>You do not have permission: " + PERMISSION + "</red>");
            return true;
        }

        UnifiedPlayer profile = playerProfileService.profile(player);
        String spellId = args.length == 0 ? null : args[0];
        spellbookService.cast(player, profile, spellId).exceptionally(error -> null);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            return new ArrayList<>(spellbookService.availableSpells(playerProfileService.profile(player)));
        }
        return List.of();
    }
}
