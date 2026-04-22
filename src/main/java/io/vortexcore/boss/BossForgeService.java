package io.vortexcore.boss;

import io.vortexcore.forge.AbstractForgeService;
import io.vortexcore.forge.ForgeInputGuard;
import io.vortexcore.forge.GenericPendingInput;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

public final class BossForgeService extends AbstractForgeService<BossForgeService.EditableBoss, BossForgeService.ChatField> {

    private final BossRegistry bossRegistry;
    private final ConcurrentMap<java.util.UUID, EditableBoss> sessions;

    public BossForgeService(
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard,
        BossRegistry bossRegistry
    ) {
        super("boss-forge", scheduler, menuBuilder, messageService, forgeInputGuard);
        this.bossRegistry = bossRegistry;
        this.sessions = new ConcurrentHashMap<>();
    }

    public void openBrowser(Player player) {
        Map<String, BossDefinition> all = bossRegistry.all();
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Boss Forge — Browser"), 4);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int index = 0;

        for (Map.Entry<String, BossDefinition> entry : all.entrySet()) {
            if (index >= slots.length) break;
            BossDefinition def = entry.getValue();
            int slot = slots[index++];

            builder.button(slot, icon(Material.SPAWNER, def.displayName(), List.of(
                "ID: " + def.id(),
                "Entity: " + def.entityType().name(),
                "HP: " + String.format(Locale.US, "%.0f", def.maxHealth()),
                "Level: " + def.minLevel(),
                "",
                "Click to edit"
            )), context -> {
                EditableBoss editable = EditableBoss.from(def);
                sessions.put(player.getUniqueId(), editable);
                context.open(buildEditorMenu(player, editable));
                return CompletableFuture.completedFuture(null);
            });
        }

        builder.button(31, icon(Material.EMERALD, "New Boss", List.of("Click to create a new boss.")), context -> {
            EditableBoss fresh = EditableBoss.fresh();
            sessions.put(player.getUniqueId(), fresh);
            context.open(buildEditorMenu(player, fresh));
            return CompletableFuture.completedFuture(null);
        });

        menuBuilder.open(player, builder.build());
    }

