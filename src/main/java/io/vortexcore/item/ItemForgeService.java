package io.vortexcore.item;

import io.vortexcore.forge.AbstractForgeService;
import io.vortexcore.forge.ForgeInputGuard;
import io.vortexcore.forge.GenericPendingInput;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemForgeService extends AbstractForgeService<ItemForgeService.EditableItem, ItemForgeService.ChatField> {

    private static final int[] ITEM_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    @SuppressWarnings("unused")
    private final JavaPlugin plugin;
    private final CustomItemService customItemService;
    private final File itemsFile;

    public ItemForgeService(
        JavaPlugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        CustomItemService customItemService,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard
    ) {
        super("item-forge", scheduler, menuBuilder, messageService, forgeInputGuard);
        this.plugin = plugin;
        this.customItemService = customItemService;
        this.itemsFile = new File(plugin.getDataFolder(), "items.yml");
    }

    public void openBrowser(org.bukkit.entity.Player player) {
        scheduler.runAsync("item-forge-open-" + player.getUniqueId(), () -> {
            LinkedHashMap<String, EditableItem> fileItems = loadCustomItems();
            List<EditableItem> items = customItemService.definitionsSnapshot().stream()
                .map(definition -> EditableItem.fromDefinition(definition, fileItems.containsKey(definition.id())))
                .toList();
            scheduler.runEntity(player, "item-forge-browser-" + player.getUniqueId(), () -> menuBuilder.open(player, buildBrowserMenu(items)));
        });
    }

    @Override
    protected void handleChatInput(org.bukkit.entity.Player player, GenericPendingInput<EditableItem, ChatField> pending, String input) {
        boolean abilityField = isAbilityField(pending.field());
        if (input.equalsIgnoreCase("cancel")) {
            messageService.send(player, "Item forge input cancelled.");
            menuBuilder.open(player, abilityField ? buildAbilityMenu(pending.entity()) : buildEditorMenu(pending.entity()));
            return;
        }

        try {
            switch (pending.field()) {
                case DISPLAY_NAME -> pending.entity().displayName = input.isBlank() ? pending.entity().displayName : input;
                case ITEM_ID -> pending.entity().setId(input);
                case MATERIAL -> pending.entity().material = parseMaterial(input);
                case LORE -> pending.entity().lore = parseLore(input);
                case CUSTOM_MODEL_DATA -> pending.entity().customModelData = parseCustomModelData(input);
                case POTION_COLOR -> pending.entity().potionColor = parsePotionColor(input);
                case ENCHANTMENTS -> pending.entity().enchantments.clear();
                case ATTRIBUTE_BONUSES -> pending.entity().attributeBonuses = parseAttributeMap(input);
                case ATTRIBUTE_MULTIPLIERS -> pending.entity().attributeMultipliers = parseAttributeMap(input);
                case REQUIRED_CLASS -> pending.entity().requiredClassId = parseRequiredClass(input);
                case RIGHT_SPELL -> {
                    String parsed = parseTokenOrNull(input);
                    pending.entity().useSpellId = parsed;
                    if (parsed != null) pending.entity().rightClickUseActiveSpell = false;
                }
                case RIGHT_EFFECT -> pending.entity().useEffectId = parseTokenOrNull(input);
                case RIGHT_COOLDOWN -> pending.entity().useCooldownMillis = parseCooldownMillis(input);
                case RIGHT_EFFECT_PAYLOAD -> applyEffectPayload(pending.entity(), input, false);
                case LEFT_SPELL -> {
                    String parsed = parseTokenOrNull(input);
                    pending.entity().leftClickSpellId = parsed;
                    if (parsed != null) pending.entity().leftClickUseActiveSpell = false;
                }
                case LEFT_EFFECT -> pending.entity().leftClickEffectId = parseTokenOrNull(input);
                case LEFT_COOLDOWN -> pending.entity().leftClickCooldownMillis = parseCooldownMillis(input);
                case LEFT_EFFECT_PAYLOAD -> applyEffectPayload(pending.entity(), input, true);
            }
            if (pending.field() == ChatField.ENCHANTMENTS) {
                pending.entity().enchantments.putAll(parseEnchantments(input));
            }
            messageService.send(player, "Updated " + pending.field().label + ".");
        } catch (IllegalArgumentException exception) {
            messageService.send(player, exception.getMessage());
        }

        menuBuilder.open(player, abilityField ? buildAbilityMenu(pending.entity()) : buildEditorMenu(pending.entity()));
    }

    private VortexMenuBuilder.MenuDefinition buildBrowserMenu(List<EditableItem> items) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Item Forge"), 6);
        builder.button(10, icon(Material.PAPER, "New Utility Item", List.of("Create a generic custom item definition.")), context -> {
            context.open(buildEditorMenu(EditableItem.basic(nextItemId(loadCustomItems()))));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(11, icon(Material.POTION, "New Potion Item", List.of("Create a potion-style item with tint support.")), context -> {
            context.open(buildEditorMenu(EditableItem.potion(nextItemId(loadCustomItems()))));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.NETHER_STAR, "Reload From Disk", List.of("Reload shared custom items from items.yml.")), context -> {
            CustomItemService.ReloadResult result = customItemService.reload();
            messageService.send(context.player(), "Reloaded custom items. Loaded " + result.loaded() + " file override(s).");
            result.notes().stream().limit(5).forEach(note -> messageService.send(context.player(), "- " + note));
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.BOOK, "Forge Notes", List.of(
            "Built-in Vortex items appear here too.",
            "Saving writes an override into items.yml.",
            "Deleting removes the override and restores built-in fallback if one exists."
        )), context -> CompletableFuture.completedFuture(null));

        for (int index = 0; index < Math.min(ITEM_SLOTS.length, items.size()); index++) {
            EditableItem item = items.get(index);
            builder.button(ITEM_SLOTS[index], iconForItem(item), context -> {
                context.open(buildEditorMenu(item.copy()));
                return CompletableFuture.completedFuture(null);
            });
        }

        if (items.isEmpty()) {
            builder.button(31, icon(Material.STRUCTURE_VOID, "No Custom Items Yet", List.of("Use the buttons above to create your first item.")), context -> CompletableFuture.completedFuture(null));
        }
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildEditorMenu(EditableItem item) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Item Forge: " + item.displayName), 6);
        builder.button(4, previewIcon(item), context -> CompletableFuture.completedFuture(null));
        builder.button(10, icon(Material.NAME_TAG, "Rename Item", List.of("Current: " + item.displayName, "Type in chat.")), context -> {
            promptForChat(context.player(), item, ChatField.DISPLAY_NAME, "Type a new item display name. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(11, icon(Material.WRITABLE_BOOK, "Change Item Id", List.of("Current: " + item.id, "Type in chat. Spaces become underscores.")), context -> {
            promptForChat(context.player(), item, ChatField.ITEM_ID, "Type a new item id. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(12, icon(item.material, "Material", List.of("Current: " + item.material.name(), "Click and type a Bukkit material in chat.")), context -> {
            promptForChat(context.player(), item, ChatField.MATERIAL, "Type a Bukkit material name like DIAMOND_SWORD or GOAT_HORN. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(13, icon(Material.BOOK, "Lore", List.of(
            "Current lines: " + item.lore.size(),
            item.lore.isEmpty() ? "No lore yet." : truncate(String.join(" | ", item.lore)),
            "Type lore lines separated by ';' or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.LORE, "Type lore lines separated by ';'. Type 'none' to clear.");
            return context.close();
        });
        builder.button(14, icon(item.glow ? Material.LIME_DYE : Material.GRAY_DYE, item.glow ? "Glow Enabled" : "Glow Disabled", List.of("Click to toggle visual glow.")), context -> {
            item.glow = !item.glow;
            context.open(buildEditorMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(15, icon(Material.ITEM_FRAME, "Custom Model Data", List.of(
            "Current: " + (item.customModelData == null ? "none" : item.customModelData),
            "Type a number in chat or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.CUSTOM_MODEL_DATA, "Type a custom model data number or 'none' to clear.");
            return context.close();
        });
        builder.button(16, icon(item.potionColor == null ? Material.GLASS_BOTTLE : Material.POTION, "Potion Color", List.of(
            "Current: " + formatColor(item.potionColor),
            "Only applies to potion items.",
            "Use #RRGGBB, 'r,g,b', or 'none'."
        )), context -> {
            promptForChat(context.player(), item, ChatField.POTION_COLOR, "Type a potion color like #7A44FF or 122,68,255. Type 'none' to clear.");
            return context.close();
        });
        builder.button(19, icon(Material.ENCHANTED_BOOK, "Enchantments", List.of(
            item.enchantments.isEmpty() ? "Current: none" : "Current: " + summarizeEnchantments(item.enchantments),
            "Format: sharpness=5; unbreaking=3",
            "Type 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.ENCHANTMENTS, "Type enchantments like sharpness=5; unbreaking=3. Type 'none' to clear.");
            return context.close();
        });
        builder.button(20, icon(Material.BLAZE_POWDER, "Flat Attributes", List.of(
            item.attributeBonuses.isEmpty() ? "Current: none" : "Current: " + summarizeAttributes(item.attributeBonuses, false),
            "Format: strength=4; vitality=2.5",
            "Adds direct Vortex stat bonuses."
        )), context -> {
            promptForChat(context.player(), item, ChatField.ATTRIBUTE_BONUSES, "Type flat stat bonuses like strength=4; vitality=2.5. Type 'none' to clear.");
            return context.close();
        });
        builder.button(21, icon(Material.GLOWSTONE_DUST, "Attribute Multipliers", List.of(
            item.attributeMultipliers.isEmpty() ? "Current: none" : "Current: " + summarizeAttributes(item.attributeMultipliers, true),
            "Format: dexterity=0.10; spirit=0.05",
            "0.10 means +10% to that stat."
        )), context -> {
            promptForChat(context.player(), item, ChatField.ATTRIBUTE_MULTIPLIERS, "Type stat multipliers like dexterity=0.10; spirit=0.05. Type 'none' to clear.");
            return context.close();
        });
        builder.button(22, icon(Material.NETHER_STAR, "Required Class", List.of(
            "Current: " + (item.requiredClassId == null ? "none" : NameFormat.friendly(item.requiredClassId)),
            "Restricts passive bonuses and ability use.",
            "Use knight, mage, ranger, cleric, or 'none'."
        )), context -> {
            promptForChat(context.player(), item, ChatField.REQUIRED_CLASS, "Type a required class like mage, ranger, or 'none' to clear.");
            return context.close();
        });
        builder.button(23, icon(Material.WRITABLE_BOOK, "Ability Bindings", List.of(
            "Configure left-click and right-click spell/effect bindings.",
            summarizeAbility(item),
            "Click to open ability editor."
        )), context -> {
            context.open(buildAbilityMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(24, icon(item.locked ? Material.BARRIER : Material.APPLE,
            item.locked ? "Locked (Bound)" : "Unlocked (Droppable)", List.of(
            item.locked ? "Players cannot drop or lose this item on death." : "Players can drop this item normally.",
            "Click to toggle."
        )), context -> {
            item.locked = !item.locked;
            context.open(buildEditorMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(18, icon(Material.PAPER, "Source", List.of(item.fileBacked
            ? "This item currently has an items.yml override."
            : "This is currently a built-in definition without a file override.")), context -> CompletableFuture.completedFuture(null));

        builder.button(49, icon(Material.EMERALD, "Save Item", List.of("Write this item definition to items.yml and reload.")), context ->
            saveItem(context.player(), item).thenRun(() -> openBrowser(context.player()))
        );
        builder.button(50, icon(Material.ARROW, "Back To Browser", List.of("Return to the item list.")), context -> {
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(51, icon(Material.TNT, item.fileBacked ? "Delete Override" : "Clear Override", List.of(
            item.fileBacked
                ? "Remove this item override from items.yml."
                : "Remove any file override and fall back to the built-in definition."
        )), context -> deleteItem(context.player(), item).thenRun(() -> openBrowser(context.player())));
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildAbilityMenu(EditableItem item) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Ability Bindings: " + item.id), 4);

        builder.button(10, icon(Material.ARROW, "RIGHT-CLICK Binding", List.of(
            "Triggers when the player right-clicks with this item.",
            summarizeAbilityBinding(item.useSpellId, item.rightClickUseActiveSpell, item.useEffectId, item.useCooldownMillis)
        )), context -> CompletableFuture.completedFuture(null));
        builder.button(11, icon(item.rightClickUseActiveSpell ? Material.LIME_DYE : Material.GRAY_DYE,
            "Right: Use Active Spell " + (item.rightClickUseActiveSpell ? "[ON]" : "[OFF]"), List.of(
            item.rightClickUseActiveSpell
                ? "Casts the player's currently equipped spell slot."
                : "Disabled — enable to cast the equipped spell.",
            "Click to toggle. Enabling clears any specific spell."
        )), context -> {
            item.rightClickUseActiveSpell = !item.rightClickUseActiveSpell;
            if (item.rightClickUseActiveSpell) item.useSpellId = null;
            context.open(buildAbilityMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(12, icon(Material.BLAZE_ROD, "Right: Specific Spell", List.of(
            "Current: " + (item.useSpellId == null ? "none" : item.useSpellId),
            "Casts a named spell on right-click.",
            "Enabling clears use-active-spell.",
            "Type a spell id or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.RIGHT_SPELL, "Type a right-click spell id or 'none' to clear. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(13, icon(Material.DRAGON_BREATH, "Right: Effect", List.of(
            "Current: " + (item.useEffectId == null ? "none" : item.useEffectId),
            "Applies a custom effect on right-click.",
            "Type an effect id or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.RIGHT_EFFECT, "Type a right-click effect id or 'none'. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(14, icon(Material.CLOCK, "Right: Cooldown", List.of(
            "Current: " + (item.useCooldownMillis <= 0L ? "none" : item.useCooldownMillis + " ms"),
            "Right-click shared cooldown.",
            "Type milliseconds or 'none'."
        )), context -> {
            promptForChat(context.player(), item, ChatField.RIGHT_COOLDOWN, "Type right-click cooldown in ms like 12000, or 'none'. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(15, icon(Material.TIPPED_ARROW, "Right: Effect Payload", List.of(
            "Stacks: " + item.useEffectStacks + "  Duration: " + item.useEffectDurationTicks + " ticks",
            "Potency: " + String.format(Locale.US, "%.2f", item.useEffectPotency) + "  Refresh: " + item.useEffectRefreshDuration,
            "Format: stacks=1; duration=100; potency=1.0; refresh=true"
        )), context -> {
            promptForChat(context.player(), item, ChatField.RIGHT_EFFECT_PAYLOAD,
                "Type right-click effect payload: stacks=1; duration=100; potency=1.0; refresh=true. 'none' to reset. 'cancel' to abort.");
            return context.close();
        });

        builder.button(19, icon(Material.STICK, "LEFT-CLICK Binding", List.of(
            "Triggers when the player left-clicks with this item.",
            summarizeAbilityBinding(item.leftClickSpellId, item.leftClickUseActiveSpell, item.leftClickEffectId, item.leftClickCooldownMillis)
        )), context -> CompletableFuture.completedFuture(null));
        builder.button(20, icon(item.leftClickUseActiveSpell ? Material.LIME_DYE : Material.GRAY_DYE,
            "Left: Use Active Spell " + (item.leftClickUseActiveSpell ? "[ON]" : "[OFF]"), List.of(
            item.leftClickUseActiveSpell
                ? "Casts the player's currently equipped spell slot."
                : "Disabled — enable to cast the equipped spell.",
            "Click to toggle. Enabling clears any specific spell."
        )), context -> {
            item.leftClickUseActiveSpell = !item.leftClickUseActiveSpell;
            if (item.leftClickUseActiveSpell) item.leftClickSpellId = null;
            context.open(buildAbilityMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(21, icon(Material.BLAZE_ROD, "Left: Specific Spell", List.of(
            "Current: " + (item.leftClickSpellId == null ? "none" : item.leftClickSpellId),
            "Casts a named spell on left-click.",
            "Enabling clears use-active-spell.",
            "Type a spell id or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.LEFT_SPELL, "Type a left-click spell id or 'none' to clear. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(22, icon(Material.DRAGON_BREATH, "Left: Effect", List.of(
            "Current: " + (item.leftClickEffectId == null ? "none" : item.leftClickEffectId),
            "Applies a custom effect on left-click.",
            "Type an effect id or 'none' to clear."
        )), context -> {
            promptForChat(context.player(), item, ChatField.LEFT_EFFECT, "Type a left-click effect id or 'none'. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(23, icon(Material.CLOCK, "Left: Cooldown", List.of(
            "Current: " + (item.leftClickCooldownMillis <= 0L ? "none" : item.leftClickCooldownMillis + " ms"),
            "Left-click shared cooldown.",
            "Type milliseconds or 'none'."
        )), context -> {
            promptForChat(context.player(), item, ChatField.LEFT_COOLDOWN, "Type left-click cooldown in ms like 3000, or 'none'. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(24, icon(Material.TIPPED_ARROW, "Left: Effect Payload", List.of(
            "Stacks: " + item.leftClickEffectStacks + "  Duration: " + item.leftClickEffectDurationTicks + " ticks",
            "Potency: " + String.format(Locale.US, "%.2f", item.leftClickEffectPotency) + "  Refresh: " + item.leftClickEffectRefreshDuration,
            "Format: stacks=1; duration=100; potency=1.0; refresh=true"
        )), context -> {
            promptForChat(context.player(), item, ChatField.LEFT_EFFECT_PAYLOAD,
                "Type left-click effect payload: stacks=1; duration=100; potency=1.0; refresh=true. 'none' to reset. 'cancel' to abort.");
            return context.close();
        });

        builder.button(31, icon(Material.ARROW, "Back To Item Editor", List.of("Return to the item editor.")), context -> {
            context.open(buildEditorMenu(item));
            return CompletableFuture.completedFuture(null);
        });
        return builder.build();
    }

    private boolean isAbilityField(ChatField field) {
        return switch (field) {
            case RIGHT_SPELL, RIGHT_EFFECT, RIGHT_COOLDOWN, RIGHT_EFFECT_PAYLOAD,
                 LEFT_SPELL, LEFT_EFFECT, LEFT_COOLDOWN, LEFT_EFFECT_PAYLOAD -> true;
            default -> false;
        };
    }

    private CompletableFuture<Void> saveItem(org.bukkit.entity.Player player, EditableItem item) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("item-forge-save-" + player.getUniqueId(), () -> {
            try {
                LinkedHashMap<String, EditableItem> fileItems = loadCustomItems();
                if (!item.sourceId.equals(item.id)) {
                    if (fileItems.containsKey(item.id)) {
                        throw new IllegalArgumentException("An item override with id " + item.id + " already exists.");
                    }
                    if (customItemService.registeredItemIds().contains(item.id) && !item.sourceId.equals(item.id)) {
                        throw new IllegalArgumentException("Item id " + item.id + " is already used by another registered item.");
                    }
                }

                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(itemsFile);
                ConfigurationSection root = configuration.getConfigurationSection("items");
                if (root == null) {
                    root = configuration.createSection("items");
                }
                if (!item.sourceId.equals(item.id)) {
                    root.set(item.sourceId, null);
                }
                root.set(item.id, null);
                writeItemSection(root.createSection(item.id), item);
                configuration.save(itemsFile);
                item.sourceId = item.id;
                item.fileBacked = true;

                CustomItemService.ReloadResult result = customItemService.reload();
                scheduler.runEntity(player, "item-forge-save-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Saved " + item.displayName + ". Loaded " + result.loaded() + " file override(s).");
                    future.complete(null);
                });
            } catch (IllegalArgumentException | IOException exception) {
                scheduler.runEntity(player, "item-forge-save-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to save item: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private CompletableFuture<Void> deleteItem(org.bukkit.entity.Player player, EditableItem item) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("item-forge-delete-" + player.getUniqueId(), () -> {
            try {
                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(itemsFile);
                ConfigurationSection root = configuration.getConfigurationSection("items");
                if (root != null) {
                    root.set(item.sourceId, null);
                    root.set(item.id, null);
                }
                configuration.save(itemsFile);
                CustomItemService.ReloadResult result = customItemService.reload();
                scheduler.runEntity(player, "item-forge-delete-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player,
                        item.fileBacked
                            ? "Removed item override " + item.id + ". Loaded " + result.loaded() + " file override(s)."
                            : "Cleared any override for " + item.id + ". Built-in fallback remains available if registered."
                    );
                    future.complete(null);
                });
            } catch (IOException exception) {
                scheduler.runEntity(player, "item-forge-delete-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to delete item override: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private void writeItemSection(ConfigurationSection section, EditableItem item) {
        section.set("material", item.material.name());
        section.set("name", item.displayName);
        section.set("lore", new ArrayList<>(item.lore));
        section.set("glow", item.glow);
        if (item.customModelData == null) {
            section.set("custom-model-data", null);
        } else {
            section.set("custom-model-data", item.customModelData);
        }
        if (item.potionColor == null) {
            section.set("potion-color", null);
        } else {
            section.set("potion-color", String.format(Locale.US, "#%02X%02X%02X", item.potionColor.getRed(), item.potionColor.getGreen(), item.potionColor.getBlue()));
        }

        if (item.enchantments.isEmpty()) {
            section.set("enchantments", null);
        } else {
            ConfigurationSection enchantSection = section.createSection("enchantments");
            item.enchantments.forEach((enchantment, level) -> enchantSection.set(enchantment.getKey().getKey(), level));
        }

        writeAttributeSection(section, "attributes", item.attributeBonuses);
        writeAttributeSection(section, "attribute-multipliers", item.attributeMultipliers);

        section.set("locked", item.locked ? Boolean.TRUE : null);

        boolean hasRight = item.rightClickUseActiveSpell || item.useSpellId != null
            || item.useEffectId != null || item.useCooldownMillis > 0L
            || item.useEffectStacks != 1 || item.useEffectDurationTicks > 0
            || Math.abs(item.useEffectPotency - 1.0D) > 0.0001D || !item.useEffectRefreshDuration;
        boolean hasLeft = item.leftClickUseActiveSpell || item.leftClickSpellId != null
            || item.leftClickEffectId != null || item.leftClickCooldownMillis > 0L
            || item.leftClickEffectStacks != 1 || item.leftClickEffectDurationTicks > 0
            || Math.abs(item.leftClickEffectPotency - 1.0D) > 0.0001D || !item.leftClickEffectRefreshDuration;
        boolean hasAbilityData = item.requiredClassId != null || hasRight || hasLeft;
        if (!hasAbilityData) {
            section.set("ability", null);
            return;
        }

        ConfigurationSection abilitySection = section.createSection("ability");
        abilitySection.set("required-class", item.requiredClassId);

        if (hasRight) {
            ConfigurationSection rc = abilitySection.createSection("right-click");
            if (item.rightClickUseActiveSpell) rc.set("use-active-spell", true);
            rc.set("use-spell", item.useSpellId);
            rc.set("use-effect", item.useEffectId);
            if (item.useEffectStacks != 1) rc.set("effect-stacks", item.useEffectStacks);
            if (item.useEffectDurationTicks > 0) rc.set("effect-duration-ticks", item.useEffectDurationTicks);
            if (Math.abs(item.useEffectPotency - 1.0D) > 0.0001D) rc.set("effect-potency", item.useEffectPotency);
            if (!item.useEffectRefreshDuration) rc.set("effect-refresh-duration", false);
            rc.set("cooldown-millis", item.useCooldownMillis <= 0L ? null : item.useCooldownMillis);
        }
        if (hasLeft) {
            ConfigurationSection lc = abilitySection.createSection("left-click");
            if (item.leftClickUseActiveSpell) lc.set("use-active-spell", true);
            lc.set("use-spell", item.leftClickSpellId);
            lc.set("use-effect", item.leftClickEffectId);
            if (item.leftClickEffectStacks != 1) lc.set("effect-stacks", item.leftClickEffectStacks);
            if (item.leftClickEffectDurationTicks > 0) lc.set("effect-duration-ticks", item.leftClickEffectDurationTicks);
            if (Math.abs(item.leftClickEffectPotency - 1.0D) > 0.0001D) lc.set("effect-potency", item.leftClickEffectPotency);
            if (!item.leftClickEffectRefreshDuration) lc.set("effect-refresh-duration", false);
            lc.set("cooldown-millis", item.leftClickCooldownMillis <= 0L ? null : item.leftClickCooldownMillis);
        }
    }

    private LinkedHashMap<String, EditableItem> loadCustomItems() {
        LinkedHashMap<String, EditableItem> items = new LinkedHashMap<>();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(itemsFile);
        ConfigurationSection root = configuration.getConfigurationSection("items");
        if (root == null) {
            return items;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section != null) {
                items.put(normalizeId(key), EditableItem.fromSection(key, section));
            }
        }
        return items;
    }

    private String nextItemId(Map<String, EditableItem> items) {
        int counter = items.size() + 1;
        String candidate = "custom_item_" + counter;
        while (items.containsKey(candidate) || customItemService.registeredItemIds().contains(candidate)) {
            counter++;
            candidate = "custom_item_" + counter;
        }
        return candidate;
    }

    private Material parseMaterial(String raw) {
        Material material = Material.matchMaterial(raw == null ? "" : raw.trim());
        if (material == null) {
            throw new IllegalArgumentException("Unknown material: " + raw);
        }
        return material;
    }

    private List<String> parseLore(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String token : raw.split(";")) {
            String line = token.trim();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private Integer parseCustomModelData(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Custom model data must be a whole number or 'none'.");
        }
    }

    private Color parsePotionColor(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("#") && trimmed.length() == 7) {
                return Color.fromRGB(Integer.parseInt(trimmed.substring(1), 16));
            }
            String[] parts = trimmed.split(",");
            if (parts.length == 3) {
                return Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
                );
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("Potion color must look like #7A44FF or 122,68,255.");
    }

    private Map<Enchantment, Integer> parseEnchantments(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return Map.of();
        }
        LinkedHashMap<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        for (String token : raw.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Enchantments must look like sharpness=5; unbreaking=3.");
            }
            Enchantment enchantment = parseEnchantment(parts[0]);
            if (enchantment == null) {
                throw new IllegalArgumentException("Unknown enchantment: " + parts[0].trim());
            }
            try {
                enchantments.put(enchantment, Math.max(1, Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid enchantment level for " + parts[0].trim() + ".");
            }
        }
        return Map.copyOf(enchantments);
    }

    private Map<VortexAttribute, Double> parseAttributeMap(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return Map.of();
        }
        EnumMap<VortexAttribute, Double> values = new EnumMap<>(VortexAttribute.class);
        for (String token : raw.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Attributes must look like strength=4; vitality=2.5.");
            }
            VortexAttribute attribute = parseAttribute(parts[0]);
            if (attribute == null) {
                throw new IllegalArgumentException("Unknown Vortex attribute: " + parts[0].trim());
            }
            try {
                values.put(attribute, Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid number for " + parts[0].trim() + ".");
            }
        }
        return Map.copyOf(values);
    }

    private String parseRequiredClass(String raw) {
        String token = parseTokenOrNull(raw);
        if (token == null) {
            return null;
        }
        VortexClass vortexClass = VortexClass.parse(token);
        if (vortexClass == null) {
            throw new IllegalArgumentException("Unknown class: " + raw);
        }
        return vortexClass.commandId();
    }

    private String parseTokenOrNull(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        return normalizeId(raw);
    }

    private long parseCooldownMillis(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Cooldown must be a whole number of milliseconds or 'none'.");
        }
    }

    private void applyEffectPayload(EditableItem item, String raw, boolean leftClick) {
        int stacks = 1;
        int durationTicks = 0;
        double potency = 1.0D;
        boolean refreshDuration = true;
        if (raw != null && !raw.isBlank() && !raw.equalsIgnoreCase("none")) {
            for (String token : raw.split(";")) {
                String trimmed = token.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                String[] parts = trimmed.split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Effect payload must look like stacks=1; duration=100; potency=1.0; refresh=true.");
                }
                String key = normalizeId(parts[0]);
                String value = parts[1].trim();
                try {
                    switch (key) {
                        case "stacks" -> stacks = Math.max(1, Integer.parseInt(value));
                        case "duration", "duration_ticks" -> durationTicks = Math.max(0, Integer.parseInt(value));
                        case "potency" -> potency = Math.max(0.01D, Double.parseDouble(value));
                        case "refresh", "refresh_duration" -> refreshDuration = Boolean.parseBoolean(value);
                        default -> throw new IllegalArgumentException("Unknown effect payload key: " + parts[0].trim());
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid number for effect payload key " + parts[0].trim() + ".");
                }
            }
        }
        if (leftClick) {
            item.leftClickEffectStacks = stacks;
            item.leftClickEffectDurationTicks = durationTicks;
            item.leftClickEffectPotency = potency;
            item.leftClickEffectRefreshDuration = refreshDuration;
        } else {
            item.useEffectStacks = stacks;
            item.useEffectDurationTicks = durationTicks;
            item.useEffectPotency = potency;
            item.useEffectRefreshDuration = refreshDuration;
        }
    }

    private ItemStack iconForItem(EditableItem item) {
        return previewIcon(item, List.of(
            "Id: " + item.id,
            "Material: " + item.material.name(),
            "Glow: " + item.glow + " | Model: " + (item.customModelData == null ? "none" : item.customModelData),
            "Potion Color: " + formatColor(item.potionColor),
            "Enchantments: " + (item.enchantments.isEmpty() ? "none" : summarizeEnchantments(item.enchantments)),
            "Flat Bonuses: " + summarizeAttributes(item.attributeBonuses, false),
            "Multipliers: " + summarizeAttributes(item.attributeMultipliers, true),
            "Required Class: " + (item.requiredClassId == null ? "none" : NameFormat.friendly(item.requiredClassId)),
            "Ability: " + summarizeAbility(item),
            "Source: " + (item.fileBacked ? "items.yml override" : "built-in registry")
        ));
    }

    private ItemStack previewIcon(EditableItem item) {
        return previewIcon(item, List.of(
            "Id: " + item.id,
            "Material: " + item.material.name(),
            "Lore Lines: " + item.lore.size(),
            "Glow: " + item.glow,
            "Potion Color: " + formatColor(item.potionColor),
            "Enchantments: " + (item.enchantments.isEmpty() ? "none" : summarizeEnchantments(item.enchantments)),
            "Ability: " + summarizeAbility(item)
        ));
    }

    @SuppressWarnings("null")
    private ItemStack previewIcon(EditableItem item, List<String> extraLore) {
        ItemStack stack = new ItemStack(item.material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(item.displayName));
        List<String> lore = new ArrayList<>(item.lore);
        lore.addAll(extraLore);
        meta.lore(lore.stream().map(Component::text).toList());
        CustomItemService.applyCustomModelData(meta, item.customModelData);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        if (item.glow) {
            stack.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
        }
        item.enchantments.forEach(stack::addUnsafeEnchantment);
        ItemMeta updatedMeta = stack.getItemMeta();
        if (updatedMeta instanceof PotionMeta potionMeta && item.potionColor != null) {
            potionMeta.setColor(item.potionColor);
            stack.setItemMeta(potionMeta);
        }
        return stack;
    }

    private String formatColor(Color color) {
        if (color == null) {
            return "none";
        }
        return String.format(Locale.US, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "none";
        }
        return text.length() > 84 ? text.substring(0, 81) + "..." : text;
    }

    private void writeAttributeSection(ConfigurationSection section, String path, Map<VortexAttribute, Double> attributes) {
        if (attributes.isEmpty()) {
            section.set(path, null);
            return;
        }
        ConfigurationSection attributeSection = section.createSection(path);
        attributes.forEach((attribute, value) -> attributeSection.set(attribute.name().toLowerCase(Locale.ROOT), value));
    }

    private String summarizeEnchantments(Map<Enchantment, Integer> enchantments) {
        return enchantments.entrySet().stream()
            .map(entry -> entry.getKey().getKey().getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + "; " + right)
            .orElse("none");
    }

    private String summarizeAttributes(Map<VortexAttribute, Double> values, boolean percentage) {
        if (values.isEmpty()) {
            return "none";
        }
        return values.entrySet().stream()
            .map(entry -> NameFormat.friendly(entry.getKey().name()) + "=" + (percentage
                ? String.format(Locale.US, "%.0f%%", entry.getValue() * 100.0D)
                : String.format(Locale.US, "%.2f", entry.getValue())))
            .reduce((left, right) -> left + "; " + right)
            .orElse("none");
    }

    private String summarizeAbility(EditableItem item) {
        String rc = summarizeAbilityBinding(item.useSpellId, item.rightClickUseActiveSpell, item.useEffectId, item.useCooldownMillis);
        String lc = summarizeAbilityBinding(item.leftClickSpellId, item.leftClickUseActiveSpell, item.leftClickEffectId, item.leftClickCooldownMillis);
        if (rc.equals("none") && lc.equals("none")) return "none";
        if (lc.equals("none")) return "R: " + rc;
        if (rc.equals("none")) return "L: " + lc;
        return "R: " + rc + "  |  L: " + lc;
    }

    private String summarizeAbilityBinding(String spellId, boolean useActiveSpell, String effectId, long cooldownMillis) {
        ArrayList<String> parts = new ArrayList<>();
        if (useActiveSpell) parts.add("active-spell");
        else if (spellId != null) parts.add("spell=" + spellId);
        if (effectId != null) parts.add("effect=" + effectId);
        if (cooldownMillis > 0L) parts.add("cd=" + cooldownMillis + "ms");
        return parts.isEmpty() ? "none" : String.join("; ", parts);
    }

    private VortexAttribute parseAttribute(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VortexAttribute.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    enum ChatField {
        DISPLAY_NAME("display name"),
        ITEM_ID("item id"),
        MATERIAL("material"),
        LORE("lore"),
        CUSTOM_MODEL_DATA("custom model data"),
        POTION_COLOR("potion color"),
        ENCHANTMENTS("enchantments"),
        ATTRIBUTE_BONUSES("flat attributes"),
        ATTRIBUTE_MULTIPLIERS("attribute multipliers"),
        REQUIRED_CLASS("required class"),
        RIGHT_SPELL("right-click spell"),
        RIGHT_EFFECT("right-click effect"),
        RIGHT_COOLDOWN("right-click cooldown"),
        RIGHT_EFFECT_PAYLOAD("right-click effect payload"),
        LEFT_SPELL("left-click spell"),
        LEFT_EFFECT("left-click effect"),
        LEFT_COOLDOWN("left-click cooldown"),
        LEFT_EFFECT_PAYLOAD("left-click effect payload");

        private final String label;

        ChatField(String label) {
            this.label = label;
        }
    }

    static final class EditableItem {

        private String id;
        private String sourceId;
        private String displayName;
        private Material material;
        private List<String> lore;
        private Integer customModelData;
        private boolean glow;
        private Color potionColor;
        private final Map<Enchantment, Integer> enchantments;
        private Map<VortexAttribute, Double> attributeBonuses;
        private Map<VortexAttribute, Double> attributeMultipliers;
        private String requiredClassId;
        private String useSpellId;
        private String useEffectId;
        private int useEffectStacks;
        private int useEffectDurationTicks;
        private double useEffectPotency;
        private boolean useEffectRefreshDuration;
        private long useCooldownMillis;
        private boolean rightClickUseActiveSpell;
        private String leftClickSpellId;
        private boolean leftClickUseActiveSpell;
        private String leftClickEffectId;
        private int leftClickEffectStacks;
        private int leftClickEffectDurationTicks;
        private double leftClickEffectPotency;
        private boolean leftClickEffectRefreshDuration;
        private long leftClickCooldownMillis;
        private boolean locked;
        private boolean fileBacked;

        private EditableItem(
            String id,
            String sourceId,
            String displayName,
            Material material,
            List<String> lore,
            Integer customModelData,
            boolean glow,
            Color potionColor,
            Map<Enchantment, Integer> enchantments,
            Map<VortexAttribute, Double> attributeBonuses,
            Map<VortexAttribute, Double> attributeMultipliers,
            String requiredClassId,
            String useSpellId,
            String useEffectId,
            int useEffectStacks,
            int useEffectDurationTicks,
            double useEffectPotency,
            boolean useEffectRefreshDuration,
            long useCooldownMillis,
            boolean rightClickUseActiveSpell,
            String leftClickSpellId,
            boolean leftClickUseActiveSpell,
            String leftClickEffectId,
            int leftClickEffectStacks,
            int leftClickEffectDurationTicks,
            double leftClickEffectPotency,
            boolean leftClickEffectRefreshDuration,
            long leftClickCooldownMillis,
            boolean locked,
            boolean fileBacked
        ) {
            this.id = id;
            this.sourceId = sourceId;
            this.displayName = displayName;
            this.material = material;
            this.lore = new ArrayList<>(lore);
            this.customModelData = customModelData;
            this.glow = glow;
            this.potionColor = potionColor;
            this.enchantments = new LinkedHashMap<>(enchantments);
            this.attributeBonuses = new EnumMap<>(VortexAttribute.class);
            this.attributeBonuses.putAll(attributeBonuses);
            this.attributeMultipliers = new EnumMap<>(VortexAttribute.class);
            this.attributeMultipliers.putAll(attributeMultipliers);
            this.requiredClassId = requiredClassId;
            this.useSpellId = useSpellId;
            this.useEffectId = useEffectId;
            this.useEffectStacks = useEffectStacks;
            this.useEffectDurationTicks = useEffectDurationTicks;
            this.useEffectPotency = useEffectPotency;
            this.useEffectRefreshDuration = useEffectRefreshDuration;
            this.useCooldownMillis = useCooldownMillis;
            this.rightClickUseActiveSpell = rightClickUseActiveSpell;
            this.leftClickSpellId = leftClickSpellId;
            this.leftClickUseActiveSpell = leftClickUseActiveSpell;
            this.leftClickEffectId = leftClickEffectId;
            this.leftClickEffectStacks = leftClickEffectStacks;
            this.leftClickEffectDurationTicks = leftClickEffectDurationTicks;
            this.leftClickEffectPotency = leftClickEffectPotency;
            this.leftClickEffectRefreshDuration = leftClickEffectRefreshDuration;
            this.leftClickCooldownMillis = leftClickCooldownMillis;
            this.locked = locked;
            this.fileBacked = fileBacked;
        }

        private static EditableItem fromDefinition(CustomItemDefinition definition, boolean fileBacked) {
            CustomItemDefinition.AbilityBinding rc = definition.rightClickAbility();
            CustomItemDefinition.AbilityBinding lc = definition.leftClickAbility();
            return new EditableItem(
                definition.id(), definition.id(),
                definition.displayName(), definition.material(),
                definition.lore(), definition.customModelData(),
                definition.glow(), definition.potionColor(),
                definition.enchantments(),
                definition.attributeBonuses(), definition.attributeMultipliers(),
                definition.requiredClassId(),
                rc.useSpellId(), rc.useEffectId(),
                rc.useEffectStacks(), rc.useEffectDurationTicks(),
                rc.useEffectPotency(), rc.useEffectRefreshDuration(), rc.useCooldownMillis(),
                rc.useActiveSpell(),
                lc.useSpellId(), lc.useActiveSpell(), lc.useEffectId(),
                lc.useEffectStacks(), lc.useEffectDurationTicks(),
                lc.useEffectPotency(), lc.useEffectRefreshDuration(), lc.useCooldownMillis(),
                definition.locked(),
                fileBacked
            );
        }

        private static EditableItem fromSection(String key, ConfigurationSection section) {
            Material material = Material.matchMaterial(section.getString("material", "PAPER"));
            if (material == null) {
                material = Material.PAPER;
            }
            Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
            ConfigurationSection enchantSection = section.getConfigurationSection("enchantments");
            if (enchantSection != null) {
                for (String enchantId : enchantSection.getKeys(false)) {
                    Enchantment enchantment = parseEnchantment(enchantId);
                    if (enchantment != null) {
                        enchantments.put(enchantment, Math.max(1, enchantSection.getInt(enchantId, 1)));
                    }
                }
            }
            Map<VortexAttribute, Double> attributeBonuses = parseAttributeSection(section.getConfigurationSection("attributes"));
            Map<VortexAttribute, Double> attributeMultipliers = parseAttributeSection(section.getConfigurationSection("attribute-multipliers"));
            ConfigurationSection abilitySection = section.getConfigurationSection("ability");
            String requiredClassId = abilitySection == null
                ? normalizeOptionalId(section.getString("required-class", ""))
                : normalizeOptionalId(abilitySection.getString("required-class", ""));
            ConfigurationSection rcSec = abilitySection == null ? null : abilitySection.getConfigurationSection("right-click");
            ConfigurationSection lcSec = abilitySection == null ? null : abilitySection.getConfigurationSection("left-click");
            // Fall back to old flat ability format as right-click binding
            if (rcSec == null && lcSec == null && abilitySection != null) {
                rcSec = abilitySection;
            }
            return new EditableItem(
                normalizeId(key), normalizeId(key),
                section.getString("name", NameFormat.friendly(key)),
                material,
                section.getStringList("lore"),
                section.contains("custom-model-data") ? section.getInt("custom-model-data") : null,
                section.getBoolean("glow", false),
                parseColor(section.getString("potion-color", "")),
                enchantments, attributeBonuses, attributeMultipliers,
                requiredClassId,
                // right-click binding
                rcSec == null ? null : normalizeOptionalId(rcSec.getString("use-spell", "")),
                rcSec == null ? null : normalizeOptionalId(rcSec.getString("use-effect", "")),
                rcSec == null ? 1 : Math.max(1, rcSec.getInt("effect-stacks", 1)),
                rcSec == null ? 0 : Math.max(0, rcSec.getInt("effect-duration-ticks", 0)),
                rcSec == null ? 1.0D : Math.max(0.01D, rcSec.getDouble("effect-potency", 1.0D)),
                rcSec == null || rcSec.getBoolean("effect-refresh-duration", true),
                rcSec == null ? 0L : Math.max(0L, rcSec.getLong("cooldown-millis", 0L)),
                rcSec != null && rcSec.getBoolean("use-active-spell", false),
                // left-click binding
                lcSec == null ? null : normalizeOptionalId(lcSec.getString("use-spell", "")),
                lcSec != null && lcSec.getBoolean("use-active-spell", false),
                lcSec == null ? null : normalizeOptionalId(lcSec.getString("use-effect", "")),
                lcSec == null ? 1 : Math.max(1, lcSec.getInt("effect-stacks", 1)),
                lcSec == null ? 0 : Math.max(0, lcSec.getInt("effect-duration-ticks", 0)),
                lcSec == null ? 1.0D : Math.max(0.01D, lcSec.getDouble("effect-potency", 1.0D)),
                lcSec == null || lcSec.getBoolean("effect-refresh-duration", true),
                lcSec == null ? 0L : Math.max(0L, lcSec.getLong("cooldown-millis", 0L)),
                section.getBoolean("locked", false),
                true
            );
        }

        private static EditableItem basic(String id) {
            return new EditableItem(
                id, id, NameFormat.friendly(id), Material.PAPER,
                List.of("Prototype custom item."), null, false, null,
                Map.of(), Map.of(), Map.of(), null,
                null, null, 1, 0, 1.0D, true, 0L,
                false, null, false, null, 1, 0, 1.0D, true, 0L, false,
                false
            );
        }

        private static EditableItem potion(String id) {
            return new EditableItem(
                id, id, NameFormat.friendly(id), Material.POTION,
                List.of("Prototype potion item."), null, false, Color.fromRGB(90, 160, 255),
                Map.of(), Map.of(), Map.of(), null,
                null, null, 1, 0, 1.0D, true, 0L,
                false, null, false, null, 1, 0, 1.0D, true, 0L, false,
                false
            );
        }

        private EditableItem copy() {
            return new EditableItem(
                id, sourceId, displayName, material, lore, customModelData,
                glow, potionColor, enchantments,
                attributeBonuses, attributeMultipliers, requiredClassId,
                useSpellId, useEffectId,
                useEffectStacks, useEffectDurationTicks, useEffectPotency, useEffectRefreshDuration, useCooldownMillis,
                rightClickUseActiveSpell,
                leftClickSpellId, leftClickUseActiveSpell, leftClickEffectId,
                leftClickEffectStacks, leftClickEffectDurationTicks, leftClickEffectPotency, leftClickEffectRefreshDuration, leftClickCooldownMillis,
                locked,
                fileBacked
            );
        }

        private void setId(String input) {
            String normalized = normalizeId(input);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Item id cannot be blank.");
            }
            this.id = normalized;
        }

        private static Map<VortexAttribute, Double> parseAttributeSection(ConfigurationSection section) {
            if (section == null) {
                return Map.of();
            }
            EnumMap<VortexAttribute, Double> values = new EnumMap<>(VortexAttribute.class);
            for (String key : section.getKeys(false)) {
                try {
                    VortexAttribute attribute = VortexAttribute.valueOf(key.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
                    values.put(attribute, section.getDouble(key, 0.0D));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return Map.copyOf(values);
        }

        private static String normalizeOptionalId(String raw) {
            String normalized = normalizeId(raw);
            return normalized.isBlank() ? null : normalized;
        }
    }

    @SuppressWarnings("deprecation")
    private static Enchantment parseEnchantment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        for (Enchantment enchantment : Enchantment.values()) {
            if (enchantment.getKey().getKey().equalsIgnoreCase(normalized)) {
                return enchantment;
            }
        }
        return null;
    }

    private static Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("#") && trimmed.length() == 7) {
                return Color.fromRGB(Integer.parseInt(trimmed.substring(1), 16));
            }
            String[] parts = trimmed.split(",");
            if (parts.length == 3) {
                return Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
                );
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

}
