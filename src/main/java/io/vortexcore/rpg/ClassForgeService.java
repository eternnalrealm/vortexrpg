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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClassForgeService extends AbstractForgeService<ClassForgeService.EditableClass, ClassForgeService.ChatField> {

    private static final int[] CLASS_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final ClassSkillService classSkillService;
    private final SpellbookService spellbookService;
    private final File classesFile;

    public ClassForgeService(
        JavaPlugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        ClassSkillService classSkillService,
        SpellbookService spellbookService,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard
    ) {
        super("class-forge", scheduler, menuBuilder, messageService, forgeInputGuard);
        this.classSkillService = classSkillService;
        this.spellbookService = spellbookService;
        this.classesFile = new File(plugin.getDataFolder(), "classes.yml");
    }

    public void openBrowser(Player player) {
        scheduler.runAsync("class-forge-open-" + player.getUniqueId(), () -> {
            List<EditableClass> classes = loadEditableClasses();
            scheduler.runEntity(player, "class-forge-browser-" + player.getUniqueId(),
                () -> menuBuilder.open(player, buildBrowserMenu(classes)));
        });
    }

    @Override
    protected void handleChatInput(Player player, GenericPendingInput<EditableClass, ChatField> pending, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            messageService.send(player, "Class forge input cancelled.");
            menuBuilder.open(player, buildEditorMenu(pending.entity()));
            return;
        }

        try {
            switch (pending.field()) {
                case DISPLAY_NAME -> pending.entity().displayName = input.isBlank() ? pending.entity().displayName : input;
                case CLASS_ID -> pending.entity().setId(input);
                case DESCRIPTION -> pending.entity().description = input.isBlank() ? pending.entity().description : input;
                case ICON -> pending.entity().icon = parseMaterial(input, "class icon");
                case STARTER_SPELL -> pending.entity().starterSpellId = parseStarterSpell(input);
                case BASE_STATS -> pending.entity().baseStats = parseBaseStats(input, pending.entity().baseStats);
            }
            messageService.send(player, "Updated " + pending.field().label + ".");
        } catch (IllegalArgumentException exception) {
            messageService.send(player, exception.getMessage());
        }

        menuBuilder.open(player, buildEditorMenu(pending.entity()));
    }

    private VortexMenuBuilder.MenuDefinition buildBrowserMenu(List<EditableClass> classes) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Class Forge"), 6);
        builder.button(10, icon(Material.NETHER_STAR, "New Class Profile", List.of(
            "Create a new playable class profile.",
            "New profiles inherit the selected archetype's",
            "skills, ascensions, item checks, and kits."
        )), context -> {
            context.open(buildEditorMenu(EditableClass.fresh(nextClassId(classes))));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.BOOK, "Reload From Disk", List.of("Reload class profiles from classes.yml.")), context -> {
            ClassSkillService.ReloadResult result = classSkillService.reloadCustomClasses();
            messageService.send(context.player(), "Reloaded class profiles. Loaded " + result.loaded() + ", skipped " + result.skipped() + ".");
            openBrowser(context.player());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.PAPER, "Forge Notes", List.of(
            "Built-in classes can be overridden here.",
            "New classes are profile variants over an archetype.",
            "Archetype controls skills, kits, and ascensions."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));

        for (int index = 0; index < Math.min(classes.size(), CLASS_SLOTS.length); index++) {
            EditableClass editableClass = classes.get(index);
            builder.button(CLASS_SLOTS[index], iconForClass(editableClass), context -> {
                context.open(buildEditorMenu(editableClass.copy()));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
        }

        if (classes.isEmpty()) {
            builder.button(31, icon(Material.STRUCTURE_VOID, "No Class Profiles", List.of("Create your first class profile above.")),
                context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        }
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildEditorMenu(EditableClass editableClass) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Class Forge: " + editableClass.displayName), 6);
        builder.button(4, iconForClass(editableClass), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(10, icon(Material.NAME_TAG, "Rename Class", List.of("Current: " + editableClass.displayName, "Click and type a new display name in chat.")), context -> {
            promptForChat(context.player(), editableClass, ChatField.DISPLAY_NAME, "Type a new class display name. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(11, icon(Material.WRITABLE_BOOK, "Change Class Id", List.of("Current: " + editableClass.id, "Spaces become underscores.")), context -> {
            promptForChat(context.player(), editableClass, ChatField.CLASS_ID, "Type a new class id. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(12, icon(editableClass.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
            editableClass.enabled ? "Enabled" : "Disabled", List.of("Click to toggle whether this class profile loads.")), context -> {
            editableClass.enabled = !editableClass.enabled;
            context.open(buildEditorMenu(editableClass));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(13, icon(Material.ARMOR_STAND, "Base Archetype", List.of(
            "Current: " + editableClass.archetype.displayName(),
            "Controls skills, ascensions, kit, and item restrictions.",
            "Click to cycle."
        )), context -> {
            editableClass.archetype = nextArchetype(editableClass.archetype);
            context.open(buildEditorMenu(editableClass));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(editableClass.icon, "Icon Material", List.of(
            "Current: " + editableClass.icon.name(),
            "Click and type a Bukkit material in chat."
        )), context -> {
            promptForChat(context.player(), editableClass, ChatField.ICON, "Type a material like BLAZE_ROD or NETHERITE_SWORD. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(15, icon(Material.BLAZE_POWDER, "Starter Spell", List.of(
            "Current: " + editableClass.starterSpellId,
            "This spell becomes active when the class is chosen.",
            "Click and type a spell id in chat."
        )), context -> {
            promptForChat(context.player(), editableClass, ChatField.STARTER_SPELL, "Type a starter spell id. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(19, icon(Material.PAPER, "Description", List.of(
            editableClass.description,
            "Click and type a new description in chat."
        )), context -> {
            promptForChat(context.player(), editableClass, ChatField.DESCRIPTION, "Type a new class description. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(20, icon(Material.COMPARATOR, "Base Stats", statLore(editableClass)), context -> {
            promptForChat(context.player(), editableClass, ChatField.BASE_STATS,
                "Type stats like strength=6; vitality=6; wisdom=1; dexterity=3; intelligence=1; spirit=3. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(21, icon(Material.ENCHANTED_BOOK, "Spell Registry Note", List.of(
            "Current starter spell: " + spellbookService.spellLabel(editableClass.starterSpellId),
            "Starter spell must exist in the spell registry."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(22, icon(Material.BOOK, "Archetype Note", List.of(
            "This class uses the " + editableClass.archetype.displayName() + " archetype.",
            "That means it inherits the same skill tree,",
            "ascensions, kits, and required-class checks."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        builder.button(23, icon(Material.PAPER, "Source", List.of(
            editableClass.builtIn ? "Built-in profile: yes" : "Built-in profile: no",
            editableClass.fileBacked ? "classes.yml override/profile exists." : "No classes.yml entry yet."
        )), context -> java.util.concurrent.CompletableFuture.completedFuture(null));

        builder.button(49, icon(Material.EMERALD, "Save Class", List.of("Write this class profile to classes.yml and reload it.")), context ->
            saveClass(context.player(), editableClass).thenRun(() -> openBrowser(context.player()))
        );
        builder.button(50, icon(Material.ARROW, "Back To Browser", List.of("Return to the class list.")), context -> {
            openBrowser(context.player());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        builder.button(51, icon(Material.TNT, editableClass.fileBacked ? "Delete Override/Profile" : "Clear Override", List.of(
            editableClass.fileBacked
                ? "Remove this class entry from classes.yml."
                : "Remove any future file override and keep the built-in class."
        )), context -> deleteClass(context.player(), editableClass).thenRun(() -> openBrowser(context.player())));
        return builder.build();
    }

    private List<EditableClass> loadEditableClasses() {
        LinkedHashSet<String> fileBackedIds = loadFileBackedIds();
        ArrayList<EditableClass> classes = new ArrayList<>();
        for (ClassProfileDefinition definition : classSkillService.registeredClassProfiles()) {
            classes.add(EditableClass.fromDefinition(definition, fileBackedIds.contains(definition.id())));
        }
        return classes;
    }

    private LinkedHashSet<String> loadFileBackedIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(classesFile);
        ConfigurationSection root = configuration.getConfigurationSection("classes");
        if (root == null) {
            return ids;
        }
        for (String key : root.getKeys(false)) {
            ids.add(normalizeId(key));
        }
        return ids;
    }

    private String nextClassId(List<EditableClass> classes) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        classes.stream().map(editableClass -> editableClass.id).forEach(ids::add);
        int counter = ids.size() + 1;
        String candidate = "custom_class_" + counter;
        while (ids.contains(candidate)) {
            counter++;
            candidate = "custom_class_" + counter;
        }
        return candidate;
    }

    private ItemStack iconForClass(EditableClass editableClass) {
        List<String> lore = new ArrayList<>();
        lore.add("Id: " + editableClass.id);
        lore.add("Archetype: " + editableClass.archetype.displayName());
        lore.add("Starter Spell: " + spellbookService.spellLabel(editableClass.starterSpellId));
        lore.add(editableClass.enabled ? "Enabled" : "Disabled");
        lore.add(editableClass.description);
        editableClass.baseStats.forEach((attribute, value) -> lore.add(attribute.name().toLowerCase(Locale.ROOT) + ": " + format(value)));
        return icon(editableClass.icon, editableClass.displayName, lore);
    }

    private List<String> statLore(EditableClass editableClass) {
        ArrayList<String> lore = new ArrayList<>();
        lore.add("Click and type the full stat map in chat.");
        editableClass.baseStats.forEach((attribute, value) ->
            lore.add(attribute.name().toLowerCase(Locale.ROOT) + " = " + format(value))
        );
        return lore;
    }

    private CompletableFuture<Void> saveClass(Player player, EditableClass editableClass) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("class-forge-save-" + player.getUniqueId(), () -> {
            try {
                if (!spellbookService.registeredSpellIds().contains(editableClass.starterSpellId)) {
                    throw new IllegalArgumentException("Unknown starter spell " + editableClass.starterSpellId + ".");
                }
                if (!editableClass.sourceId.equals(editableClass.id)
                    && classSkillService.classProfile(editableClass.id).isPresent()) {
                    throw new IllegalArgumentException("A class profile with id " + editableClass.id + " already exists.");
                }

                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(classesFile);
                ConfigurationSection root = configuration.getConfigurationSection("classes");
                if (root == null) {
                    root = configuration.createSection("classes");
                }
                if (!editableClass.sourceId.equals(editableClass.id)) {
                    root.set(editableClass.sourceId, null);
                }
                root.set(editableClass.id, null);
                ConfigurationSection section = root.createSection(editableClass.id);
                writeClassSection(section, editableClass);
                configuration.save(classesFile);

                editableClass.sourceId = editableClass.id;
                editableClass.fileBacked = true;
                ClassSkillService.ReloadResult result = classSkillService.reloadCustomClasses();
                scheduler.runEntity(player, "class-forge-save-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Saved class profile " + editableClass.displayName + ". Loaded " + result.loaded() + " custom class profile(s).");
                    future.complete(null);
                });
            } catch (IllegalArgumentException | IOException exception) {
                scheduler.runEntity(player, "class-forge-save-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to save class profile: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private CompletableFuture<Void> deleteClass(Player player, EditableClass editableClass) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("class-forge-delete-" + player.getUniqueId(), () -> {
            try {
                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(classesFile);
                ConfigurationSection root = configuration.getConfigurationSection("classes");
                if (root != null) {
                    root.set(editableClass.sourceId, null);
                }
                configuration.save(classesFile);
                ClassSkillService.ReloadResult result = classSkillService.reloadCustomClasses();
                scheduler.runEntity(player, "class-forge-delete-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Removed class profile override for " + editableClass.id + ". Loaded " + result.loaded() + " custom class profile(s).");
                    future.complete(null);
                });
            } catch (IOException exception) {
                scheduler.runEntity(player, "class-forge-delete-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to delete class profile: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private void writeClassSection(ConfigurationSection section, EditableClass editableClass) {
        section.set("enabled", editableClass.enabled);
        section.set("display-name", editableClass.displayName);
        section.set("description", editableClass.description);
        section.set("base-archetype", editableClass.archetype.commandId());
        section.set("icon", editableClass.icon.name().toLowerCase(Locale.ROOT));
        section.set("starter-spell", editableClass.starterSpellId);
        ConfigurationSection statSection = section.createSection("base-stats");
        editableClass.baseStats.forEach((attribute, value) -> statSection.set(attribute.name().toLowerCase(Locale.ROOT), value));
    }

    private VortexClass nextArchetype(VortexClass current) {
        VortexClass[] values = VortexClass.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private Material parseMaterial(String raw, String label) {
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown " + label + ": " + raw);
        }
    }

    private String parseStarterSpell(String input) {
        String normalized = normalizeId(input);
        if (!spellbookService.registeredSpellIds().contains(normalized)) {
            throw new IllegalArgumentException("Unknown starter spell: " + input);
        }
        return normalized;
    }

    private Map<VortexAttribute, Double> parseBaseStats(String input, Map<VortexAttribute, Double> fallback) {
        EnumMap<VortexAttribute, Double> stats = new EnumMap<>(VortexAttribute.class);
        stats.putAll(fallback);
        for (String token : input.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Stats must look like strength=6; vitality=6; ...");
            }
            String key = parts[0].trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            VortexAttribute attribute;
            try {
                attribute = VortexAttribute.valueOf(key);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown stat " + parts[0].trim() + ".");
            }
            try {
                stats.put(attribute, Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid value for " + parts[0].trim() + ".");
            }
        }
        return Map.copyOf(stats);
    }

    private String normalizeId(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String format(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    enum ChatField {
        DISPLAY_NAME("display name"),
        CLASS_ID("class id"),
        DESCRIPTION("description"),
        ICON("icon material"),
        STARTER_SPELL("starter spell"),
        BASE_STATS("base stats");

        private final String label;

        ChatField(String label) {
            this.label = label;
        }
    }

    static final class EditableClass {

        private String sourceId;
        private String id;
        private String displayName;
        private String description;
        private boolean enabled;
        private boolean builtIn;
        private boolean fileBacked;
        private VortexClass archetype;
        private Material icon;
        private String starterSpellId;
        private Map<VortexAttribute, Double> baseStats;

        private EditableClass(String id) {
            this.sourceId = id;
            this.id = id;
        }

        private static EditableClass fresh(String id) {
            EditableClass editableClass = new EditableClass(id);
            editableClass.displayName = "New Class Profile";
            editableClass.description = "A custom class profile layered over an archetype.";
            editableClass.enabled = true;
            editableClass.builtIn = false;
            editableClass.fileBacked = false;
            editableClass.archetype = VortexClass.KNIGHT;
            editableClass.icon = editableClass.archetype.icon();
            editableClass.starterSpellId = editableClass.archetype.starterSpellId();
            editableClass.baseStats = editableClass.archetype.baseStats();
            return editableClass;
        }

        private static EditableClass fromDefinition(ClassProfileDefinition definition, boolean fileBacked) {
            EditableClass editableClass = new EditableClass(definition.id());
            editableClass.displayName = definition.displayName();
            editableClass.description = definition.description();
            editableClass.enabled = true;
            editableClass.builtIn = definition.builtIn();
            editableClass.fileBacked = fileBacked;
            editableClass.archetype = definition.archetype();
            editableClass.icon = definition.icon();
            editableClass.starterSpellId = definition.starterSpellId();
            editableClass.baseStats = definition.baseStats();
            return editableClass;
        }

        private EditableClass copy() {
            EditableClass copy = new EditableClass(sourceId);
            copy.sourceId = sourceId;
            copy.id = id;
            copy.displayName = displayName;
            copy.description = description;
            copy.enabled = enabled;
            copy.builtIn = builtIn;
            copy.fileBacked = fileBacked;
            copy.archetype = archetype;
            copy.icon = icon;
            copy.starterSpellId = starterSpellId;
            copy.baseStats = Map.copyOf(baseStats);
            return copy;
        }

        private void setId(String newId) {
            String normalized = newId.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Class id cannot be blank.");
            }
            this.id = normalized;
        }
    }
}