    private VortexMenuBuilder.MenuDefinition buildEditorMenu(Player player, EditableBoss boss) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
            Component.text("Boss Forge: " + boss.displayName), 6);

        // Row 1: identity
        builder.button(10, icon(Material.NAME_TAG, "Display Name", List.of(
            "Current: " + boss.displayName, "Click and type in chat."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.DISPLAY_NAME, "<yellow>Type a new display name (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(11, icon(Material.COMMAND_BLOCK, "Boss ID", List.of(
            "Current: " + boss.id, "Click and type in chat."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.BOSS_ID, "<yellow>Type a new boss ID (lowercase, underscores, or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(12, icon(Material.ZOMBIE_HEAD, "Entity Type", List.of(
            "Current: " + boss.entityType.name(), "Click and type in chat."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.ENTITY_TYPE, "<yellow>Type an entity type (e.g. WITHER_SKELETON, or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(13, icon(boss.summonItem, "Summon Item", List.of(
            "Current: " + boss.summonItem.name(), "Click and type in chat."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.SUMMON_ITEM, "<yellow>Type a material name (e.g. NETHER_STAR, or 'cancel'):</yellow>");
            return context.close();
        });

        // Row 2: health
        builder.button(19, icon(Material.RED_STAINED_GLASS_PANE, "HP -50", List.of(
            "Current HP: " + String.format(Locale.US, "%.0f", boss.maxHealth)
        )), context -> {
            boss.maxHealth = Math.max(50, boss.maxHealth - 50);
            context.open(buildEditorMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(20, icon(Material.APPLE, "Max Health", List.of(
            String.format(Locale.US, "%.0f", boss.maxHealth),
            "Click to type exact value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.MAX_HEALTH, "<yellow>Type max health value (or 'cancel'):</yellow>");
            return context.close();
        });
        builder.button(21, icon(Material.GREEN_STAINED_GLASS_PANE, "HP +50", List.of(
            "Current HP: " + String.format(Locale.US, "%.0f", boss.maxHealth)
        )), context -> {
            boss.maxHealth = Math.min(100000, boss.maxHealth + 50);
            context.open(buildEditorMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });

        // Row 2: level
        builder.button(23, icon(Material.RED_STAINED_GLASS_PANE, "Level -1", List.of(
            "Current: " + boss.minLevel
        )), context -> {
            boss.minLevel = Math.max(1, boss.minLevel - 1);
            context.open(buildEditorMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(24, icon(Material.EXPERIENCE_BOTTLE, "Min Level", List.of(
            "Current: " + boss.minLevel,
            "Click to type exact value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.MIN_LEVEL, "<yellow>Type min level (or 'cancel'):</yellow>");
            return context.close();
        });
        builder.button(25, icon(Material.GREEN_STAINED_GLASS_PANE, "Level +1", List.of(
            "Current: " + boss.minLevel
        )), context -> {
            boss.minLevel = Math.min(100, boss.minLevel + 1);
            context.open(buildEditorMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });

        // Row 3: multipliers
        builder.button(28, icon(Material.IRON_SWORD, "Damage Mult", List.of(
            "Current: " + String.format(Locale.US, "%.2f", boss.damageMultiplier),
            "Click to type new value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.DAMAGE_MULT, "<yellow>Type damage multiplier (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(29, icon(Material.FEATHER, "Speed Mult", List.of(
            "Current: " + String.format(Locale.US, "%.2f", boss.speedMultiplier),
            "Click to type new value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.SPEED_MULT, "<yellow>Type speed multiplier (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(30, icon(Material.CLOCK, "Spawn Cooldown", List.of(
            "Current: " + boss.spawnCooldownSeconds + "s",
            "Click to type new value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.SPAWN_COOLDOWN, "<yellow>Type spawn cooldown in seconds (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(31, icon(Material.HOPPER, "Despawn Timer", List.of(
            "Current: " + boss.despawnAfterSeconds + "s",
            "Click to type new value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.DESPAWN_TIMER, "<yellow>Type despawn timer in seconds (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(32, icon(Material.ENDER_EYE, "Arena Radius", List.of(
            "Current: " + boss.arenaRadius,
            "Click to type new value."
        )), context -> {
            promptForChat(context.player(), boss, ChatField.ARENA_RADIUS, "<yellow>Type arena radius (or 'cancel'):</yellow>");
            return context.close();
        });

        builder.button(34, icon(Material.WRITABLE_BOOK, "Phases: " + boss.phases.size(), List.of(
            "Click to view/edit phases."
        )), context -> {
            context.open(buildPhasesMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });

        // Bottom row: save / back / delete
        builder.button(47, icon(Material.EMERALD, "Save Boss", List.of(
            "Saves to bosses.yml and reloads."
        )), context -> {
            saveBoss(player, boss);
            return context.close();
        });

        builder.button(49, icon(Material.ARROW, "Back to Browser", List.of()), context -> {
            sessions.remove(player.getUniqueId());
            openBrowser(player);
            return CompletableFuture.completedFuture(null);
        });

        builder.button(51, icon(Material.TNT, "Delete Boss", List.of(
            "Permanently removes from bosses.yml."
        )), context -> {
            if (boss.sourceId != null && bossRegistry.deleteBoss(boss.sourceId)) {
                bossRegistry.reload();
                messageService.send(player, "<green>Boss '" + boss.sourceId + "' deleted.</green>");
            } else {
                messageService.send(player, "<red>Could not delete — boss not saved on disk.</red>");
            }
            sessions.remove(player.getUniqueId());
            openBrowser(player);
            return CompletableFuture.completedFuture(null);
        });

        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildPhasesMenu(Player player, EditableBoss boss) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
            Component.text("Phases: " + boss.displayName), 4);

        for (int i = 0; i < boss.phases.size() && i < 14; i++) {
            EditablePhase phase = boss.phases.get(i);
            int slot = 10 + (i % 7) + (i / 7) * 9;
            int phaseIndex = i;

            builder.button(slot, icon(Material.BLAZE_POWDER, "Phase " + (i + 1), List.of(
                "Threshold: " + String.format(Locale.US, "%.0f%%", phase.threshold * 100),
                "Abilities: " + String.join(", ", phase.abilities),
                "Damage× " + String.format(Locale.US, "%.2f", phase.damageMultiplier),
                "Speed× " + String.format(Locale.US, "%.2f", phase.speedMultiplier),
                "",
                "Left-click to edit threshold.",
                "Right-click to edit abilities."
            )), context -> {
                promptForChat(context.player(), boss, ChatField.PHASE_EDIT,
                    "<yellow>Editing Phase " + (phaseIndex + 1) + ". Format: threshold,abilities,damage,speed</yellow>\n"
                    + "<gray>Example: 0.75,fireball_barrage;flame_aura,2.0,1.2</gray>\n"
                    + "<gray>Type 'cancel' to abort.</gray>");
                boss.editingPhaseIndex = phaseIndex;
                return context.close();
            });
        }

        builder.button(28, icon(Material.LIME_DYE, "Add Phase", List.of(
            "Adds a new phase at 50% HP."
        )), context -> {
            boss.phases.add(new EditablePhase(0.5, new ArrayList<>(), boss.speedMultiplier, boss.damageMultiplier));
            context.open(buildPhasesMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });

        if (!boss.phases.isEmpty()) {
            builder.button(29, icon(Material.RED_DYE, "Remove Last Phase", List.of()), context -> {
                boss.phases.remove(boss.phases.size() - 1);
                context.open(buildPhasesMenu(player, boss));
                return CompletableFuture.completedFuture(null);
            });
        }

        builder.button(31, icon(Material.ARROW, "Back to Editor", List.of()), context -> {
            context.open(buildEditorMenu(player, boss));
            return CompletableFuture.completedFuture(null);
        });

        return builder.build();
    }

    private void saveBoss(Player player, EditableBoss boss) {
        if (boss.id == null || boss.id.isBlank()) {
            messageService.send(player, "<red>Boss ID cannot be empty.</red>");
            return;
        }

        BossDefinition def = new BossDefinition(
            boss.id, boss.displayName, boss.entityType, boss.maxHealth,
            boss.damageMultiplier, boss.speedMultiplier, boss.minLevel,
            boss.spawnCooldownSeconds, boss.despawnAfterSeconds,
            boss.phases.stream().map(p -> new BossDefinition.BossPhase(
                p.threshold, List.copyOf(p.abilities), p.speedMultiplier, p.damageMultiplier
            )).toList(),
            boss.lootProfiles.stream().map(lp -> new BossDefinition.BossLootProfile(
                lp.itemId, lp.baseValue, lp.scalePerLevel, lp.spread
            )).toList(),
            boss.summonItem, boss.arenaRadius
        );

        if (boss.sourceId != null && !boss.sourceId.equals(boss.id)) {
            bossRegistry.deleteBoss(boss.sourceId);
        }

        bossRegistry.saveBoss(def);
        BossRegistry.ReloadResult result = bossRegistry.reload();
        sessions.remove(player.getUniqueId());
        messageService.send(player, "<green>Boss '" + boss.id + "' saved. Registry: " + result.loaded() + " boss(es) loaded.</green>");
    }

    @Override
    protected void handleChatInput(Player player, GenericPendingInput<EditableBoss, ChatField> pending, String input) {
        EditableBoss boss = pending.entity();

        if (input.equalsIgnoreCase("cancel")) {
            messageService.send(player, "Boss forge input cancelled.");
            menuBuilder.open(player, buildEditorMenu(player, boss));
            return;
        }

        switch (pending.field()) {
            case DISPLAY_NAME -> boss.displayName = input;
            case BOSS_ID -> boss.id = input.toLowerCase(Locale.ROOT).replace(' ', '_');
            case ENTITY_TYPE -> {
                try {
                    boss.entityType = EntityType.valueOf(input.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    messageService.send(player, "<red>Unknown entity type: " + input + "</red>");
                }
            }
            case SUMMON_ITEM -> {
                try {
                    boss.summonItem = Material.valueOf(input.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    messageService.send(player, "<red>Unknown material: " + input + "</red>");
                }
            }
            case MAX_HEALTH -> {
                try {
                    boss.maxHealth = Math.max(1, Double.parseDouble(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case MIN_LEVEL -> {
                try {
                    boss.minLevel = Math.max(1, Integer.parseInt(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case DAMAGE_MULT -> {
                try {
                    boss.damageMultiplier = Math.max(0.1, Double.parseDouble(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case SPEED_MULT -> {
                try {
                    boss.speedMultiplier = Math.max(0.1, Double.parseDouble(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case SPAWN_COOLDOWN -> {
                try {
                    boss.spawnCooldownSeconds = Math.max(0, Long.parseLong(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case DESPAWN_TIMER -> {
                try {
                    boss.despawnAfterSeconds = Math.max(10, Long.parseLong(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case ARENA_RADIUS -> {
                try {
                    boss.arenaRadius = Math.max(5, Integer.parseInt(input));
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid number.</red>");
                }
            }
            case PHASE_EDIT -> {
                int idx = boss.editingPhaseIndex;
                if (idx < 0 || idx >= boss.phases.size()) {
                    messageService.send(player, "<red>Phase index out of range.</red>");
                    break;
                }
                try {
                    String[] parts = input.split(",", 4);
                    EditablePhase phase = boss.phases.get(idx);
                    if (parts.length >= 1) phase.threshold = Double.parseDouble(parts[0].trim());
                    if (parts.length >= 2) {
                        phase.abilities.clear();
                        for (String ability : parts[1].trim().split(";")) {
                            String trimmed = ability.trim();
                            if (!trimmed.isEmpty()) phase.abilities.add(trimmed);
                        }
                    }
                    if (parts.length >= 3) phase.damageMultiplier = Double.parseDouble(parts[2].trim());
                    if (parts.length >= 4) phase.speedMultiplier = Double.parseDouble(parts[3].trim());
                } catch (NumberFormatException exception) {
                    messageService.send(player, "<red>Invalid format. Use: threshold,abilities,damage,speed</red>");
                }
            }
        }

        messageService.send(player, "Updated <gold>" + pending.field().label + "</gold>.");
        menuBuilder.open(player, buildEditorMenu(player, boss));
    }

    enum ChatField {
        DISPLAY_NAME("display name"),
        BOSS_ID("boss id"),
        ENTITY_TYPE("entity type"),
        SUMMON_ITEM("summon item"),
        MAX_HEALTH("max health"),
        MIN_LEVEL("min level"),
        DAMAGE_MULT("damage multiplier"),
        SPEED_MULT("speed multiplier"),
        SPAWN_COOLDOWN("spawn cooldown"),
        DESPAWN_TIMER("despawn timer"),
        ARENA_RADIUS("arena radius"),
        PHASE_EDIT("phase");

        final String label;
        ChatField(String label) { this.label = label; }
    }

    static final class EditableBoss {
        String sourceId;
        String id;
        String displayName;
        EntityType entityType;
        double maxHealth;
        double damageMultiplier;
        double speedMultiplier;
        int minLevel;
        long spawnCooldownSeconds;
        long despawnAfterSeconds;
        Material summonItem;
        int arenaRadius;
        List<EditablePhase> phases;
        List<EditableLootProfile> lootProfiles;
        int editingPhaseIndex;

        static EditableBoss fresh() {
            EditableBoss b = new EditableBoss();
            b.sourceId = null;
            b.id = "new_boss";
            b.displayName = "<red>New Boss</red>";
            b.entityType = EntityType.ZOMBIE;
            b.maxHealth = 200;
            b.damageMultiplier = 1.0;
            b.speedMultiplier = 1.0;
            b.minLevel = 1;
            b.spawnCooldownSeconds = 300;
            b.despawnAfterSeconds = 180;
            b.summonItem = Material.NETHER_STAR;
            b.arenaRadius = 30;
            b.phases = new ArrayList<>();
            b.phases.add(new EditablePhase(1.0, new ArrayList<>(), 1.0, 1.0));
            b.lootProfiles = new ArrayList<>();
            b.editingPhaseIndex = -1;
            return b;
        }

        static EditableBoss from(BossDefinition def) {
            EditableBoss b = new EditableBoss();
            b.sourceId = def.id();
            b.id = def.id();
            b.displayName = def.displayName();
            b.entityType = def.entityType();
            b.maxHealth = def.maxHealth();
            b.damageMultiplier = def.damageMultiplier();
            b.speedMultiplier = def.speedMultiplier();
            b.minLevel = def.minLevel();
            b.spawnCooldownSeconds = def.spawnCooldownSeconds();
            b.despawnAfterSeconds = def.despawnAfterSeconds();
            b.summonItem = def.summonItem();
            b.arenaRadius = def.arenaRadius();
            b.phases = new ArrayList<>();
            for (BossDefinition.BossPhase p : def.phases()) {
                b.phases.add(new EditablePhase(p.threshold(), new ArrayList<>(p.abilities()), p.speedMultiplier(), p.damageMultiplier()));
            }
            b.lootProfiles = new ArrayList<>();
            for (BossDefinition.BossLootProfile lp : def.lootProfiles()) {
                b.lootProfiles.add(new EditableLootProfile(lp.itemId(), lp.baseValue(), lp.scalePerLevel(), lp.spread()));
            }
            b.editingPhaseIndex = -1;
            return b;
        }
    }

    static final class EditablePhase {
        double threshold;
        List<String> abilities;
        double speedMultiplier;
        double damageMultiplier;

        EditablePhase(double threshold, List<String> abilities, double speedMultiplier, double damageMultiplier) {
            this.threshold = threshold;
            this.abilities = abilities;
            this.speedMultiplier = speedMultiplier;
            this.damageMultiplier = damageMultiplier;
        }
    }

    static final class EditableLootProfile {
        String itemId;
        double baseValue;
        double scalePerLevel;
        double spread;

        EditableLootProfile(String itemId, double baseValue, double scalePerLevel, double spread) {
            this.itemId = itemId;
            this.baseValue = baseValue;
            this.scalePerLevel = scalePerLevel;
            this.spread = spread;
        }
    }
}
