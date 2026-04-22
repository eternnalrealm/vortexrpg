package io.vortexcore.talent;

import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class TalentTreeMenu {

    private final VortexMenuBuilder menuBuilder;
    private final TalentService talentService;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final FoliaExecutionFacade scheduler;
    private final MessageService messageService;
    private final ConcurrentMap<UUID, Instant> pendingResets;

    public TalentTreeMenu(
        VortexMenuBuilder menuBuilder,
        TalentService talentService,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        FoliaExecutionFacade scheduler,
        MessageService messageService
    ) {
        this.menuBuilder = menuBuilder;
        this.talentService = talentService;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.scheduler = scheduler;
        this.messageService = messageService;
        this.pendingResets = new ConcurrentHashMap<>();
    }

    public void open(Player player) {
        talentService.talentData(player.getUniqueId()).thenAccept(data ->
            scheduler.runEntity(player, "talent-menu-open-" + player.getUniqueId(), () -> {
                UnifiedPlayer playerProfile = playerProfileService.profile(player);
                Optional<VortexClass> selectedOpt = classSkillService.selectedClass(playerProfile);
                if (selectedOpt.isEmpty()) {
                    messageService.send(player, "<red>Choose a class first with /class.</red>");
                    return;
                }
                VortexClass vortexClass = selectedOpt.get();
                String classId = vortexClass.commandId();

                Optional<TalentTreeDefinition> treeOpt = talentService.registry().find(vortexClass);
                if (treeOpt.isEmpty()) {
                    messageService.send(player, "<red>No talent tree available for your class.</red>");
                    return;
                }
                TalentTreeDefinition tree = treeOpt.get();
                Set<String> allocated = data.allocatedNodes().getOrDefault(classId, Set.of());

                VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
                    Component.text(vortexClass.displayName() + " Talent Tree").color(NamedTextColor.GOLD),
                    6
                );

                for (Map.Entry<String, TalentNodeDefinition> entry : tree.nodes().entrySet()) {
                    TalentNodeDefinition node = entry.getValue();
                    int slot = node.row() * 9 + node.column();
                    if (slot < 0 || slot > 44) {
                        continue;
                    }

                    boolean isAllocated = allocated.contains(node.id());
                    boolean prereqsMet = node.prerequisites().stream().allMatch(allocated::contains);
                    boolean canAfford = data.talentPoints() >= node.cost();

                    ItemStack icon;
                    if (isAllocated) {
                        icon = buildIcon(Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN,
                            "\u2713 " + node.displayName(), buildAllocatedLore(node));
                    } else if (prereqsMet && canAfford) {
                        icon = buildIcon(Material.PAPER, NamedTextColor.YELLOW,
                            node.displayName(), buildAvailableLore(node));
                    } else if (prereqsMet) {
                        icon = buildIcon(Material.PAPER, NamedTextColor.RED,
                            node.displayName(), buildInsufficientLore(node, data.talentPoints()));
                    } else {
                        icon = buildIcon(Material.GRAY_STAINED_GLASS_PANE, NamedTextColor.DARK_GRAY,
                            node.displayName(), buildLockedLore(node, tree));
                    }

                    if (isAllocated) {
                        builder.button(slot, icon, ctx -> CompletableFuture.completedFuture(null));
                    } else if (prereqsMet && canAfford) {
                        builder.button(slot, icon, ctx -> {
                            talentService.allocateNode(ctx.player(), node.id()).thenAccept(result -> {
                                if (result.success()) {
                                    open(ctx.player());
                                }
                            });
                            return CompletableFuture.completedFuture(null);
                        });
                    } else {
                        builder.button(slot, icon, ctx -> CompletableFuture.completedFuture(null));
                    }
                }

                fillEmptySlots(builder, tree);

                builder.button(47, resetIcon(), ctx -> {
                    UUID playerId = ctx.player().getUniqueId();
                    Instant previous = pendingResets.get(playerId);
                    Instant now = Instant.now();
                    if (previous != null && now.minusSeconds(5).isBefore(previous)) {
                        pendingResets.remove(playerId);
                        talentService.resetTree(ctx.player()).thenAccept(success -> {
                            if (success) {
                                open(ctx.player());
                            }
                        });
                    } else {
                        pendingResets.put(playerId, now);
                        ctx.player().sendActionBar(MiniMessage.miniMessage().deserialize(
                            "<red>Click again within 5 seconds to confirm talent reset.</red>"
                        ));
                    }
                    return CompletableFuture.completedFuture(null);
                });

                builder.button(49, talentPointsIcon(data.talentPoints(), allocated.size()),
                    ctx -> CompletableFuture.completedFuture(null));

                builder.button(53, closeIcon(), ctx -> {
                    ctx.close();
                    return CompletableFuture.completedFuture(null);
                });

                menuBuilder.open(player, builder.build());
            })
        ).exceptionally(error -> {
            messageService.send(player, "<red>Failed to load talent data.</red>");
            return null;
        });
    }

    private List<Component> buildAllocatedLore(TalentNodeDefinition node) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(node.description()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        node.statBonuses().forEach((attr, bonus) ->
            lore.add(Component.text("+" + formatDouble(bonus) + " " + formatAttribute(attr))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
        );
        if (node.hasGrantedAbility()) {
            lore.add(Component.text("Ability: " + node.grantedAbility()).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        return lore;
    }

    private List<Component> buildAvailableLore(TalentNodeDefinition node) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(node.description()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Cost: " + node.cost() + " point(s)").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        node.statBonuses().forEach((attr, bonus) ->
            lore.add(Component.text("+" + formatDouble(bonus) + " " + formatAttribute(attr))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
        );
        if (node.hasGrantedAbility()) {
            lore.add(Component.text("Ability: " + node.grantedAbility()).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Click to unlock").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    private List<Component> buildInsufficientLore(TalentNodeDefinition node, int currentPoints) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(node.description()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Need " + node.cost() + " point(s), have " + currentPoints)
            .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        node.statBonuses().forEach((attr, bonus) ->
            lore.add(Component.text("+" + formatDouble(bonus) + " " + formatAttribute(attr))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        );
        return lore;
    }

    private List<Component> buildLockedLore(TalentNodeDefinition node, TalentTreeDefinition tree) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(node.description()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        List<String> prereqNames = new ArrayList<>();
        for (String prereqId : node.prerequisites()) {
            tree.find(prereqId).ifPresentOrElse(
                prereq -> prereqNames.add(prereq.displayName()),
                () -> prereqNames.add(prereqId)
            );
        }
        lore.add(Component.text("Requires: " + String.join(", ", prereqNames))
            .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    private void fillEmptySlots(VortexMenuBuilder.MenuDefinition.Builder builder, TalentTreeDefinition tree) {
        Set<Integer> usedSlots = new java.util.HashSet<>();
        tree.nodes().values().forEach(node -> usedSlots.add(node.row() * 9 + node.column()));
        usedSlots.add(47);
        usedSlots.add(49);
        usedSlots.add(53);

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        filler.setItemMeta(meta);

        for (int i = 45; i < 54; i++) {
            if (!usedSlots.contains(i)) {
                builder.button(i, filler, ctx -> CompletableFuture.completedFuture(null));
            }
        }
    }

    private ItemStack buildIcon(Material material, NamedTextColor nameColor, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(nameColor).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetIcon() {
        return buildIcon(Material.LAVA_BUCKET, NamedTextColor.RED, "Reset Tree",
            List.of(
                Component.text("Cost: " + String.format("%.0f", talentService.resetCost()) + " coins")
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                Component.text("Click twice to confirm").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
    }

    private ItemStack talentPointsIcon(int available, int spent) {
        return buildIcon(Material.EXPERIENCE_BOTTLE, NamedTextColor.GOLD, "Talent Points",
            List.of(
                Component.text("Available: " + available).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("Allocated: " + spent).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
    }

    private ItemStack closeIcon() {
        return buildIcon(Material.BARRIER, NamedTextColor.RED, "Close", List.of());
    }

    private String formatAttribute(VortexAttribute attribute) {
        String raw = attribute.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private String formatDouble(double value) {
        return value == (long) value ? Long.toString((long) value) : String.format(Locale.US, "%.1f", value);
    }
}
