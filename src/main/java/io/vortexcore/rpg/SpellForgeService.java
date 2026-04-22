package io.vortexcore.rpg;

import io.vortexcore.forge.AbstractForgeService;
import io.vortexcore.forge.ForgeInputGuard;
import io.vortexcore.forge.GenericPendingInput;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpellForgeService extends AbstractForgeService<SpellForgeService.EditableSpell, SpellForgeService.ChatField> {

    private static final int[] SPELL_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final List<Particle> PARTICLE_CYCLE = List.of(
        Particle.FLAME,
        Particle.HAPPY_VILLAGER,
        Particle.ENCHANT,
        Particle.SOUL_FIRE_FLAME,
        Particle.CLOUD,
        Particle.WITCH
    );
    private static final List<Sound> SOUND_CYCLE = List.of(
        Sound.ENTITY_BLAZE_SHOOT,
        Sound.BLOCK_AMETHYST_BLOCK_CHIME,
        Sound.ENTITY_ILLUSIONER_CAST_SPELL,
        Sound.ENTITY_PLAYER_LEVELUP,
        Sound.BLOCK_RESPAWN_ANCHOR_CHARGE
    );

    @SuppressWarnings("unused")
    private final JavaPlugin plugin;
    private final SpellbookService spellbookService;
    private final File spellsFile;
    private final ConcurrentMap<UUID, EditorSession> sessions;

    public SpellForgeService(
        JavaPlugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        SpellbookService spellbookService,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard
    ) {
        super("spell-forge", scheduler, menuBuilder, messageService, forgeInputGuard);
        this.plugin = plugin;
        this.spellbookService = spellbookService;
        this.spellsFile = new File(plugin.getDataFolder(), "spells.yml");
        this.sessions = new ConcurrentHashMap<>();
    }

    public void openBrowser(Player player) {
        scheduler.runAsync("spell-forge-open-" + player.getUniqueId(), () -> {
            List<EditableSpell> spells = new ArrayList<>(loadCustomSpells().values());
            scheduler.runEntity(player, "spell-forge-browser-" + player.getUniqueId(), () -> menuBuilder.open(player, buildBrowserMenu(player, spells)));
        });
    }

    @Override
    protected void handleChatInput(Player player, GenericPendingInput<EditableSpell, ChatField> pending, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            messageService.send(player, "Spell forge input cancelled.");
            menuBuilder.open(player, buildEditorMenu(player, pending.entity()));
            return;
        }

        try {
            switch (pending.field()) {
                case DISPLAY_NAME -> pending.entity().displayName = input.isBlank() ? pending.entity().displayName : input;
                case SPELL_ID -> pending.entity().setId(input);
                case MESSAGE -> pending.entity().message = input;
                case EXTRA_TARGETERS -> pending.entity().extraTargeters = parseExtraTargeters(input);
                case CHAINED_MECHANICS -> pending.entity().extraMechanics = parseChainedMechanics(input);
            }
            messageService.send(player, "Updated " + pending.field().label() + ".");
        } catch (IllegalArgumentException exception) {
            messageService.send(player, exception.getMessage());
        }

        menuBuilder.open(player, buildEditorMenu(player, pending.entity()));
    }

    private VortexMenuBuilder.MenuDefinition buildBrowserMenu(Player player, List<EditableSpell> spells) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Spell Forge"), 6);
        builder.button(10, icon(Material.BLAZE_POWDER, "New Offense Spell", List.of("Create a directional projectile damage template.")), context -> {
            EditableSpell spell = EditableSpell.offense(nextSpellId(loadCustomSpells()));
            sessions.put(player.getUniqueId(), new EditorSession(spell));
            context.open(buildEditorMenu(player, spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(11, icon(Material.GHAST_TEAR, "New Heal Spell", List.of("Create a healing template for support spells.")), context -> {
            EditableSpell spell = EditableSpell.heal(nextSpellId(loadCustomSpells()));
            sessions.put(player.getUniqueId(), new EditorSession(spell));
            context.open(buildEditorMenu(player, spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(12, icon(Material.BOOK, "New Utility Spell", List.of("Create a self-targeted message and effect template.")), context -> {
            EditableSpell spell = EditableSpell.utility(nextSpellId(loadCustomSpells()));
            sessions.put(player.getUniqueId(), new EditorSession(spell));
            context.open(buildEditorMenu(player, spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.NETHER_STAR, "Reload From Disk", List.of("Reload custom spells from spells.yml.")), context -> {
            SpellbookService.ReloadResult result = spellbookService.reloadCustomSpells();
            messageService.send(context.player(), "Reloaded custom spells. Loaded " + result.loaded() + ", skipped " + result.skipped() + ".");
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.PAPER, "Forge Note", List.of(
            "Built-in and custom spells are both editable here.",
            "Saving a built-in spell writes an override into spells.yml.",
            "Deleting restores the built-in fallback if one exists."
        )), context -> CompletableFuture.completedFuture(null));

        for (int index = 0; index < Math.min(SPELL_SLOTS.length, spells.size()); index++) {
            EditableSpell spell = spells.get(index);
            int slot = SPELL_SLOTS[index];
            builder.button(slot, iconForSpell(spell), context -> {
                EditableSpell sessionSpell = spell.copy();
                sessions.put(player.getUniqueId(), new EditorSession(sessionSpell));
                context.open(buildEditorMenu(player, sessionSpell));
                return CompletableFuture.completedFuture(null);
            });
        }

        if (spells.isEmpty()) {
            builder.button(31, icon(Material.STRUCTURE_VOID, "No Custom Spells Yet", List.of("Use the buttons above to create your first spell.")), context -> CompletableFuture.completedFuture(null));
        }

        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildEditorMenu(Player player, EditableSpell spell) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Spell Forge: " + spell.displayName), 6);
        builder.button(4, iconForSpell(spell), context -> CompletableFuture.completedFuture(null));

        builder.button(10, icon(Material.NAME_TAG, "Rename Spell", List.of("Current: " + spell.displayName, "Click and type a new name in chat.", "Type 'cancel' to abort.")), context -> {
            promptForChat(context.player(), spell, ChatField.DISPLAY_NAME, "Type a new display name for " + spell.displayName + " in chat. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(11, icon(Material.WRITABLE_BOOK, "Change Spell Id", List.of("Current: " + spell.id, "Click and type a new id in chat.", "Spaces become underscores.")), context -> {
            promptForChat(context.player(), spell, ChatField.SPELL_ID, "Type a new spell id in chat for " + spell.displayName + ". Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(12, icon(spell.enabled ? Material.LIME_DYE : Material.GRAY_DYE, spell.enabled ? "Enabled" : "Disabled", List.of("Click to toggle whether the spell loads.")), context -> {
            spell.enabled = !spell.enabled;
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(13, icon(Material.ARMOR_STAND, "Required Class", List.of("Current: " + (spell.requiredClass == null ? "Any" : spell.requiredClass.displayName()), "Click to cycle class restriction.")), context -> {
            spell.requiredClass = nextClass(spell.requiredClass);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.RED_STAINED_GLASS_PANE, "Min Level -1", List.of("Current: " + spell.minLevel)), context -> {
            spell.minLevel = Math.max(1, spell.minLevel - 1);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(15, icon(Material.GREEN_STAINED_GLASS_PANE, "Min Level +1", List.of("Current: " + spell.minLevel)), context -> {
            spell.minLevel = Math.min(100, spell.minLevel + 1);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.CLOCK, "Cooldown", List.of("Current: " + spell.cooldownMillis + " ms", "Left buttons below adjust by 500 ms.")), context -> CompletableFuture.completedFuture(null));
        builder.button(17, icon(Material.REDSTONE, "Cooldown -500", List.of("Current: " + spell.cooldownMillis + " ms")), context -> {
            spell.cooldownMillis = Math.max(0L, spell.cooldownMillis - 500L);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(18, icon(Material.GLOWSTONE_DUST, "Cooldown +500", List.of("Current: " + spell.cooldownMillis + " ms")), context -> {
            spell.cooldownMillis += 500L;
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });

        builder.button(19, icon(Material.COMPASS, "Targeter", List.of("Current: " + spell.targeterType.label, "Click to cycle targeter type.")), context -> {
            spell.targeterType = nextTargeter(spell.targeterType);
            if (spell.targeterType == EditableTargeterType.SELF) {
                spell.limit = 1;
            }
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(20, icon(Material.RED_STAINED_GLASS_PANE, "Range -2", List.of("Current: " + format(spell.range))), context -> {
            spell.range = Math.max(1.0D, spell.range - 2.0D);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(21, icon(Material.GREEN_STAINED_GLASS_PANE, "Range +2", List.of("Current: " + format(spell.range))), context -> {
            spell.range = Math.min(64.0D, spell.range + 2.0D);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(22, icon(Material.RED_STAINED_GLASS_PANE, "Limit -1", List.of("Current: " + spell.limit, "Only used for monster targeters.")), context -> {
            spell.limit = Math.max(1, spell.limit - 1);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(23, icon(Material.GREEN_STAINED_GLASS_PANE, "Limit +1", List.of("Current: " + spell.limit, "Only used for monster targeters.")), context -> {
            spell.limit = Math.min(8, spell.limit + 1);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(24, icon(Material.LECTERN, "Condition", List.of("Current: " + spell.conditionType.label, "Click to cycle the optional condition.")), context -> {
            spell.conditionType = nextCondition(spell.conditionType);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(25, icon(Material.RED_STAINED_GLASS_PANE, "Condition Value -", List.of(conditionValueLabel(spell))), context -> {
            adjustConditionValue(spell, false);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(26, icon(Material.GREEN_STAINED_GLASS_PANE, "Condition Value +", List.of(conditionValueLabel(spell))), context -> {
            adjustConditionValue(spell, true);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });

        builder.button(28, icon(Material.BREWING_STAND, "Primary Mechanic", List.of("Current: " + spell.mechanicType.label, "Click to cycle damage, heal, or message.")), context -> {
            spell.mechanicType = nextMechanic(spell.mechanicType);
            if (spell.mechanicType == EditableMechanicType.MESSAGE) {
                spell.messageEnabled = true;
            }
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(29, icon(Material.RED_STAINED_GLASS_PANE, "Base -1", List.of("Current: " + format(spell.baseValue))), context -> {
            spell.baseValue = Math.max(0.0D, spell.baseValue - 1.0D);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(30, icon(Material.GREEN_STAINED_GLASS_PANE, "Base +1", List.of("Current: " + format(spell.baseValue))), context -> {
            spell.baseValue += 1.0D;
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(31, icon(Material.ENCHANTING_TABLE, "Scaling Attribute", List.of("Current: " + (spell.scalingAttribute == null ? "None" : NameFormat.friendly(spell.scalingAttribute.name())), "Click to cycle the scaling attribute.")), context -> {
            spell.scalingAttribute = nextAttribute(spell.scalingAttribute);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(32, icon(Material.RED_STAINED_GLASS_PANE, "Scale -0.1", List.of("Current: " + format(spell.scale))), context -> {
            spell.scale = Math.max(0.0D, spell.scale - 0.1D);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(33, icon(Material.GREEN_STAINED_GLASS_PANE, "Scale +0.1", List.of("Current: " + format(spell.scale))), context -> {
            spell.scale += 0.1D;
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(34, icon(Material.PAPER, "Message Text", List.of("Current: " + spell.message, "Click and type a new message in chat.")), context -> {
            promptForChat(context.player(), spell, ChatField.MESSAGE, "Type the spell message for " + spell.displayName + " in chat. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(35, icon(spell.messageEnabled ? Material.WRITABLE_BOOK : Material.BARRIER, spell.messageEnabled ? "Message Enabled" : "Message Disabled", List.of("Click to toggle the send_message mechanic.")), context -> {
            spell.messageEnabled = !spell.messageEnabled;
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });

        builder.button(37, icon(spell.particle == null ? Material.GRAY_DYE : Material.BLAZE_POWDER, "Particle Effect", List.of("Current: " + (spell.particle == null ? "None" : spell.particle.name()), "Click to cycle particle visuals.")), context -> {
            spell.particle = nextParticle(spell.particle);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(38, icon(Material.RED_STAINED_GLASS_PANE, "Particle Count -2", List.of("Current: " + spell.particleCount)), context -> {
            spell.particleCount = Math.max(1, spell.particleCount - 2);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(39, icon(Material.GREEN_STAINED_GLASS_PANE, "Particle Count +2", List.of("Current: " + spell.particleCount)), context -> {
            spell.particleCount = Math.min(128, spell.particleCount + 2);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(40, icon(spell.sound == null ? Material.GRAY_DYE : Material.NOTE_BLOCK, "Sound Effect", List.of("Current: " + (spell.sound == null ? "None" : NameFormat.friendly(soundToken(spell.sound))), "Click to cycle sound effects.")), context -> {
            spell.sound = nextSound(spell.sound);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(41, icon(Material.RED_STAINED_GLASS_PANE, "Volume -0.1", List.of("Current: " + format(spell.volume))), context -> {
            spell.volume = Math.max(0.1F, spell.volume - 0.1F);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(42, icon(Material.GREEN_STAINED_GLASS_PANE, "Volume +0.1", List.of("Current: " + format(spell.volume))), context -> {
            spell.volume = Math.min(3.0F, spell.volume + 0.1F);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(43, icon(Material.RED_STAINED_GLASS_PANE, "Pitch -0.1", List.of("Current: " + format(spell.pitch))), context -> {
            spell.pitch = Math.max(0.1F, spell.pitch - 0.1F);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(44, icon(Material.GREEN_STAINED_GLASS_PANE, "Pitch +0.1", List.of("Current: " + format(spell.pitch))), context -> {
            spell.pitch = Math.min(2.0F, spell.pitch + 0.1F);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });

        builder.button(45, icon(Material.COMPARATOR, "Extra Targeters", List.of(
            "Current: " + targeterSummary(spell),
            "Click to edit extra targeters in chat.",
            "Example: ray:24; nearby_monster:18:2; nearby_player:8; self"
        )), context -> {
            promptForChat(
                context.player(),
                spell,
                ChatField.EXTRA_TARGETERS,
                "Enter extra targeters separated by ';' using self, ray:<range>, nearby_monster:<range>:<limit>, or nearby_player:<range>. Type 'none' to clear or 'cancel' to abort."
            );
            return context.close();
        });
        builder.button(46, icon(Material.CROSSBOW, "Chained Mechanics", List.of(
            "Current: " + chainedMechanicSummary(spell),
            "Click to edit chained mechanics in chat.",
            "Example: damage:mobs:8:intelligence:1.2; heal:players:4:spirit:0.7"
        )), context -> {
            promptForChat(
                context.player(),
                spell,
                ChatField.CHAINED_MECHANICS,
                "Enter chained mechanics separated by ';'. Formats: damage:<scope>:<base>[:<attribute>[:<scale>]], heal:<scope>:<base>[:<attribute>[:<scale>]], send_message:<scope>:<message>. Use scopes any, self, players, mobs. Type 'none' to clear or 'cancel' to abort."
            );
            return context.close();
        });
        builder.button(47, icon(Material.TARGET, "Primary Target Filter", List.of(
            "Current: " + spell.primaryTargetScope.label,
            "Use this when your spell has mixed targeters."
        )), context -> {
            spell.primaryTargetScope = nextScope(spell.primaryTargetScope);
            context.open(buildEditorMenu(context.player(), spell));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(48, icon(Material.PAPER, "Advanced Note", List.of(
            "Extra targeters and chained mechanics are appended",
            "after the primary targeter and primary mechanic."
        )), context -> CompletableFuture.completedFuture(null));

        builder.button(49, icon(Material.EMERALD, "Save Spell", List.of("Write this spell to spells.yml and reload it.")), context -> saveSpell(context.player(), spell).thenRun(() -> openBrowser(context.player())));
        builder.button(50, icon(Material.ARROW, "Back To Browser", List.of("Return to the main spell list.")), context -> {
            sessions.remove(context.player().getUniqueId());
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(51, icon(Material.TNT, "Delete Spell", List.of("Remove this spell from spells.yml immediately.")), context -> deleteSpell(context.player(), spell).thenRun(() -> openBrowser(context.player())));
        return builder.build();
    }

    private LinkedHashMap<String, EditableSpell> loadCustomSpells() {
        LinkedHashMap<String, EditableSpell> spells = new LinkedHashMap<>();
        java.util.Set<String> fileBackedIds = loadFileBackedIds();
        for (SpellbookService.EditorSpellSnapshot snapshot : spellbookService.editorSpellSnapshots()) {
            EditableSpell spell = EditableSpell.fromSnapshot(snapshot, fileBackedIds.contains(snapshot.id()));
            spells.put(spell.id, spell);
        }
        return spells;
    }

    private java.util.Set<String> loadFileBackedIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(spellsFile);
        ConfigurationSection section = configuration.getConfigurationSection("spells");
        if (section == null) {
            return ids;
        }
        for (String key : section.getKeys(false)) {
            ids.add(key.toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private String nextSpellId(Map<String, EditableSpell> spells) {
        int counter = spells.size() + 1;
        String candidate = "custom_spell_" + counter;
        while (spells.containsKey(candidate)) {
            counter++;
            candidate = "custom_spell_" + counter;
        }
        return candidate;
    }

    private ItemStack iconForSpell(EditableSpell spell) {
        List<String> lore = new ArrayList<>();
        lore.add("Id: " + spell.id);
        lore.add("Class: " + (spell.requiredClass == null ? "Any" : spell.requiredClass.displayName()));
        lore.add("Level: " + spell.minLevel + " | Cooldown: " + spell.cooldownMillis + " ms");
        lore.add("Targeter: " + spell.targeterType.label + " | Mechanic: " + spell.mechanicType.label);
        lore.add(spell.builtIn ? "Built-in spell" : "Custom spell");
        lore.add(spell.fileBacked ? "spells.yml entry present" : "Using built-in registry fallback");
        lore.add(spell.enabled ? "Enabled" : "Disabled");
        return icon(spell.iconMaterial(), spell.displayName, lore);
    }

    private CompletableFuture<Void> saveSpell(Player player, EditableSpell spell) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("spell-forge-save-" + player.getUniqueId(), () -> {
            try {
                LinkedHashMap<String, EditableSpell> spells = loadCustomSpells();
                if (!spell.sourceId.equals(spell.id) && spells.containsKey(spell.id)) {
                    throw new IllegalArgumentException("A custom spell with id " + spell.id + " already exists.");
                }
                if (!spell.sourceId.equals(spell.id) && spellbookService.registeredSpellIds().contains(spell.id)) {
                    throw new IllegalArgumentException("Spell id " + spell.id + " is already used by another registered spell.");
                }

                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(spellsFile);
                ConfigurationSection root = configuration.getConfigurationSection("spells");
                if (root == null) {
                    root = configuration.createSection("spells");
                }

                if (!spell.sourceId.equals(spell.id)) {
                    root.set(spell.sourceId, null);
                }

                root.set(spell.id, null);
                ConfigurationSection section = root.createSection(spell.id);
                writeSpellSection(section, spell);
                configuration.save(spellsFile);
                spell.sourceId = spell.id;
                sessions.put(player.getUniqueId(), new EditorSession(spell));
                SpellbookService.ReloadResult result = spellbookService.reloadCustomSpells();
                scheduler.runEntity(player, "spell-forge-save-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Saved " + spell.displayName + ". Loaded " + result.loaded() + " custom spell(s).");
                    future.complete(null);
                });
            } catch (IllegalArgumentException | IOException exception) {
                scheduler.runEntity(player, "spell-forge-save-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to save spell: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private CompletableFuture<Void> deleteSpell(Player player, EditableSpell spell) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("spell-forge-delete-" + player.getUniqueId(), () -> {
            try {
                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(spellsFile);
                ConfigurationSection root = configuration.getConfigurationSection("spells");
                if (root != null) {
                    root.set(spell.sourceId, null);
                }
                configuration.save(spellsFile);
                sessions.remove(player.getUniqueId());
                SpellbookService.ReloadResult result = spellbookService.reloadCustomSpells();
                scheduler.runEntity(player, "spell-forge-delete-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Deleted custom spell " + spell.id + ". Remaining loaded: " + result.loaded());
                    future.complete(null);
                });
            } catch (IOException exception) {
                scheduler.runEntity(player, "spell-forge-delete-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to delete spell: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private void writeSpellSection(ConfigurationSection section, EditableSpell spell) {
        section.set("enabled", spell.enabled);
        section.set("display-name", spell.displayName);
        section.set("cooldown-millis", spell.cooldownMillis);
        section.set("min-level", spell.minLevel);
        section.set("required-classes", spell.requiredClass == null ? List.of() : List.of(spell.requiredClass.commandId()));
        section.set("targeters", spell.targeterMaps());
        section.set("conditions", spell.conditionMaps());
        section.set("mechanics", spell.mechanicMaps());
    }

    private VortexClass nextClass(VortexClass current) {
        if (current == null) {
            return VortexClass.KNIGHT;
        }
        VortexClass[] values = VortexClass.values();
        int next = current.ordinal() + 1;
        return next >= values.length ? null : values[next];
    }

    private EditableTargeterType nextTargeter(EditableTargeterType current) {
        EditableTargeterType[] values = EditableTargeterType.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private EditableConditionType nextCondition(EditableConditionType current) {
        EditableConditionType[] values = EditableConditionType.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private EditableMechanicType nextMechanic(EditableMechanicType current) {
        EditableMechanicType[] values = EditableMechanicType.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private EditableTargetScope nextScope(EditableTargetScope current) {
        EditableTargetScope[] values = EditableTargetScope.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private VortexAttribute nextAttribute(VortexAttribute current) {
        if (current == null) {
            return VortexAttribute.STRENGTH;
        }
        VortexAttribute[] values = VortexAttribute.values();
        int next = current.ordinal() + 1;
        return next >= values.length ? null : values[next];
    }

    private Particle nextParticle(Particle current) {
        if (current == null) {
            return PARTICLE_CYCLE.get(0);
        }
        int index = PARTICLE_CYCLE.indexOf(current);
        if (index < 0 || index == PARTICLE_CYCLE.size() - 1) {
            return null;
        }
        return PARTICLE_CYCLE.get(index + 1);
    }

    private Sound nextSound(Sound current) {
        if (current == null) {
            return SOUND_CYCLE.get(0);
        }
        int index = SOUND_CYCLE.indexOf(current);
        if (index < 0 || index == SOUND_CYCLE.size() - 1) {
            return null;
        }
        return SOUND_CYCLE.get(index + 1);
    }

    private void adjustConditionValue(EditableSpell spell, boolean increase) {
        double delta = switch (spell.conditionType) {
            case CHANCE_PASS -> 0.05D;
            case HAS_HEALTH_BELOW, HAS_HEALTH_ABOVE -> 0.05D;
            default -> 0.0D;
        };
        if (delta == 0.0D) {
            return;
        }
        double updated = spell.conditionValue + (increase ? delta : -delta);
        if (spell.conditionType == EditableConditionType.CHANCE_PASS) {
            spell.conditionValue = Math.max(0.05D, Math.min(1.0D, updated));
            return;
        }
        spell.conditionValue = Math.max(0.05D, Math.min(0.95D, updated));
    }

    private String targeterSummary(EditableSpell spell) {
        if (spell.extraTargeters.isEmpty()) {
            return "Primary only";
        }
        return spell.extraTargeters.size() + " extra: " + spell.extraTargeters.stream().map(EditableTargeter::summary).reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private String chainedMechanicSummary(EditableSpell spell) {
        if (spell.extraMechanics.isEmpty()) {
            return "Primary only";
        }
        return spell.extraMechanics.size() + " chained: " + spell.extraMechanics.stream().map(EditableChainedMechanic::summary).reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private List<EditableTargeter> parseExtraTargeters(String input) {
        if (input.isBlank() || input.equalsIgnoreCase("none")) {
            return new ArrayList<>();
        }

        ArrayList<EditableTargeter> targeters = new ArrayList<>();
        for (String token : input.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            String type = parts[0].trim().toLowerCase(Locale.ROOT);
            switch (type) {
                case "self" -> targeters.add(new EditableTargeter(EditableTargeterType.SELF, 0.0D, 1));
                case "ray", "line", "directional" -> {
                    if (parts.length < 2) {
                        throw new IllegalArgumentException("ray needs a range.");
                    }
                    targeters.add(new EditableTargeter(
                        EditableTargeterType.RAY,
                        parsePositiveDouble(parts[1], "targeter range"),
                        1
                    ));
                }
                case "nearby_monster" -> {
                    if (parts.length < 3) {
                        throw new IllegalArgumentException("nearby_monster needs range and limit.");
                    }
                    targeters.add(new EditableTargeter(
                        EditableTargeterType.NEARBY_MONSTER,
                        parsePositiveDouble(parts[1], "targeter range"),
                        Math.max(1, parsePositiveInt(parts[2], "targeter limit"))
                    ));
                }
                case "nearby_player" -> {
                    if (parts.length < 2) {
                        throw new IllegalArgumentException("nearby_player needs a range.");
                    }
                    targeters.add(new EditableTargeter(
                        EditableTargeterType.NEARBY_PLAYER,
                        parsePositiveDouble(parts[1], "targeter range"),
                        1
                    ));
                }
                default -> throw new IllegalArgumentException("Unknown targeter type: " + type);
            }
        }
        return targeters;
    }

    private List<EditableChainedMechanic> parseChainedMechanics(String input) {
        if (input.isBlank() || input.equalsIgnoreCase("none")) {
            return new ArrayList<>();
        }

        ArrayList<EditableChainedMechanic> mechanics = new ArrayList<>();
        for (String token : input.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Mechanic entries need at least type and scope.");
            }
            String type = parts[0].trim().toLowerCase(Locale.ROOT);
            EditableTargetScope scope = parseTargetScope(parts[1]);
            switch (type) {
                case "damage", "heal" -> {
                    if (parts.length < 3) {
                        throw new IllegalArgumentException(type + " mechanics need a base value.");
                    }
                    VortexAttribute attribute = parts.length >= 4 && !parts[3].isBlank()
                        ? parseAttributeStrict(parts[3], "scaling attribute")
                        : null;
                    double scale = parts.length >= 5 ? parseNonNegativeDouble(parts[4], "mechanic scale") : 0.0D;
                    mechanics.add(new EditableChainedMechanic(
                        type.equals("damage") ? EditableMechanicType.DAMAGE : EditableMechanicType.HEAL,
                        scope,
                        parseNonNegativeDouble(parts[2], "mechanic base"),
                        attribute,
                        scale,
                        null
                    ));
                }
                case "send_message", "message" -> {
                    if (parts.length < 3) {
                        throw new IllegalArgumentException("send_message mechanics need message text.");
                    }
                    String message = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length)).trim();
                    mechanics.add(new EditableChainedMechanic(EditableMechanicType.MESSAGE, scope, 0.0D, null, 0.0D, message));
                }
                default -> throw new IllegalArgumentException("Unknown chained mechanic type: " + type);
            }
        }
        return mechanics;
    }

    private static EditableTargetScope parseTargetScope(String raw) {
        return switch (normalizeToken(raw)) {
            case "", "ANY" -> EditableTargetScope.ANY;
            case "SELF", "CASTER" -> EditableTargetScope.SELF;
            case "PLAYER", "PLAYERS", "ALLY", "ALLIES", "PARTY" -> EditableTargetScope.PLAYERS;
            case "MOB", "MOBS", "MONSTER", "MONSTERS", "ENEMY", "ENEMIES" -> EditableTargetScope.MOBS;
            default -> throw new IllegalArgumentException("Unknown target scope: " + raw);
        };
    }

    private static VortexAttribute parseAttributeStrict(String raw, String label) {
        String normalized = normalizeToken(raw);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return VortexAttribute.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown " + label + ": " + raw);
        }
    }

    private static double parsePositiveDouble(String raw, String label) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (value <= 0.0D) {
                throw new IllegalArgumentException(label + " must be greater than zero.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private static int parsePositiveInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(label + " must be greater than zero.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private static double parseNonNegativeDouble(String raw, String label) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < 0.0D) {
                throw new IllegalArgumentException(label + " cannot be negative.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private static EditableTargeter targeterFromMap(Map<?, ?> entry, EditableTargeterType fallbackType, double fallbackRange, int fallbackLimit) {
        String type = normalizeToken(entry.get("type") == null ? fallbackType.name() : entry.get("type").toString());
        return switch (type) {
            case "SELF" -> new EditableTargeter(EditableTargeterType.SELF, 0.0D, 1);
            case "RAY", "LINE", "LINE_OF_SIGHT", "DIRECTIONAL" -> new EditableTargeter(
                EditableTargeterType.RAY,
                Math.max(1.0D, objectDouble(entry.get("range"), fallbackRange)),
                1
            );
            case "NEARBY_MONSTER", "MONSTER", "AREA_MONSTER" -> new EditableTargeter(
                EditableTargeterType.NEARBY_MONSTER,
                Math.max(1.0D, objectDouble(entry.get("range"), fallbackRange)),
                Math.max(1, objectInt(entry.get("limit"), fallbackLimit))
            );
            case "NEARBY_PLAYER", "PLAYER", "PARTY" -> new EditableTargeter(
                EditableTargeterType.NEARBY_PLAYER,
                Math.max(1.0D, objectDouble(entry.get("range"), fallbackRange)),
                1
            );
            default -> new EditableTargeter(fallbackType, fallbackType == EditableTargeterType.SELF ? 0.0D : fallbackRange, Math.max(1, fallbackLimit));
        };
    }

    private static double objectDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String string && !string.isBlank()) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int objectInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String normalizeToken(String raw) {
        return raw == null ? "" : raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private static String formatStatic(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String conditionValueLabel(EditableSpell spell) {
        return switch (spell.conditionType) {
            case CHANCE_PASS -> "Chance: " + format(spell.conditionValue);
            case HAS_HEALTH_BELOW, HAS_HEALTH_ABOVE -> "Threshold: " + format(spell.conditionValue);
            default -> "No numeric value for this condition.";
        };
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private record EditorSession(EditableSpell spell) {
    }

    enum ChatField {
        DISPLAY_NAME("display name"),
        SPELL_ID("spell id"),
        MESSAGE("message"),
        EXTRA_TARGETERS("extra targeters"),
        CHAINED_MECHANICS("chained mechanics");

        private final String label;

        ChatField(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    private enum EditableTargetScope {
        ANY("Any"),
        SELF("Self"),
        PLAYERS("Players"),
        MOBS("Mobs");

        private final String label;

        EditableTargetScope(String label) {
            this.label = label;
        }
    }

    private enum EditableTargeterType {
        SELF("Self"),
        RAY("Ray / Line"),
        NEARBY_MONSTER("Nearby Monster"),
        NEARBY_PLAYER("Nearby Player");

        private final String label;

        EditableTargeterType(String label) {
            this.label = label;
        }
    }

    private enum EditableConditionType {
        NONE("None"),
        HAS_TARGET("Has Target"),
        CASTER_MOVING("Caster Moving"),
        CASTER_STATIONARY("Caster Stationary"),
        CHANCE_PASS("Chance Pass"),
        HAS_HEALTH_BELOW("Health Below"),
        HAS_HEALTH_ABOVE("Health Above"),
        TARGET_IS_PLAYER("Target Is Player"),
        TARGET_IS_MOB("Target Is Mob"),
        TARGET_IS_SELF("Target Is Self");

        private final String label;

        EditableConditionType(String label) {
            this.label = label;
        }
    }

    private enum EditableMechanicType {
        DAMAGE("Damage"),
        HEAL("Heal"),
        MESSAGE("Message");

        private final String label;

        EditableMechanicType(String label) {
            this.label = label;
        }
    }

    private static final class EditableTargeter {

        private final EditableTargeterType type;
        private final double range;
        private final int limit;

        private EditableTargeter(EditableTargeterType type, double range, int limit) {
            this.type = type;
            this.range = range;
            this.limit = limit;
        }

        private EditableTargeter copy() {
            return new EditableTargeter(type, range, limit);
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            switch (type) {
                case SELF -> map.put("type", "self");
                case RAY -> {
                    map.put("type", "ray");
                    map.put("range", range);
                }
                case NEARBY_MONSTER -> {
                    map.put("type", "nearby_monster");
                    map.put("range", range);
                    map.put("limit", limit);
                }
                case NEARBY_PLAYER -> {
                    map.put("type", "nearby_player");
                    map.put("range", range);
                }
            }
            return map;
        }

        private String summary() {
            return switch (type) {
                case SELF -> "self";
                case RAY -> "ray(" + formatStatic(range) + ")";
                case NEARBY_MONSTER -> "nearby_monster(" + formatStatic(range) + "," + limit + ")";
                case NEARBY_PLAYER -> "nearby_player(" + formatStatic(range) + ")";
            };
        }
    }

    private static final class EditableChainedMechanic {

        private final EditableMechanicType type;
        private final EditableTargetScope scope;
        private final double baseValue;
        private final VortexAttribute scalingAttribute;
        private final double scale;
        private final String message;

        private EditableChainedMechanic(
            EditableMechanicType type,
            EditableTargetScope scope,
            double baseValue,
            VortexAttribute scalingAttribute,
            double scale,
            String message
        ) {
            this.type = type;
            this.scope = scope;
            this.baseValue = baseValue;
            this.scalingAttribute = scalingAttribute;
            this.scale = scale;
            this.message = message;
        }

        private EditableChainedMechanic copy() {
            return new EditableChainedMechanic(type, scope, baseValue, scalingAttribute, scale, message);
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            switch (type) {
                case DAMAGE -> {
                    map.put("type", "damage");
                    map.put("target-filter", scope.name().toLowerCase(Locale.ROOT));
                    map.put("base", baseValue);
                    if (scalingAttribute != null) {
                        map.put("scaling-attribute", scalingAttribute.name().toLowerCase(Locale.ROOT));
                    }
                    map.put("scale", scale);
                }
                case HEAL -> {
                    map.put("type", "heal");
                    map.put("target-filter", scope.name().toLowerCase(Locale.ROOT));
                    map.put("base", baseValue);
                    if (scalingAttribute != null) {
                        map.put("scaling-attribute", scalingAttribute.name().toLowerCase(Locale.ROOT));
                    }
                    map.put("scale", scale);
                }
                case MESSAGE -> {
                    map.put("type", "send_message");
                    map.put("target-filter", scope.name().toLowerCase(Locale.ROOT));
                    map.put("message", message == null ? "" : message);
                }
            }
            return map;
        }

        private String summary() {
            return switch (type) {
                case DAMAGE -> "damage(" + scope.label + "," + formatStatic(baseValue) + ")";
                case HEAL -> "heal(" + scope.label + "," + formatStatic(baseValue) + ")";
                case MESSAGE -> "message(" + scope.label + ")";
            };
        }
    }

    static final class EditableSpell {

        private String sourceId;
        private String id;
        private String displayName;
        private boolean enabled;
        private boolean builtIn;
        private boolean fileBacked;
        private int minLevel;
        private long cooldownMillis;
        private VortexClass requiredClass;
        private EditableTargeterType targeterType;
        private double range;
        private int limit;
        private EditableConditionType conditionType;
        private double conditionValue;
        private EditableMechanicType mechanicType;
        private EditableTargetScope primaryTargetScope;
        private double baseValue;
        private VortexAttribute scalingAttribute;
        private double scale;
        private boolean messageEnabled;
        private String message;
        private Particle particle;
        private int particleCount;
        private Sound sound;
        private float volume;
        private float pitch;
        private List<EditableTargeter> extraTargeters;
        private List<EditableChainedMechanic> extraMechanics;

        private EditableSpell(String id) {
            this.sourceId = id;
            this.id = id;
        }

        private static EditableSpell fromSnapshot(SpellbookService.EditorSpellSnapshot snapshot, boolean fileBacked) {
            EditableSpell spell = new EditableSpell(snapshot.id());
            spell.displayName = snapshot.displayName();
            spell.enabled = true;
            spell.builtIn = snapshot.builtIn();
            spell.fileBacked = fileBacked;
            spell.minLevel = Math.max(1, snapshot.minLevel());
            spell.cooldownMillis = Math.max(0L, snapshot.cooldownMillis());
            if (!snapshot.requiredClasses().isEmpty()) {
                spell.requiredClass = VortexClass.parse(snapshot.requiredClasses().get(0));
            }

            Map<String, Object> targeter = snapshot.targeters().stream().findFirst().orElse(Map.of());
            EditableTargeter primaryTargeter = targeterFromMap(targeter, spell.targeterType == null ? EditableTargeterType.RAY : spell.targeterType, 24.0D, 1);
            spell.targeterType = primaryTargeter.type;
            spell.range = primaryTargeter.range;
            spell.limit = primaryTargeter.limit;
            spell.extraTargeters = new ArrayList<>();
            for (int index = 1; index < snapshot.targeters().size(); index++) {
                spell.extraTargeters.add(targeterFromMap(snapshot.targeters().get(index), EditableTargeterType.RAY, 24.0D, 1));
            }

            spell.conditionType = EditableConditionType.NONE;
            for (Map<?, ?> entry : snapshot.conditions()) {
                switch (normalizeValue(entry.get("type"))) {
                    case "HAS_TARGET" -> spell.conditionType = EditableConditionType.HAS_TARGET;
                    case "CASTER_MOVING" -> spell.conditionType = EditableConditionType.CASTER_MOVING;
                    case "CASTER_STATIONARY" -> spell.conditionType = EditableConditionType.CASTER_STATIONARY;
                    case "CHANCE_PASS" -> {
                        spell.conditionType = EditableConditionType.CHANCE_PASS;
                        spell.conditionValue = numberValue(entry.get("chance"), spell.conditionValue);
                    }
                    case "HAS_HEALTH_BELOW" -> {
                        spell.conditionType = EditableConditionType.HAS_HEALTH_BELOW;
                        spell.conditionValue = numberValue(entry.get("threshold"), spell.conditionValue);
                    }
                    case "HAS_HEALTH_ABOVE" -> {
                        spell.conditionType = EditableConditionType.HAS_HEALTH_ABOVE;
                        spell.conditionValue = numberValue(entry.get("threshold"), spell.conditionValue);
                    }
                    case "TARGET_IS_PLAYER" -> spell.conditionType = EditableConditionType.TARGET_IS_PLAYER;
                    case "TARGET_IS_MOB" -> spell.conditionType = EditableConditionType.TARGET_IS_MOB;
                    case "TARGET_IS_SELF" -> spell.conditionType = EditableConditionType.TARGET_IS_SELF;
                    default -> {
                    }
                }
                if (spell.conditionType != EditableConditionType.NONE) {
                    break;
                }
            }

            spell.messageEnabled = false;
            boolean foundPrimary = false;
            spell.extraMechanics = new ArrayList<>();
            for (Map<?, ?> entry : snapshot.mechanics()) {
                String type = normalizeValue(entry.get("type"));
                EditableTargetScope scope = parseTargetScope(stringValue(entry.get("target-filter"), "any"));
                switch (type) {
                    case "DAMAGE" -> {
                        if (!foundPrimary) {
                            foundPrimary = true;
                            spell.mechanicType = EditableMechanicType.DAMAGE;
                            spell.primaryTargetScope = scope;
                            spell.baseValue = numberValue(entry.get("base"), 0.0D);
                            spell.scalingAttribute = parseAttribute(entry.get("scaling-attribute"), null);
                            spell.scale = numberValue(entry.get("scale"), 0.0D);
                        } else {
                            spell.extraMechanics.add(new EditableChainedMechanic(
                                EditableMechanicType.DAMAGE,
                                scope,
                                numberValue(entry.get("base"), 0.0D),
                                parseAttribute(entry.get("scaling-attribute"), null),
                                numberValue(entry.get("scale"), 0.0D),
                                null
                            ));
                        }
                    }
                    case "HEAL" -> {
                        if (!foundPrimary) {
                            foundPrimary = true;
                            spell.mechanicType = EditableMechanicType.HEAL;
                            spell.primaryTargetScope = scope;
                            spell.baseValue = numberValue(entry.get("base"), 0.0D);
                            spell.scalingAttribute = parseAttribute(entry.get("scaling-attribute"), null);
                            spell.scale = numberValue(entry.get("scale"), 0.0D);
                        } else {
                            spell.extraMechanics.add(new EditableChainedMechanic(
                                EditableMechanicType.HEAL,
                                scope,
                                numberValue(entry.get("base"), 0.0D),
                                parseAttribute(entry.get("scaling-attribute"), null),
                                numberValue(entry.get("scale"), 0.0D),
                                null
                            ));
                        }
                    }
                    case "SEND_MESSAGE" -> {
                        if (!foundPrimary) {
                            foundPrimary = true;
                            spell.mechanicType = EditableMechanicType.MESSAGE;
                            spell.primaryTargetScope = scope;
                            spell.messageEnabled = true;
                            spell.message = stringValue(entry.get("message"), "");
                        } else {
                            spell.extraMechanics.add(new EditableChainedMechanic(
                                EditableMechanicType.MESSAGE,
                                scope,
                                0.0D,
                                null,
                                0.0D,
                                stringValue(entry.get("message"), "")
                            ));
                        }
                    }
                    case "SPAWN_PARTICLES", "SPAWN_PARTICLE" -> {
                        spell.particle = parseParticle(entry.get("particle"), spell.particle);
                        spell.particleCount = Math.max(1, (int) numberValue(entry.get("count"), spell.particleCount <= 0 ? 12 : spell.particleCount));
                    }
                    case "PLAY_SOUND" -> {
                        spell.sound = parseSound(entry.get("sound"), spell.sound);
                        spell.volume = (float) numberValue(entry.get("volume"), spell.volume <= 0.0F ? 1.0F : spell.volume);
                        spell.pitch = (float) numberValue(entry.get("pitch"), spell.pitch <= 0.0F ? 1.0F : spell.pitch);
                    }
                    default -> {
                    }
                }
            }

            if (!foundPrimary) {
                spell.mechanicType = EditableMechanicType.MESSAGE;
                spell.primaryTargetScope = EditableTargetScope.SELF;
                spell.messageEnabled = true;
                spell.message = "A spell surges around you.";
            }
            if (spell.volume <= 0.0F) {
                spell.volume = 1.0F;
            }
            if (spell.pitch <= 0.0F) {
                spell.pitch = 1.0F;
            }
            if (spell.particleCount <= 0) {
                spell.particleCount = 12;
            }
            return spell;
        }

        private static EditableSpell offense(String id) {
            EditableSpell spell = new EditableSpell(id);
            spell.displayName = "New Offense Spell";
            spell.enabled = true;
            spell.minLevel = 1;
            spell.cooldownMillis = 3000L;
            spell.targeterType = EditableTargeterType.RAY;
            spell.range = 24.0D;
            spell.limit = 1;
            spell.conditionType = EditableConditionType.HAS_TARGET;
            spell.conditionValue = 0.75D;
            spell.mechanicType = EditableMechanicType.DAMAGE;
            spell.primaryTargetScope = EditableTargetScope.ANY;
            spell.baseValue = 6.0D;
            spell.scalingAttribute = VortexAttribute.INTELLIGENCE;
            spell.scale = 1.0D;
            spell.messageEnabled = true;
            spell.message = "Power erupts from your focus.";
            spell.particle = Particle.FLAME;
            spell.particleCount = 22;
            spell.sound = Sound.ENTITY_BLAZE_SHOOT;
            spell.volume = 1.0F;
            spell.pitch = 1.1F;
            spell.extraTargeters = new ArrayList<>();
            spell.extraMechanics = new ArrayList<>();
            return spell;
        }

        private static EditableSpell heal(String id) {
            EditableSpell spell = offense(id);
            spell.displayName = "New Heal Spell";
            spell.targeterType = EditableTargeterType.NEARBY_PLAYER;
            spell.mechanicType = EditableMechanicType.HEAL;
            spell.baseValue = 4.0D;
            spell.scalingAttribute = VortexAttribute.SPIRIT;
            spell.message = "Restorative energy answers your call.";
            spell.particle = Particle.HAPPY_VILLAGER;
            spell.sound = Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            return spell;
        }

        private static EditableSpell utility(String id) {
            EditableSpell spell = offense(id);
            spell.displayName = "New Utility Spell";
            spell.targeterType = EditableTargeterType.SELF;
            spell.conditionType = EditableConditionType.NONE;
            spell.mechanicType = EditableMechanicType.MESSAGE;
            spell.baseValue = 0.0D;
            spell.scalingAttribute = null;
            spell.scale = 0.0D;
            spell.message = "A strange force settles around you.";
            spell.particle = Particle.CLOUD;
            spell.sound = Sound.ENTITY_ILLUSIONER_CAST_SPELL;
            return spell;
        }

        private EditableSpell copy() {
            EditableSpell copy = new EditableSpell(sourceId);
            copy.sourceId = sourceId;
            copy.id = id;
            copy.displayName = displayName;
            copy.enabled = enabled;
            copy.builtIn = builtIn;
            copy.fileBacked = fileBacked;
            copy.minLevel = minLevel;
            copy.cooldownMillis = cooldownMillis;
            copy.requiredClass = requiredClass;
            copy.targeterType = targeterType;
            copy.range = range;
            copy.limit = limit;
            copy.conditionType = conditionType;
            copy.conditionValue = conditionValue;
            copy.mechanicType = mechanicType;
            copy.primaryTargetScope = primaryTargetScope;
            copy.baseValue = baseValue;
            copy.scalingAttribute = scalingAttribute;
            copy.scale = scale;
            copy.messageEnabled = messageEnabled;
            copy.message = message;
            copy.particle = particle;
            copy.particleCount = particleCount;
            copy.sound = sound;
            copy.volume = volume;
            copy.pitch = pitch;
            copy.extraTargeters = extraTargeters.stream().map(EditableTargeter::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            copy.extraMechanics = extraMechanics.stream().map(EditableChainedMechanic::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return copy;
        }

        private void setId(String newId) {
            String normalized = newId.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Spell id cannot be blank.");
            }
            this.id = normalized;
        }

        private List<Map<String, Object>> targeterMaps() {
            ArrayList<Map<String, Object>> maps = new ArrayList<>();
            maps.add(new EditableTargeter(targeterType, range, limit).toMap());
            extraTargeters.stream().map(EditableTargeter::toMap).forEach(maps::add);
            return maps;
        }

        private List<Map<String, Object>> conditionMaps() {
            if (conditionType == EditableConditionType.NONE) {
                return List.of();
            }
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            switch (conditionType) {
                case HAS_TARGET -> map.put("type", "has_target");
                case CASTER_MOVING -> map.put("type", "caster_moving");
                case CASTER_STATIONARY -> map.put("type", "caster_stationary");
                case CHANCE_PASS -> {
                    map.put("type", "chance_pass");
                    map.put("chance", conditionValue);
                }
                case HAS_HEALTH_BELOW -> {
                    map.put("type", "has_health_below");
                    map.put("threshold", conditionValue);
                }
                case HAS_HEALTH_ABOVE -> {
                    map.put("type", "has_health_above");
                    map.put("threshold", conditionValue);
                }
                case TARGET_IS_PLAYER -> map.put("type", "target_is_player");
                case TARGET_IS_MOB -> map.put("type", "target_is_mob");
                case TARGET_IS_SELF -> map.put("type", "target_is_self");
                default -> {
                }
            }
            return List.of(map);
        }

        private List<Map<String, Object>> mechanicMaps() {
            List<Map<String, Object>> mechanics = new ArrayList<>();
            LinkedHashMap<String, Object> primary = new LinkedHashMap<>();
            switch (mechanicType) {
                case DAMAGE -> {
                    primary.put("type", "damage");
                    primary.put("target-filter", primaryTargetScope.name().toLowerCase(Locale.ROOT));
                    primary.put("base", baseValue);
                    if (scalingAttribute != null) {
                        primary.put("scaling-attribute", scalingAttribute.name().toLowerCase(Locale.ROOT));
                    }
                    primary.put("scale", scale);
                    mechanics.add(primary);
                }
                case HEAL -> {
                    primary.put("type", "heal");
                    primary.put("target-filter", primaryTargetScope.name().toLowerCase(Locale.ROOT));
                    primary.put("base", baseValue);
                    if (scalingAttribute != null) {
                        primary.put("scaling-attribute", scalingAttribute.name().toLowerCase(Locale.ROOT));
                    }
                    primary.put("scale", scale);
                    mechanics.add(primary);
                }
                case MESSAGE -> {
                    if (messageEnabled && message != null && !message.isBlank()) {
                        primary.put("type", "send_message");
                        primary.put("target-filter", primaryTargetScope.name().toLowerCase(Locale.ROOT));
                        primary.put("message", message);
                        mechanics.add(primary);
                    }
                }
            }

            extraMechanics.stream().map(EditableChainedMechanic::toMap).forEach(mechanics::add);

            if (mechanicType != EditableMechanicType.MESSAGE && messageEnabled && message != null && !message.isBlank()) {
                LinkedHashMap<String, Object> messageMap = new LinkedHashMap<>();
                messageMap.put("type", "send_message");
                messageMap.put("message", message);
                mechanics.add(messageMap);
            }

            if (particle != null) {
                LinkedHashMap<String, Object> particleMap = new LinkedHashMap<>();
                particleMap.put("type", "spawn_particles");
                particleMap.put("particle", particle.name().toLowerCase(Locale.ROOT));
                particleMap.put("count", particleCount);
                particleMap.put("offset-x", 0.25D);
                particleMap.put("offset-y", 0.25D);
                particleMap.put("offset-z", 0.25D);
                particleMap.put("extra", 0.01D);
                mechanics.add(particleMap);
            }

            if (sound != null) {
                LinkedHashMap<String, Object> soundMap = new LinkedHashMap<>();
                soundMap.put("type", "play_sound");
                soundMap.put("sound", soundToken(sound));
                soundMap.put("volume", volume);
                soundMap.put("pitch", pitch);
                mechanics.add(soundMap);
            }
            return mechanics;
        }

        private Material iconMaterial() {
            return switch (mechanicType) {
                case DAMAGE -> Material.BLAZE_POWDER;
                case HEAL -> Material.GHAST_TEAR;
                case MESSAGE -> Material.BOOK;
            };
        }

        private static String normalizeValue(Object raw) {
            return raw == null ? "" : raw.toString().trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        }

        private static double numberValue(Object raw, double fallback) {
            if (raw instanceof Number number) {
                return number.doubleValue();
            }
            if (raw instanceof String string && !string.isBlank()) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        private static String stringValue(Object raw, String fallback) {
            if (raw == null) {
                return fallback;
            }
            String value = raw.toString();
            return value.isBlank() ? fallback : value;
        }

        private static VortexAttribute parseAttribute(Object raw, VortexAttribute fallback) {
            String normalized = normalizeValue(raw);
            if (normalized.isBlank()) {
                return fallback;
            }
            try {
                return VortexAttribute.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }

        private static Particle parseParticle(Object raw, Particle fallback) {
            String normalized = normalizeValue(raw);
            if (normalized.isBlank()) {
                return fallback;
            }
            try {
                return Particle.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }

        @SuppressWarnings("removal")
        private static Sound parseSound(Object raw, Sound fallback) {
            String normalized = normalizeSoundKey(raw);
            if (normalized == null || normalized.isBlank()) {
                return fallback;
            }
            NamespacedKey key = NamespacedKey.fromString(normalized);
            if (key == null) {
                return fallback;
            }
            Sound sound = Registry.SOUNDS.get(key);
            if (sound == null) {
                try {
                    sound = Sound.valueOf(key.getKey().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return sound == null ? fallback : sound;
        }
    }

    private static String normalizeSoundKey(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.isBlank()) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (!normalized.contains(":")) {
            normalized = NamespacedKey.MINECRAFT + ":" + normalized;
        }
        return normalized;
    }

    private static String soundToken(Sound sound) {
        return sound == null ? "" : Registry.SOUNDS.getKeyOrThrow(sound).getKey();
    }
}
