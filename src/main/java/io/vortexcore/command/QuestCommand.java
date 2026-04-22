package io.vortexcore.command;

import io.vortexcore.quest.QuestDefinition;
import io.vortexcore.quest.QuestObjectiveProgress;
import io.vortexcore.quest.QuestProgress;
import io.vortexcore.quest.QuestRegistry;
import io.vortexcore.quest.QuestService;
import io.vortexcore.quest.QuestState;
import io.vortexcore.quest.QuestBoardMenu;
import io.vortexcore.ui.MessageService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class QuestCommand implements CommandExecutor, TabCompleter {

    private final QuestService questService;
    private final QuestBoardMenu questBoardMenu;
    private final MessageService messageService;

    public QuestCommand(QuestService questService, QuestBoardMenu questBoardMenu, MessageService messageService) {
        this.questService = questService;
        this.questBoardMenu = questBoardMenu;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use quest commands.");
            return true;
        }

        if (args.length == 0) {
            if (player.hasPermission("vortex.player.quest.open")) {
                questBoardMenu.open(player);
            } else {
                showHelp(player);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        return switch (sub) {
            case "open", "board" -> {
                if (!player.hasPermission("vortex.player.quest.open")) {
                    messageService.send(player, "<red>You don't have permission to open the quest board.</red>");
                    yield true;
                }
                questBoardMenu.open(player);
                yield true;
            }
            case "list" -> {
                if (!player.hasPermission("vortex.player.quest.list")) {
                    messageService.send(player, "<red>You don't have permission to list quests.</red>");
                    yield true;
                }
                handleList(player);
                yield true;
            }
            case "start", "accept" -> {
                if (!player.hasPermission("vortex.player.quest.start")) {
                    messageService.send(player, "<red>You don't have permission to start quests.</red>");
                    yield true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /quest start <quest_id></red>");
                    yield true;
                }
                handleStart(player, args[1]);
                yield true;
            }
            case "abandon" -> {
                if (!player.hasPermission("vortex.player.quest.abandon")) {
                    messageService.send(player, "<red>You don't have permission to abandon quests.</red>");
                    yield true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /quest abandon <quest_id></red>");
                    yield true;
                }
                handleAbandon(player, args[1]);
                yield true;
            }
            case "progress", "status" -> {
                if (!player.hasPermission("vortex.player.quest.progress")) {
                    messageService.send(player, "<red>You don't have permission to view quest progress.</red>");
                    yield true;
                }
                handleProgress(player);
                yield true;
            }
            case "log" -> {
                if (!player.hasPermission("vortex.player.quest.log")) {
                    messageService.send(player, "<red>You don't have permission to view the quest log.</red>");
                    yield true;
                }
                handleLog(player);
                yield true;
            }
            case "info" -> {
                if (!player.hasPermission("vortex.player.quest.info")) {
                    messageService.send(player, "<red>You don't have permission to view quest info.</red>");
                    yield true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /quest info <quest_id></red>");
                    yield true;
                }
                handleInfo(player, args[1]);
                yield true;
            }
            case "reload" -> {
                if (!player.hasPermission("vortex.admin.quest.reload")) {
                    messageService.send(player, "<red>You don't have permission to reload quests.</red>");
                    yield true;
                }
                QuestRegistry.ReloadResult result = questService.registry().reload();
                messageService.send(player, "<green>Reloaded " + result.loaded() + " quest definition(s).</green>");
                result.notes().forEach(note -> messageService.send(player, "<yellow>  " + note + "</yellow>"));
                yield true;
            }
            default -> {
                showHelp(player);
                yield true;
            }
        };
    }

    private void handleList(Player player) {
        questService.questData(player.getUniqueId()).thenAccept(data -> {
            Set<String> completed = new HashSet<>(data.completedQuestLog());
            Map<String, QuestDefinition> all = questService.registry().all();

            messageService.send(player, "<gold>═══ Available Quests ═══</gold>");

            if (all.isEmpty()) {
                messageService.send(player, "<gray>No quests are registered.</gray>");
                return;
            }

            for (QuestDefinition def : all.values()) {
                QuestProgress progress = data.quests().get(def.id());
                String status;
                if (progress != null && progress.state() == QuestState.ACTIVE) {
                    status = "<green>[Active]</green>";
                } else if (completed.contains(def.id())) {
                    status = "<gray>[Completed]</gray>";
                } else {
                    status = "<yellow>[Available]</yellow>";
                }

                messageService.send(player,
                    status + " <white>" + def.name() + "</white> <dark_gray>(" + def.id() + ")</dark_gray>"
                );
            }
        });
    }

    private void handleStart(Player player, String questId) {
        questService.startQuest(player, questId.toLowerCase(Locale.ROOT)).thenAccept(result -> {
            if (!result.success()) {
                messageService.send(player, "<red>" + result.message() + "</red>");
            }
        });
    }

    private void handleAbandon(Player player, String questId) {
        questService.abandonQuest(player, questId.toLowerCase(Locale.ROOT)).thenAccept(success -> {
            if (!success) {
                messageService.send(player, "<red>You don't have that quest active.</red>");
            }
        });
    }

    private void handleProgress(Player player) {
        questService.questData(player.getUniqueId()).thenAccept(data -> {
            List<Map.Entry<String, QuestProgress>> active = data.quests().entrySet().stream()
                .filter(e -> e.getValue().state() == QuestState.ACTIVE)
                .toList();

            if (active.isEmpty()) {
                messageService.send(player, "<gray>You have no active quests.</gray>");
                return;
            }

            messageService.send(player, "<gold>═══ Active Quests ═══</gold>");

            for (Map.Entry<String, QuestProgress> entry : active) {
                Optional<QuestDefinition> optDef = questService.registry().find(entry.getKey());
                String questName = optDef.map(QuestDefinition::name).orElse(entry.getKey());

                messageService.send(player, "<yellow>" + questName + "</yellow>");

                for (QuestObjectiveProgress obj : entry.getValue().objectives()) {
                    String objDesc = optDef.flatMap(def -> def.objectives().stream()
                        .filter(o -> o.id().equals(obj.objectiveId()))
                        .findFirst()
                        .map(o -> o.description()))
                        .orElse(obj.objectiveId());
                    String color = obj.completed() ? "<green>" : "<gray>";
                    String check = obj.completed() ? "✓" : "○";
                    messageService.send(player,
                        "  " + color + check + " " + objDesc + " " + obj.current() + "/" + obj.target() + color.replace("<", "</")
                    );
                }
            }
        });
    }

    private void handleLog(Player player) {
        questService.questData(player.getUniqueId()).thenAccept(data -> {
            List<String> log = data.completedQuestLog();
            if (log.isEmpty()) {
                messageService.send(player, "<gray>Your quest log is empty.</gray>");
                return;
            }

            messageService.send(player, "<gold>═══ Quest Log ═══</gold>");

            for (String questId : log) {
                Optional<QuestDefinition> optDef = questService.registry().find(questId);
                String questName = optDef.map(QuestDefinition::name).orElse(questId);
                messageService.send(player, "  <green>✓</green> <white>" + questName + "</white>");
            }
        });
    }

    private void handleInfo(Player player, String questId) {
        Optional<QuestDefinition> optDef = questService.registry().find(questId.toLowerCase(Locale.ROOT));
        if (optDef.isEmpty()) {
            messageService.send(player, "<red>Unknown quest: " + questId + "</red>");
            return;
        }

        QuestDefinition def = optDef.get();
        messageService.send(player, "<gold>═══ " + def.name() + " ═══</gold>");
        messageService.send(player, "<gray>" + def.description() + "</gray>");
        if (def.hasLevelRequirement()) {
            messageService.send(player, "<yellow>Required Level:</yellow> <white>" + def.requiredLevel() + "</white>");
        }
        if (def.hasPrerequisite()) {
            messageService.send(player, "<yellow>Prerequisite:</yellow> <white>" + def.requiredQuest() + "</white>");
        }
        if (def.repeatable()) {
            messageService.send(player, "<aqua>Repeatable</aqua>");
        }

        messageService.send(player, "<yellow>Objectives:</yellow>");
        for (var obj : def.objectives()) {
            messageService.send(player, "  <gray>○ " + obj.description() + " (x" + obj.amount() + ")</gray>");
        }

        if (!def.rewards().isEmpty()) {
            messageService.send(player, "<yellow>Rewards:</yellow>");
            for (var reward : def.rewards()) {
                String desc = switch (reward.type()) {
                    case EXPERIENCE -> (long) reward.amount() + " XP";
                    case CURRENCY -> reward.amount() + " coins";
                    case ITEM -> reward.value();
                    case COMMAND -> "Special reward";
                    case PERMISSION -> reward.value();
                };
                messageService.send(player, "  <green>★</green> <white>" + desc + "</white>");
            }
        }
    }

    private void showHelp(Player player) {
        messageService.send(player, "<gold>═══ Quest Commands ═══</gold>");
        messageService.send(player, "<yellow>/quest</yellow> <gray>- Open the quest board</gray>");
        messageService.send(player, "<yellow>/quest list</yellow> <gray>- List available quests</gray>");
        messageService.send(player, "<yellow>/quest start <id></yellow> <gray>- Accept a quest</gray>");
        messageService.send(player, "<yellow>/quest abandon <id></yellow> <gray>- Abandon an active quest</gray>");
        messageService.send(player, "<yellow>/quest progress</yellow> <gray>- View active quest progress</gray>");
        messageService.send(player, "<yellow>/quest log</yellow> <gray>- View completed quests</gray>");
        messageService.send(player, "<yellow>/quest info <id></yellow> <gray>- View quest details</gray>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("open", "list", "start", "abandon", "progress", "log", "info"));
            if (sender.hasPermission("vortex.admin.quest.reload")) {
                subs.add("reload");
            }
            return filterCompletions(subs, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start") || sub.equals("info")) {
                return filterCompletions(
                    new ArrayList<>(questService.registry().all().keySet()),
                    args[1]
                );
            }
            if (sub.equals("abandon")) {
                Player player = (Player) sender;
                questService.questData(player.getUniqueId()).thenAccept(data -> {});
                return filterCompletions(
                    new ArrayList<>(questService.registry().all().keySet()),
                    args[1]
                );
            }
        }

        return List.of();
    }

    private List<String> filterCompletions(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
            .collect(Collectors.toList());
    }
}
