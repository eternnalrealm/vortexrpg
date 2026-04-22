package io.vortexcore.boss;

import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class BossCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.boss.open";
    private static final String PERM_LIST = "vortex.player.boss.list";
    private static final String PERM_SPAWN = "vortex.admin.boss.spawn";
    private static final String PERM_SUMMON = "vortex.admin.boss.summon";
    private static final String PERM_CLEAR = "vortex.admin.boss.clear";
    private static final String PERM_INFO = "vortex.admin.boss.info";
    private static final String PERM_EDIT = "vortex.admin.boss.edit";
    private static final String PERM_RELOAD = "vortex.admin.boss.reload";

    private final BossService bossService;
    private final BossForgeService bossForgeService;
    private final MessageService messageService;

    public BossCommand(BossService bossService, BossForgeService bossForgeService, MessageService messageService) {
        this.bossService = bossService;
        this.bossForgeService = bossForgeService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use boss commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) return true;
            bossService.openBossMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (deny(player, PERM_LIST)) return true;
            Map<String, BossDefinition> all = bossService.registry().all();
            if (all.isEmpty()) {
                messageService.send(player, "No bosses configured.");
                return true;
            }
            messageService.send(player, "<gold>Registered Bosses:</gold>");
            for (Map.Entry<String, BossDefinition> entry : all.entrySet()) {
                BossDefinition def = entry.getValue();
                messageService.send(player, " <gray>-</gray> " + def.displayName() + " <dark_gray>(</dark_gray>" + def.id()
                    + "<dark_gray>, Lv" + def.minLevel() + ", HP " + String.format(Locale.US, "%.0f", def.maxHealth()) + ")</dark_gray>");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (deny(player, PERM_SPAWN)) return true;
            if (args.length < 2) {
                messageService.send(player, "<red>Usage: /vboss spawn <id></red>");
                return true;
            }
            bossService.spawnBoss(player, args[1].toLowerCase(Locale.ROOT), player.getLocation());
            return true;
        }

        if (args[0].equalsIgnoreCase("summon")) {
            if (deny(player, PERM_SUMMON)) return true;
            if (args.length < 2) {
                messageService.send(player, "<red>Usage: /vboss summon <id></red>");
                return true;
            }
            bossService.forceSummon(player, args[1].toLowerCase(Locale.ROOT), player.getLocation());
            return true;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            if (deny(player, PERM_CLEAR)) return true;
            int cleared = bossService.clearAll();
            messageService.send(player, "<green>Cleared " + cleared + " active boss(es).</green>");
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            if (deny(player, PERM_INFO)) return true;
            if (args.length < 2) {
                // Show active bosses
                Map<UUID, ActiveBoss> active = bossService.activeBosses();
                if (active.isEmpty()) {
                    messageService.send(player, "<gray>No bosses currently active.</gray>");
                } else {
                    messageService.send(player, "<gold>Active Bosses:</gold>");
                    for (ActiveBoss ab : active.values()) {
                        BossDefinition def = ab.definition();
                        messageService.send(player, " <gray>-</gray> " + def.displayName()
                            + " <dark_gray>(Phase " + (ab.currentPhaseIndex() + 1) + "/" + def.phases().size()
                            + ", " + ab.participants().size() + " participant(s)"
                            + ", " + ab.secondsAlive() + "s alive)</dark_gray>");
                    }
                }
                return true;
            }
            String bossId = args[1].toLowerCase(Locale.ROOT);
            BossDefinition def = bossService.registry().get(bossId).orElse(null);
            if (def == null) {
                messageService.send(player, "<red>Unknown boss: " + bossId + "</red>");
                return true;
            }
            messageService.send(player, "<gold>═══ " + def.displayName() + " <gold>═══</gold>");
            messageService.send(player, " <gray>ID:</gray> " + def.id());
            messageService.send(player, " <gray>Entity:</gray> " + def.entityType().name());
            messageService.send(player, " <gray>HP:</gray> " + String.format(Locale.US, "%.0f", def.maxHealth()));
            messageService.send(player, " <gray>DMG×:</gray> " + String.format(Locale.US, "%.2f", def.damageMultiplier())
                + " <gray>SPD×:</gray> " + String.format(Locale.US, "%.2f", def.speedMultiplier()));
            messageService.send(player, " <gray>Level:</gray> " + def.minLevel()
                + " <gray>Cooldown:</gray> " + def.spawnCooldownSeconds() + "s"
                + " <gray>Despawn:</gray> " + def.despawnAfterSeconds() + "s");
            messageService.send(player, " <gray>Phases:</gray> " + def.phases().size());
            for (int i = 0; i < def.phases().size(); i++) {
                BossDefinition.BossPhase phase = def.phases().get(i);
                messageService.send(player, "   <dark_gray>" + (i + 1) + ".</dark_gray> @" + String.format(Locale.US, "%.0f%%", phase.threshold() * 100)
                    + " <gray>Abilities:</gray> " + (phase.abilities().isEmpty() ? "none" : String.join(", ", phase.abilities())));
            }
            messageService.send(player, " <gray>Loot Profiles:</gray> " + def.lootProfiles().size());
            for (BossDefinition.BossLootProfile lp : def.lootProfiles()) {
                messageService.send(player, "   <dark_gray>-</dark_gray> " + lp.itemId()
                    + " <gray>(base:" + String.format(Locale.US, "%.1f", lp.baseValue())
                    + " scale:" + String.format(Locale.US, "%.2f", lp.scalePerLevel()) + ")</gray>");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            if (deny(player, PERM_EDIT)) return true;
            bossForgeService.openBrowser(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (deny(player, PERM_RELOAD)) return true;
            BossRegistry.ReloadResult result = bossService.registry().reload();
            messageService.send(player, "Boss registry reloaded. Loaded: " + result.loaded() + ", Skipped: " + result.skipped());
            result.notes().forEach(note -> messageService.send(player, " <gray>" + note + "</gray>"));
            return true;
        }

        messageService.send(player, "<red>Unknown subcommand. Usage: /vboss [open|list|spawn|summon|clear|info|edit|reload]</red>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) subs.add("open");
            if (sender.hasPermission(PERM_LIST)) subs.add("list");
            if (sender.hasPermission(PERM_SPAWN)) subs.add("spawn");
            if (sender.hasPermission(PERM_SUMMON)) subs.add("summon");
            if (sender.hasPermission(PERM_CLEAR)) subs.add("clear");
            if (sender.hasPermission(PERM_INFO)) subs.add("info");
            if (sender.hasPermission(PERM_EDIT)) subs.add("edit");
            if (sender.hasPermission(PERM_RELOAD)) subs.add("reload");
            return filter(subs, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("spawn") && sender.hasPermission(PERM_SPAWN))
                || (sub.equals("summon") && sender.hasPermission(PERM_SUMMON))
                || (sub.equals("info") && sender.hasPermission(PERM_INFO))) {
                return filter(new ArrayList<>(bossService.registry().all().keySet()), args[1]);
            }
        }

        return List.of();
    }

    private boolean deny(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            messageService.send(player, "<red>You don't have permission to do that.</red>");
            return true;
        }
        return false;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
