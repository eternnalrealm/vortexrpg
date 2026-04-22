package io.vortexcore.rpg.effects;

import io.vortexcore.forge.AbstractForgeService;
import io.vortexcore.forge.ForgeInputGuard;
import io.vortexcore.forge.GenericPendingInput;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

public final class EffectForgeService extends AbstractForgeService<EffectForgeService.EditableEffect, EffectForgeService.ChatField> {

    private static final int[] EFFECT_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    @SuppressWarnings("unused")
    private final JavaPlugin plugin;
    private final EffectService effectService;
    private final File effectsFile;

    public EffectForgeService(
        JavaPlugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        EffectService effectService,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard
    ) {
        super("effect-forge", scheduler, menuBuilder, messageService, forgeInputGuard);
        this.plugin = plugin;
        this.effectService = effectService;
        this.effectsFile = new File(plugin.getDataFolder(), "effects.yml");
    }

    public void openBrowser(Player player) {
        scheduler.runAsync("effect-forge-open-" + player.getUniqueId(), () -> {
            List<EditableEffect> effects = new ArrayList<>(loadCustomEffects().values());
            scheduler.runEntity(player, "effect-forge-browser-" + player.getUniqueId(), () -> menuBuilder.open(player, buildBrowserMenu(effects)));
        });
    }

    @Override
    protected void handleChatInput(Player player, GenericPendingInput<EditableEffect, ChatField> pending, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            messageService.send(player, "Effect forge input cancelled.");
            menuBuilder.open(player, buildEditorMenu(pending.entity()));
            return;
        }

