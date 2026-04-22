package io.vortexcore.dungeon;

import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class DungeonCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PERM_OPEN = "vortex.player.dungeon.open";
    private static final String PERM_ENTER = "vortex.player.dungeon.enter";
    private static final String PERM_LEAVE = "vortex.player.dungeon.leave";
    private static final String PERM_LIST = "vortex.player.dungeon.list";
    private static final String PERM_INFO = "vortex.admin.dungeon.info";
    private static final String PERM_RELOAD = "vortex.admin.dungeon.reload";

    private final DungeonService dungeonService;
    private final VortexMenuBuilder menuBuilder;
    private final MessageService messageService;

    public DungeonCommand(DungeonService dungeonService, VortexMenuBuilder menuBuilder, MessageService messageService) {
        this.dungeonService = dungeonService;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "open";

        switch (sub) {
            case "open" -> {
                if (!player.hasPermission(PERM_OPEN)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                openBrowser(player);
            }
            case "list" -> {
                if (!player.hasPermission(PERM_LIST)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                listDungeons(player);
            }
            case "enter" -> {
                if (!player.hasPermission(PERM_ENTER)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /vdungeon enter <id></red>");
                    return true;
                }
                dungeonService.enterDungeon(player, args[1].toLowerCase(Locale.ROOT));
            }
            case "leave" -> {
                if (!player.hasPermission(PERM_LEAVE)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                dungeonService.leaveDungeon(player);
            }
            case "info" -> {
                if (!player.hasPermission(PERM_INFO)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                showInfo(player);
            }
            case "reload" -> {
                if (!player.hasPermission(PERM_RELOAD)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                DungeonRegistry.ReloadResult result = dungeonService.registry().reload();
                messageService.send(player, "<green>Dungeon registry reloaded. " + result.loaded() + " dungeon(s) loaded.</green>");
                result.notes().forEach(note -> messageService.send(player, "<gray>" + note + "</gray>"));
            }
            default -> messageService.send(player, "<red>Usage: /vdungeon [open|list|enter|leave|info|reload]</red>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "list", "enter", "leave", "info", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
            return filter(new ArrayList<>(dungeonService.registry().all().keySet()), args[1]);
        }
        return List.of();
    }

    private void openBrowser(Player player) {
        Map<String, DungeonDefinition> all = dungeonService.registry().all();
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Dungeon Browser"), 4);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int index = 0;

        for (Map.Entry<String, DungeonDefinition> entry : all.entrySet()) {
            if (index >= slots.length) break;
            DungeonDefinition def = entry.getValue();
            int slot = slots[index++];

            ItemStack icon = buildIcon(Material.MOSSY_STONE_BRICKS, def.displayName(), List.of(
                "ID: " + def.id(),
                "Level Req: " + def.minLevel(),
                "Party: up to " + def.maxPartySize(),
                "Time Limit: " + def.timeLimitSeconds() / 60 + " min",
                "Waves: " + def.waves().size(),
                def.bossId().isEmpty() ? "" : "Boss: " + def.bossId(),
                "",
                "Click to enter!"
            ));

            builder.button(slot, icon, context -> {
                dungeonService.enterDungeon(context.player(), def.id());
                return context.close();
            });
        }

        if (all.isEmpty()) {
            builder.button(22, buildIcon(Material.STRUCTURE_VOID, "No Dungeons", List.of("No dungeons are configured.")),
                context -> CompletableFuture.completedFuture(null));
        }

        menuBuilder.open(player, builder.build());
    }

    private void listDungeons(Player player) {
        Map<String, DungeonDefinition> all = dungeonService.registry().all();
        if (all.isEmpty()) {
            messageService.send(player, "<gray>No dungeons configured.</gray>");
            return;
        }

        messageService.send(player, "<gold>Available Dungeons:</gold>");
        for (DungeonDefinition def : all.values()) {
            messageService.send(player, "<yellow>" + def.id() + "</yellow> — " + def.displayName()
                + " <gray>(Lvl " + def.minLevel() + ", " + def.waves().size() + " waves)</gray>");
        }
    }

    private void showInfo(Player player) {
        var active = dungeonService.activeDungeons();
        if (active.isEmpty()) {
            messageService.send(player, "<gray>No active dungeon instances.</gray>");
            return;
        }

        messageService.send(player, "<gold>Active Dungeons: " + active.size() + "</gold>");
        for (ActiveDungeon dungeon : active.values()) {
            messageService.send(player, "<yellow>" + dungeon.definition().id() + "</yellow>"
                + " — State: " + dungeon.state()
                + " — Wave: " + dungeon.currentWaveIndex() + "/" + dungeon.definition().waves().size()
                + " — Players: " + dungeon.participants().size()
                + " — Time: " + dungeon.secondsRemaining() + "s");
        }
    }

    private ItemStack buildIcon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize(name));
        meta.lore(loreLines.stream()
            .filter(line -> !line.isEmpty())
            .map(line -> (Component) MINI.deserialize(line))
            .toList());
        item.setItemMeta(meta);
        return item;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
