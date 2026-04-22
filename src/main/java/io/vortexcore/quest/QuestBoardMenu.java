package io.vortexcore.quest;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class QuestBoardMenu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int ROWS = 6;

    private final VortexMenuBuilder menuBuilder;
    private final QuestService questService;
    private final FoliaExecutionFacade scheduler;

    public QuestBoardMenu(VortexMenuBuilder menuBuilder, QuestService questService, FoliaExecutionFacade scheduler) {
        this.menuBuilder = menuBuilder;
        this.questService = questService;
        this.scheduler = scheduler;
    }

    public void open(Player player) {
        questService.questData(player.getUniqueId()).thenAccept(data -> {
            Set<String> completedIds = new HashSet<>(data.completedQuestLog());
            for (Map.Entry<String, QuestProgress> entry : data.quests().entrySet()) {
                if (entry.getValue().state() == QuestState.COMPLETED) {
                    completedIds.add(entry.getKey());
                }
            }

            Map<String, QuestDefinition> all = questService.registry().all();
            List<QuestDefinition> available = new ArrayList<>();
            List<QuestDefinition> active = new ArrayList<>();
            List<QuestDefinition> completed = new ArrayList<>();

            for (QuestDefinition def : all.values()) {
                QuestProgress progress = data.quests().get(def.id());
                if (progress != null && progress.state() == QuestState.ACTIVE) {
                    active.add(def);
                } else if (completedIds.contains(def.id())) {
                    completed.add(def);
                } else {
                    available.add(def);
                }
            }

            VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
                MINI.deserialize("<gold>✦ Quest Board</gold>"),
                ROWS
            );

            int slot = 0;

            // Header: Active quests section
            if (!active.isEmpty()) {
                builder.button(slot++, createSectionHeader("Active Quests", Material.WRITABLE_BOOK), ctx ->
                    CompletableFuture.completedFuture(null)
                );

                for (QuestDefinition def : active) {
                    if (slot >= 45) break;
                    QuestProgress progress = data.quests().get(def.id());
                    builder.button(slot++, createActiveQuestIcon(def, progress), ctx -> {
                        ctx.close();
                        return CompletableFuture.completedFuture(null);
                    });
                }
            }

            // Separator
            if (!active.isEmpty() && !available.isEmpty()) {
                if (slot < 45) {
                    builder.button(slot++, createSeparator(), ctx ->
                        CompletableFuture.completedFuture(null)
                    );
                }
            }

            // Available quests section
            if (!available.isEmpty()) {
                if (slot < 45) {
                    builder.button(slot++, createSectionHeader("Available Quests", Material.BOOK), ctx ->
                        CompletableFuture.completedFuture(null)
                    );
                }

                for (QuestDefinition def : available) {
                    if (slot >= 45) break;
                    builder.button(slot++, createAvailableQuestIcon(def), ctx -> {
                        questService.startQuest(ctx.player(), def.id());
                        ctx.close();
                        return CompletableFuture.completedFuture(null);
                    });
                }
            }

            // Bottom row: info/navigation
            builder.button(49, createInfoIcon(active.size(), available.size(), completed.size()), ctx ->
                CompletableFuture.completedFuture(null)
            );

            builder.button(53, createCloseIcon(), ctx -> {
                ctx.close();
                return CompletableFuture.completedFuture(null);
            });

            scheduler.runEntity(player, "quest-board-open-" + player.getUniqueId(), () ->
                menuBuilder.open(player, builder.build())
            );
        });
    }

    private ItemStack createSectionHeader(String title, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSeparator() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createActiveQuestIcon(QuestDefinition def, QuestProgress progress) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(def.name(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(def.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Objectives:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        if (progress != null) {
            for (QuestObjectiveProgress obj : progress.objectives()) {
                Optional<QuestObjectiveDefinition> objDef = def.objectives().stream()
                    .filter(o -> o.id().equals(obj.objectiveId()))
                    .findFirst();
                String desc = objDef.map(QuestObjectiveDefinition::description).orElse(obj.objectiveId());
                NamedTextColor color = obj.completed() ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                String symbol = obj.completed() ? "✓" : "○";
                lore.add(Component.text("  " + symbol + " " + desc + " " + obj.current() + "/" + obj.target(), color)
                    .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Click to view details", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAvailableQuestIcon(QuestDefinition def) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(def.name(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(def.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());

        if (def.hasLevelRequirement()) {
            lore.add(Component.text("Required Level: " + def.requiredLevel(), NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.text("Objectives:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        for (QuestObjectiveDefinition obj : def.objectives()) {
            lore.add(Component.text("  ○ " + obj.description() + " (x" + obj.amount() + ")", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        }

        if (!def.rewards().isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("Rewards:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            for (QuestRewardDefinition reward : def.rewards()) {
                String desc = switch (reward.type()) {
                    case EXPERIENCE -> (long) reward.amount() + " XP";
                    case CURRENCY -> reward.amount() + " coins";
                    case ITEM -> reward.value();
                    case COMMAND -> "Special reward";
                    case PERMISSION -> reward.value();
                };
                lore.add(Component.text("  ★ " + desc, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Click to accept quest", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoIcon(int activeCount, int availableCount, int completedCount) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Quest Summary", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Active: " + activeCount, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
            Component.text("Available: " + availableCount, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
            Component.text("Completed: " + completedCount, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseIcon() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