        try {
            switch (pending.field()) {
                case DISPLAY_NAME -> pending.entity().displayName = input.isBlank() ? pending.entity().displayName : input;
                case EFFECT_ID -> pending.entity().setId(input);
                case TAGS -> pending.entity().tags = String.join(",", normalizeTags(input));
                case ON_APPLY -> pending.entity().onApplyText = input;
                case ON_TICK -> pending.entity().onTickText = input;
                case ON_REMOVE -> pending.entity().onRemoveText = input;
            }
            messageService.send(player, "Updated " + pending.field().label + ".");
        } catch (IllegalArgumentException exception) {
            messageService.send(player, exception.getMessage());
        }
        menuBuilder.open(player, buildEditorMenu(pending.entity()));
    }

    private VortexMenuBuilder.MenuDefinition buildBrowserMenu(List<EditableEffect> effects) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Effect Forge"), 6);
        builder.button(10, icon(Material.REDSTONE, "New Harmful Effect", List.of("Create a harmful debuff template.")), context -> {
            context.open(buildEditorMenu(EditableEffect.harmful(nextEffectId(loadCustomEffects()))));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(11, icon(Material.EMERALD, "New Beneficial Effect", List.of("Create a beneficial buff template.")), context -> {
            context.open(buildEditorMenu(EditableEffect.beneficial(nextEffectId(loadCustomEffects()))));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(12, icon(Material.PAPER, "New Neutral Effect", List.of("Create a neutral utility template.")), context -> {
            context.open(buildEditorMenu(EditableEffect.neutral(nextEffectId(loadCustomEffects()))));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.NETHER_STAR, "Reload From Disk", List.of("Reload custom effects from effects.yml.")), context -> {
            EffectService.ReloadResult result = effectService.reloadCustomEffects();
            messageService.send(context.player(), "Reloaded custom effects. Loaded " + result.loaded() + ", skipped " + result.skipped() + ".");
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.PAPER, "Syntax Help", List.of(
            "damage:base[:stack[:potency]]",
            "heal:base[:stack[:potency]]",
            "message:text",
            "particles:type[:count[:ox:oy:oz:extra]]",
            "sound:id[:volume[:pitch]]",
            "Separate operations with ';'"
        )), context -> CompletableFuture.completedFuture(null));

        for (int i = 0; i < Math.min(EFFECT_SLOTS.length, effects.size()); i++) {
            EditableEffect effect = effects.get(i);
            builder.button(EFFECT_SLOTS[i], iconForEffect(effect), context -> {
                context.open(buildEditorMenu(effect.copy()));
                return CompletableFuture.completedFuture(null);
            });
        }

        if (effects.isEmpty()) {
            builder.button(31, icon(Material.STRUCTURE_VOID, "No Custom Effects Yet", List.of("Use the buttons above to create your first effect.")), context -> CompletableFuture.completedFuture(null));
        }
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildEditorMenu(EditableEffect effect) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Effect Forge: " + effect.displayName), 6);
        builder.button(4, iconForEffect(effect), context -> CompletableFuture.completedFuture(null));
        builder.button(10, icon(Material.NAME_TAG, "Rename Effect", List.of("Current: " + effect.displayName, "Type in chat.")), context -> {
            promptForChat(context.player(), effect, ChatField.DISPLAY_NAME, "Type a new display name. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(11, icon(Material.WRITABLE_BOOK, "Change Effect Id", List.of("Current: " + effect.id, "Type in chat.")), context -> {
            promptForChat(context.player(), effect, ChatField.EFFECT_ID, "Type a new effect id. Type 'cancel' to abort.");
            return context.close();
        });
        builder.button(12, icon(effect.enabled ? Material.LIME_DYE : Material.GRAY_DYE, effect.enabled ? "Enabled" : "Disabled", List.of("Click to toggle load state.")), context -> {
            effect.enabled = !effect.enabled;
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(13, icon(Material.COMPASS, "Category", List.of("Current: " + effect.category.label, "Click to cycle.")), context -> {
            effect.category = nextCategory(effect.category);
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(14, icon(Material.RED_STAINED_GLASS_PANE, "Max Stacks -1", List.of("Current: " + effect.maxStacks)), context -> {
            effect.maxStacks = Math.max(1, effect.maxStacks - 1);
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(15, icon(Material.GREEN_STAINED_GLASS_PANE, "Max Stacks +1", List.of("Current: " + effect.maxStacks)), context -> {
            effect.maxStacks = Math.min(20, effect.maxStacks + 1);
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(16, icon(Material.RED_STAINED_GLASS_PANE, "Duration -20t", List.of("Current: " + effect.durationTicks + "t")), context -> {
            effect.durationTicks = Math.max(20, effect.durationTicks - 20);
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(17, icon(Material.GREEN_STAINED_GLASS_PANE, "Duration +20t", List.of("Current: " + effect.durationTicks + "t")), context -> {
            effect.durationTicks += 20;
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(18, icon(Material.RED_STAINED_GLASS_PANE, "Tick Interval -5t", List.of("Current: " + effect.tickIntervalTicks + "t")), context -> {
            effect.tickIntervalTicks = Math.max(1, effect.tickIntervalTicks - 5);
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(19, icon(Material.GREEN_STAINED_GLASS_PANE, "Tick Interval +5t", List.of("Current: " + effect.tickIntervalTicks + "t")), context -> {
            effect.tickIntervalTicks += 5;
            context.open(buildEditorMenu(effect));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(20, icon(Material.BOOK, "Tags", List.of("Current: " + (effect.tags.isBlank() ? "none" : effect.tags), "Comma-separated in chat.")), context -> {
            promptForChat(context.player(), effect, ChatField.TAGS, "Type tags like 'harmful,dot,physical'. Type 'none' to clear.");
            return context.close();
        });
        builder.button(23, icon(Material.BLAZE_POWDER, "On Apply", List.of(truncate(effect.onApplyText))), context -> {
            promptForChat(context.player(), effect, ChatField.ON_APPLY, "Set on-apply operations; use ';' between operations. Type 'none' to clear.");
            return context.close();
        });
        builder.button(24, icon(Material.CLOCK, "On Tick", List.of(truncate(effect.onTickText))), context -> {
            promptForChat(context.player(), effect, ChatField.ON_TICK, "Set on-tick operations; use ';' between operations. Type 'none' to clear.");
            return context.close();
        });
        builder.button(25, icon(Material.BARRIER, "On Remove", List.of(truncate(effect.onRemoveText))), context -> {
            promptForChat(context.player(), effect, ChatField.ON_REMOVE, "Set on-remove operations; use ';' between operations. Type 'none' to clear.");
            return context.close();
        });
        builder.button(49, icon(Material.EMERALD, "Save Effect", List.of("Write this effect to effects.yml and reload.")), context -> saveEffect(context.player(), effect).thenRun(() -> openBrowser(context.player())));
        builder.button(50, icon(Material.ARROW, "Back To Browser", List.of("Return to effect list.")), context -> {
            openBrowser(context.player());
            return CompletableFuture.completedFuture(null);
        });
        builder.button(51, icon(Material.TNT, "Delete Effect", List.of("Remove this effect from effects.yml.")), context -> deleteEffect(context.player(), effect).thenRun(() -> openBrowser(context.player())));
        return builder.build();
    }

    private CompletableFuture<Void> saveEffect(Player player, EditableEffect effect) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("effect-forge-save-" + player.getUniqueId(), () -> {
            try {
                LinkedHashMap<String, EditableEffect> effects = loadCustomEffects();
                if (!effect.sourceId.equals(effect.id) && effects.containsKey(effect.id)) {
                    throw new IllegalArgumentException("A custom effect with id " + effect.id + " already exists.");
                }
                if (!effect.sourceId.equals(effect.id) && effectService.hasRegisteredEffect(effect.id)) {
                    throw new IllegalArgumentException("Effect id " + effect.id + " is already used by another registered effect.");
                }

                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(effectsFile);
                ConfigurationSection root = configuration.getConfigurationSection("effects");
                if (root == null) {
                    root = configuration.createSection("effects");
                }
                if (!effect.sourceId.equals(effect.id)) {
                    root.set(effect.sourceId, null);
                }
                root.set(effect.id, null);
                writeEffectSection(root.createSection(effect.id), effect);
                configuration.save(effectsFile);
                effect.sourceId = effect.id;
                EffectService.ReloadResult result = effectService.reloadCustomEffects();
                scheduler.runEntity(player, "effect-forge-save-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Saved " + effect.displayName + ". Loaded " + result.loaded() + " custom effect(s).");
                    future.complete(null);
                });
            } catch (IllegalArgumentException | IOException exception) {
                scheduler.runEntity(player, "effect-forge-save-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to save effect: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private CompletableFuture<Void> deleteEffect(Player player, EditableEffect effect) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync("effect-forge-delete-" + player.getUniqueId(), () -> {
            try {
                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(effectsFile);
                ConfigurationSection root = configuration.getConfigurationSection("effects");
                if (root != null) {
                    root.set(effect.sourceId, null);
                }
                configuration.save(effectsFile);
                EffectService.ReloadResult result = effectService.reloadCustomEffects();
                scheduler.runEntity(player, "effect-forge-delete-msg-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Deleted effect " + effect.id + ". Remaining loaded: " + result.loaded());
                    future.complete(null);
                });
            } catch (IOException exception) {
                scheduler.runEntity(player, "effect-forge-delete-error-" + player.getUniqueId(), () -> {
                    messageService.send(player, "Unable to delete effect: " + exception.getMessage());
                    future.complete(null);
                });
            }
        });
        return future;
    }

    private void writeEffectSection(ConfigurationSection section, EditableEffect effect) {
        section.set("enabled", effect.enabled);
        section.set("display-name", effect.displayName);
        section.set("category", effect.category.yamlValue);
        section.set("max-stacks", effect.maxStacks);
        section.set("duration-ticks", effect.durationTicks);
        section.set("tick-interval-ticks", effect.tickIntervalTicks);
        section.set("tags", new ArrayList<>(normalizeTags(effect.tags)));
        section.set("on-apply", parseOperationsText(effect.onApplyText));
        section.set("on-tick", parseOperationsText(effect.onTickText));
        section.set("on-remove", parseOperationsText(effect.onRemoveText));
    }

    private LinkedHashMap<String, EditableEffect> loadCustomEffects() {
        LinkedHashMap<String, EditableEffect> effects = new LinkedHashMap<>();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(effectsFile);
        ConfigurationSection root = configuration.getConfigurationSection("effects");
        if (root == null) {
            return effects;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section != null) {
                effects.put(normalizeId(key), EditableEffect.fromSection(key, section));
            }
        }
        return effects;
    }

    private String nextEffectId(Map<String, EditableEffect> effects) {
        int counter = effects.size() + 1;
        String candidate = "custom_effect_" + counter;
        while (effects.containsKey(candidate)) {
            counter++;
            candidate = "custom_effect_" + counter;
        }
        return candidate;
    }

    private EditableCategory nextCategory(EditableCategory current) {
        EditableCategory[] values = EditableCategory.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private ItemStack iconForEffect(EditableEffect effect) {
        return icon(
            effect.category.iconMaterial,
            effect.displayName,
            List.of(
                "Id: " + effect.id,
                "Category: " + effect.category.label + " | Enabled: " + effect.enabled,
                "Stacks: " + effect.maxStacks + " | Duration: " + effect.durationTicks + "t",
                "Tick interval: " + effect.tickIntervalTicks + "t",
                "Tags: " + (effect.tags.isBlank() ? "none" : effect.tags)
            )
        );
    }

    private List<Map<String, Object>> parseOperationsText(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank() || text.equalsIgnoreCase("none")) {
            return List.of();
        }

        ArrayList<Map<String, Object>> operations = new ArrayList<>();
        for (String token : text.split(";")) {
            String op = token.trim();
            if (op.isBlank()) {
                continue;
            }

            int separator = op.indexOf(':');
            String typeToken = separator < 0 ? op : op.substring(0, separator);
            String payload = separator < 0 ? "" : op.substring(separator + 1);
            String type = normalizeId(typeToken);
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            switch (type) {
                case "damage", "heal" -> {
                    String[] parts = payload.split(":");
                    if (parts.length < 1 || parts[0].isBlank()) {
                        throw new IllegalArgumentException(type + " operation needs a base value.");
                    }
                    map.put("type", type);
                    map.put("base", parseDouble(parts[0], "base", true));
                    map.put("stack-scale", parts.length >= 2 && !parts[1].isBlank() ? parseDouble(parts[1], "stack-scale", true) : 0.0D);
                    map.put("potency-scale", parts.length >= 3 && !parts[2].isBlank() ? parseDouble(parts[2], "potency-scale", true) : 0.0D);
                }
                case "message", "send_message" -> {
                    if (payload.isBlank()) {
                        throw new IllegalArgumentException("message operation needs text.");
                    }
                    map.put("type", "send_message");
                    map.put("message", payload);
                }
                case "particles", "spawn_particles", "spawn_particle" -> {
                    String[] parts = payload.split(":");
                    if (parts.length < 1 || parts[0].isBlank()) {
                        throw new IllegalArgumentException("particles operation needs a particle type.");
                    }
                    map.put("type", "spawn_particles");
                    map.put("particle", parseParticle(parts[0]).name().toLowerCase(Locale.ROOT));
                    map.put("count", parts.length >= 2 && !parts[1].isBlank() ? (int) parseDouble(parts[1], "count", false) : 12);
                    map.put("offset-x", parts.length >= 3 && !parts[2].isBlank() ? parseDouble(parts[2], "offset-x", true) : 0.25D);
                    map.put("offset-y", parts.length >= 4 && !parts[3].isBlank() ? parseDouble(parts[3], "offset-y", true) : 0.25D);
                    map.put("offset-z", parts.length >= 5 && !parts[4].isBlank() ? parseDouble(parts[4], "offset-z", true) : 0.25D);
                    map.put("extra", parts.length >= 6 && !parts[5].isBlank() ? parseDouble(parts[5], "extra", true) : 0.01D);
                }
                case "sound", "play_sound" -> {
                    String[] parts = payload.split(":");
                    if (parts.length < 1 || parts[0].isBlank()) {
                        throw new IllegalArgumentException("sound operation needs a sound id.");
                    }
                    map.put("type", "play_sound");
                    map.put("sound", soundToken(parseSound(parts[0])));
                    map.put("volume", parts.length >= 2 && !parts[1].isBlank() ? parseDouble(parts[1], "volume", false) : 1.0D);
                    map.put("pitch", parts.length >= 3 && !parts[2].isBlank() ? parseDouble(parts[2], "pitch", false) : 1.0D);
                }
                default -> throw new IllegalArgumentException("Unknown operation type: " + type);
            }
            operations.add(map);
        }
        return operations;
    }

    private static LinkedHashSet<String> normalizeTags(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = normalizeId(token);
            if (!normalized.isBlank()) {
                tags.add(normalized);
            }
        }
        return tags;
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "none";
        }
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }

    private static String trim(double value) {
        String text = String.format(Locale.US, "%.3f", value);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static double parseDouble(String raw, String label, boolean allowZero) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!allowZero && value <= 0.0D) {
                throw new IllegalArgumentException(label + " must be > 0.");
            }
            if (allowZero && value < 0.0D) {
                throw new IllegalArgumentException(label + " cannot be negative.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
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

    @SuppressWarnings("removal")
    private static Sound parseSound(Object raw) {
        String normalized = normalizeSoundKey(raw);
        if (normalized == null) {
            throw new IllegalArgumentException("Unknown sound: " + raw);
        }
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) {
            throw new IllegalArgumentException("Unknown sound: " + raw);
        }
        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) {
            try {
                sound = Sound.valueOf(key.getKey().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (sound == null) {
            throw new IllegalArgumentException("Unknown sound: " + raw);
        }
        return sound;
    }

    private static Particle parseParticle(Object raw) {
        String token = raw == null ? "" : raw.toString().trim();
        if (token.isBlank()) {
            throw new IllegalArgumentException("Unknown particle: " + raw);
        }
        try {
            return Particle.valueOf(token.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown particle: " + raw);
        }
    }

    private static String soundToken(Sound sound) {
        return Registry.SOUNDS.getKeyOrThrow(sound).getKey();
    }

    private static String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.toString();
        return value.isBlank() ? fallback : value;
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

    enum ChatField {
        DISPLAY_NAME("display name"),
        EFFECT_ID("effect id"),
        TAGS("tags"),
        ON_APPLY("on-apply operations"),
        ON_TICK("on-tick operations"),
        ON_REMOVE("on-remove operations");

        private final String label;

        ChatField(String label) {
            this.label = label;
        }
    }

    private enum EditableCategory {
        BENEFICIAL("beneficial", "Beneficial", Material.EMERALD),
        HARMFUL("harmful", "Harmful", Material.REDSTONE),
        NEUTRAL("neutral", "Neutral", Material.BOOK);

        private final String yamlValue;
        private final String label;
        private final Material iconMaterial;

        EditableCategory(String yamlValue, String label, Material iconMaterial) {
            this.yamlValue = yamlValue;
            this.label = label;
            this.iconMaterial = iconMaterial;
        }

        private static EditableCategory fromRaw(String raw) {
            String normalized = normalizeId(raw);
            return switch (normalized) {
                case "harmful", "debuff", "negative" -> HARMFUL;
                case "beneficial", "buff", "positive" -> BENEFICIAL;
                default -> NEUTRAL;
            };
        }
    }

    static final class EditableEffect {

        private String sourceId;
        private String id;
        private String displayName;
        private boolean enabled;
        private EditableCategory category;
        private int maxStacks;
        private int durationTicks;
        private int tickIntervalTicks;
        private String tags;
        private String onApplyText;
        private String onTickText;
        private String onRemoveText;

        private EditableEffect(String id) {
            this.sourceId = id;
            this.id = id;
        }

        private static EditableEffect harmful(String id) {
            EditableEffect effect = base(id);
            effect.displayName = "New Harmful Effect";
            effect.category = EditableCategory.HARMFUL;
            effect.tags = "harmful,dot";
            effect.onApplyText = "message:{effect} grips you.";
            effect.onTickText = "damage:1.5:0.5:0";
            effect.onRemoveText = "message:{effect} fades.";
            return effect;
        }

        private static EditableEffect beneficial(String id) {
            EditableEffect effect = base(id);
            effect.displayName = "New Beneficial Effect";
            effect.category = EditableCategory.BENEFICIAL;
            effect.tags = "beneficial,ward";
            effect.onApplyText = "sound:entity_player_levelup:0.9:1.2";
            effect.onTickText = "heal:0.8:0.25:0";
            effect.onRemoveText = "message:{effect} ends.";
            return effect;
        }

        private static EditableEffect neutral(String id) {
            EditableEffect effect = base(id);
            effect.displayName = "New Neutral Effect";
            effect.category = EditableCategory.NEUTRAL;
            effect.tags = "neutral";
            effect.onApplyText = "none";
            effect.onTickText = "particles:enchant:10:0.3:0.4:0.3:0.02";
            effect.onRemoveText = "none";
            return effect;
        }

        private static EditableEffect base(String id) {
            EditableEffect effect = new EditableEffect(id);
            effect.enabled = true;
            effect.category = EditableCategory.NEUTRAL;
            effect.maxStacks = 1;
            effect.durationTicks = 100;
            effect.tickIntervalTicks = 20;
            effect.tags = "";
            effect.onApplyText = "none";
            effect.onTickText = "none";
            effect.onRemoveText = "none";
            return effect;
        }

        private static EditableEffect fromSection(String id, ConfigurationSection section) {
            EditableEffect effect = base(id);
            effect.displayName = section.getString("display-name", id);
            effect.enabled = section.getBoolean("enabled", true);
            effect.category = EditableCategory.fromRaw(section.getString("category", "neutral"));
            effect.maxStacks = Math.max(1, section.getInt("max-stacks", 1));
            effect.durationTicks = Math.max(1, section.getInt("duration-ticks", 100));
            effect.tickIntervalTicks = Math.max(1, section.getInt("tick-interval-ticks", 20));
            effect.tags = String.join(",", section.getStringList("tags"));
            effect.onApplyText = encode(section.getMapList("on-apply"));
            effect.onTickText = encode(section.getMapList("on-tick"));
            effect.onRemoveText = encode(section.getMapList("on-remove"));
            return effect;
        }

        private EditableEffect copy() {
            EditableEffect copy = new EditableEffect(sourceId);
            copy.sourceId = sourceId;
            copy.id = id;
            copy.displayName = displayName;
            copy.enabled = enabled;
            copy.category = category;
            copy.maxStacks = maxStacks;
            copy.durationTicks = durationTicks;
            copy.tickIntervalTicks = tickIntervalTicks;
            copy.tags = tags;
            copy.onApplyText = onApplyText;
            copy.onTickText = onTickText;
            copy.onRemoveText = onRemoveText;
            return copy;
        }

        private void setId(String raw) {
            String normalized = normalizeId(raw);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Effect id cannot be blank.");
            }
            this.id = normalized;
        }

        private static String encode(List<Map<?, ?>> maps) {
            if (maps.isEmpty()) {
                return "none";
            }
            ArrayList<String> tokens = new ArrayList<>();
            for (Map<?, ?> map : maps) {
                String type = normalizeId(stringValue(map.get("type"), ""));
                switch (type) {
                    case "damage", "heal" -> tokens.add(type + ":" + trim(numberValue(map.get("base"), 0.0D)) + ":" + trim(numberValue(map.get("stack-scale"), 0.0D)) + ":" + trim(numberValue(map.get("potency-scale"), 0.0D)));
                    case "send_message", "message" -> tokens.add("message:" + stringValue(map.get("message"), ""));
                    case "spawn_particles", "spawn_particle", "particles" -> tokens.add(
                        "particles:" + stringValue(map.get("particle"), "enchant")
                            + ":" + (int) numberValue(map.get("count"), 12)
                            + ":" + trim(numberValue(map.get("offset-x"), 0.25D))
                            + ":" + trim(numberValue(map.get("offset-y"), 0.25D))
                            + ":" + trim(numberValue(map.get("offset-z"), 0.25D))
                            + ":" + trim(numberValue(map.get("extra"), 0.01D))
                    );
                    case "play_sound", "sound" -> tokens.add(
                        "sound:" + stringValue(map.get("sound"), "entity_player_levelup")
                            + ":" + trim(numberValue(map.get("volume"), 1.0D))
                            + ":" + trim(numberValue(map.get("pitch"), 1.0D))
                    );
                    default -> {
                    }
                }
            }
            return tokens.isEmpty() ? "none" : String.join("; ", tokens);
        }
    }
}
