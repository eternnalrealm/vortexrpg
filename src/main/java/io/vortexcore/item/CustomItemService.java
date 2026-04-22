package io.vortexcore.item;

import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.VortexClass;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class CustomItemService {

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey legacyProfessionKey;
    private final NamespacedKey legacyFractureKey;
    private final Map<String, CustomItemDefinition> builtInDefinitions;
    private final Map<String, CustomItemDefinition> definitions;

    public CustomItemService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.itemIdKey = new NamespacedKey(plugin, "vortex_item_id");
        this.legacyProfessionKey = new NamespacedKey(plugin, "profession_item_id");
        this.legacyFractureKey = new NamespacedKey(plugin, "fracture_item_id");
        this.builtInDefinitions = new ConcurrentHashMap<>();
        this.definitions = new ConcurrentHashMap<>();
    }

    public ReloadResult reload() {
        definitions.clear();
        List<String> notes = new ArrayList<>();

        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            builtInDefinitions.values().forEach(definition -> definitions.putIfAbsent(normalize(definition.id()), definition));
            return new ReloadResult(0, List.of("items.yml not found; using built-in registrations only."));
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = configuration.getConfigurationSection("items");
        if (items == null) {
            builtInDefinitions.values().forEach(definition -> definitions.putIfAbsent(normalize(definition.id()), definition));
            return new ReloadResult(0, List.of("items.yml has no items section; using built-in registrations only."));
        }

        int loaded = 0;
        for (String id : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(id);
            if (section == null) {
                continue;
            }

            Material material = Material.matchMaterial(section.getString("material", "PAPER"));
            if (material == null) {
                notes.add("Skipped " + id + " because material is invalid.");
                continue;
            }

            Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
            ConfigurationSection enchantSection = section.getConfigurationSection("enchantments");
            if (enchantSection != null) {
                for (String enchantId : enchantSection.getKeys(false)) {
                    Enchantment enchantment = parseEnchantment(enchantId);
                    if (enchantment == null) {
                        notes.add("Skipped unknown enchantment " + enchantId + " for " + id + ".");
                        continue;
                    }
                    enchantments.put(enchantment, Math.max(1, enchantSection.getInt(enchantId, 1)));
                }
            }

            Map<VortexAttribute, Double> attributeBonuses = parseAttributeSection(
                section.getConfigurationSection("attributes"),
                notes,
                id,
                "attribute"
            );
            Map<VortexAttribute, Double> attributeMultipliers = parseAttributeSection(
                section.getConfigurationSection("attribute-multipliers"),
                notes,
                id,
                "attribute multiplier"
            );

            ConfigurationSection abilitySection = section.getConfigurationSection("ability");
            String requiredClassId = parseRequiredClass(
                abilitySection == null ? section.getString("required-class", "") : abilitySection.getString("required-class", ""),
                notes,
                id
            );
            String normalizedId = normalize(id);
            CustomItemDefinition.AbilityBinding rightClickAbility = CustomItemDefinition.AbilityBinding.none();
            CustomItemDefinition.AbilityBinding leftClickAbility = CustomItemDefinition.AbilityBinding.none();
            if (abilitySection != null) {
                ConfigurationSection rightClickSection = abilitySection.getConfigurationSection("right-click");
                ConfigurationSection leftClickSection = abilitySection.getConfigurationSection("left-click");
                if (rightClickSection == null && leftClickSection == null) {
                    rightClickAbility = parseAbilityBinding(abilitySection);
                } else {
                    rightClickAbility = parseAbilityBinding(rightClickSection);
                    leftClickAbility = parseAbilityBinding(leftClickSection);
                }
            }

            if ("knight_iron_resolve".equals(normalizedId) && leftClickAbility.isConfigured()) {
                leftClickAbility = CustomItemDefinition.AbilityBinding.none();
                notes.add("Cleared left-click binding for knight_iron_resolve so it keeps normal melee attacks.");
            }
            if ("ranger_hunting_bow".equals(normalizedId) && rightClickAbility.isConfigured()) {
                rightClickAbility = CustomItemDefinition.AbilityBinding.none();
                notes.add("Cleared right-click binding for ranger_hunting_bow so it still fires arrows normally.");
            }

            CustomItemDefinition definition = new CustomItemDefinition(
                normalizedId,
                material,
                section.getString("name", NameFormat.friendly(id)),
                section.getStringList("lore"),
                section.contains("custom-model-data") ? section.getInt("custom-model-data") : null,
                section.getBoolean("glow", false),
                parseColor(section.getString("potion-color", "")),
                enchantments,
                attributeBonuses,
                attributeMultipliers,
                requiredClassId,
                rightClickAbility,
                leftClickAbility,
                section.getBoolean("locked", false)
            );
            definitions.put(normalize(definition.id()), definition);
            loaded++;
        }

        builtInDefinitions.values().forEach(definition -> definitions.putIfAbsent(normalize(definition.id()), definition));

        return new ReloadResult(loaded, List.copyOf(notes));
    }

    public void register(CustomItemDefinition definition) {
        definitions.put(normalize(definition.id()), definition);
    }

    public void registerIfAbsent(CustomItemDefinition definition) {
        String normalized = normalize(definition.id());
        builtInDefinitions.putIfAbsent(normalized, definition);
        definitions.putIfAbsent(normalized, definition);
    }

    public List<CustomItemDefinition> definitionsSnapshot() {
        return definitions.values().stream()
            .sorted(java.util.Comparator.comparing(CustomItemDefinition::id))
            .toList();
    }

    public List<String> registeredItemIds() {
        return definitions.keySet().stream().sorted().toList();
    }

    public int registeredItemCount() {
        return definitions.size();
    }

    public Optional<CustomItemDefinition> definition(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(normalize(id)));
    }

    public ItemStack create(String id, int amount) {
        CustomItemDefinition definition = definitions.get(normalize(id));
        ItemStack item = definition == null
            ? fallbackItem(id)
            : build(definition, null, List.of(), Math.max(1, amount));
        item.setAmount(Math.max(1, amount));
        return item;
    }

    public ItemStack createIcon(String id, List<String> extraLore) {
        return createIcon(id, null, extraLore);
    }

    public ItemStack createIcon(String id, String titleOverride, List<String> extraLore) {
        CustomItemDefinition definition = definitions.get(normalize(id));
        if (definition == null) {
            return build(
                new CustomItemDefinition(
                    normalize(id),
                    Material.PAPER,
                    titleOverride == null ? NameFormat.friendly(id) : titleOverride,
                    extraLore == null ? List.of() : extraLore,
                    null,
                    false,
                    null,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    1,
                    0,
                    1.0D,
                    true,
                    0L
                ),
                null,
                List.of(),
                1
            );
        }
        return build(definition, titleOverride, extraLore == null ? List.of() : extraLore, 1);
    }

    public String resolveId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        String modernId = container.get(itemIdKey, PersistentDataType.STRING);
        if (modernId != null && !modernId.isBlank()) {
            return modernId;
        }
        String legacyProfession = container.get(legacyProfessionKey, PersistentDataType.STRING);
        if (legacyProfession != null && !legacyProfession.isBlank()) {
            return legacyProfession;
        }
        String legacyFracture = container.get(legacyFractureKey, PersistentDataType.STRING);
        return legacyFracture == null || legacyFracture.isBlank() ? null : legacyFracture;
    }

    /**
     * Re-applies the current definition's name, lore, and custom-model-data to an
     * existing custom item stack in-place, so players don't keep stale lore/text
     * from an older plugin version. Amount and enchantments are preserved.
     *
     * @return true if the item was refreshed, false if it isn't a known custom item.
     */
    public boolean refresh(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String id = resolveId(item);
        if (id == null) {
            return false;
        }
        CustomItemDefinition definition = definitions.get(normalize(id));
        if (definition == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(parseLoreLine(definition.displayName()).decoration(TextDecoration.ITALIC, false));
        List<String> lore = definition.lore();
        if (lore.isEmpty()) {
            meta.lore(List.of());
        } else {
            meta.lore(lore.stream()
                .map(this::parseLoreLine)
                .map(component -> component.decoration(TextDecoration.ITALIC, false))
                .toList());
        }
        applyCustomModelData(meta, definition.customModelData());
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, normalize(definition.id()));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return true;
    }

    private Component parseLoreLine(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    /** Returns true if this item is a locked VortexRPG custom item (cannot be dropped). */
    public boolean isLocked(ItemStack item) {
        String id = resolveId(item);
        if (id == null) {
            return false;
        }
        return definition(id).map(CustomItemDefinition::locked).orElse(false);
    }

    @SuppressWarnings("null")
    private ItemStack build(CustomItemDefinition definition, String titleOverride, List<String> extraLore, int amount) {
        ItemStack item = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        String rawName = titleOverride == null ? definition.displayName() : titleOverride;
        meta.displayName(parseLoreLine(rawName).decoration(TextDecoration.ITALIC, false));

        List<String> lore = new ArrayList<>(definition.lore());
        lore.addAll(extraLore);
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                .map(this::parseLoreLine)
                .map(component -> component.decoration(TextDecoration.ITALIC, false))
                .toList());
        }

        applyCustomModelData(meta, definition.customModelData());
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, normalize(definition.id()));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);

        if (definition.glow() && definition.enchantments().isEmpty()) {
            item.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
        }
        definition.enchantments().forEach(item::addUnsafeEnchantment);

        ItemMeta updatedMeta = item.getItemMeta();
        if (updatedMeta instanceof PotionMeta potionMeta && definition.potionColor() != null) {
            potionMeta.setColor(definition.potionColor());
            item.setItemMeta(potionMeta);
        }
        return item;
    }

    private ItemStack fallbackItem(String id) {
        CustomItemDefinition fallback = new CustomItemDefinition(
            normalize(id),
            Material.PAPER,
            NameFormat.friendly(id),
            List.of("Prototype custom item."),
            null,
            false,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            1,
            0,
            1.0D,
            true,
            0L
        );
        return build(fallback, null, List.of(), 1);
    }

    @SuppressWarnings("deprecation")
    static void applyCustomModelData(ItemMeta meta, Integer customModelData) {
        if (meta == null || customModelData == null) {
            return;
        }

        meta.setCustomModelData(customModelData);

        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of(customModelData.floatValue()));
        component.setFlags(List.of());
        component.setStrings(List.of());
        component.setColors(List.of());
        meta.setCustomModelDataComponent(component);
    }

    @SuppressWarnings("deprecation")
    private Enchantment parseEnchantment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        for (Enchantment enchantment : Enchantment.values()) {
            NamespacedKey key = enchantment.getKey();
            if (key != null && key.getKey().equalsIgnoreCase(normalized)) {
                return enchantment;
            }
        }
        return null;
    }

    private Color parseColor(String raw) {
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
            logger.warning("Invalid custom item color: " + raw);
        }
        return null;
    }

    private Map<VortexAttribute, Double> parseAttributeSection(
        ConfigurationSection section,
        List<String> notes,
        String itemId,
        String label
    ) {
        if (section == null) {
            return Map.of();
        }
        EnumMap<VortexAttribute, Double> values = new EnumMap<>(VortexAttribute.class);
        for (String key : section.getKeys(false)) {
            VortexAttribute attribute = parseAttribute(key);
            if (attribute == null) {
                notes.add("Skipped unknown " + label + " " + key + " for " + itemId + ".");
                continue;
            }
            values.put(attribute, section.getDouble(key, 0.0D));
        }
        return Map.copyOf(values);
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

    private String parseRequiredClass(String raw, List<String> notes, String itemId) {
        String normalized = normalizeToken(raw);
        if (normalized == null) {
            return null;
        }
        VortexClass vortexClass = VortexClass.parse(normalized);
        if (vortexClass == null) {
            notes.add("Skipped unknown required class " + raw + " for " + itemId + ".");
            return null;
        }
        return vortexClass.commandId();
    }

    private CustomItemDefinition.AbilityBinding parseAbilityBinding(ConfigurationSection section) {
        if (section == null) {
            return CustomItemDefinition.AbilityBinding.none();
        }
        return new CustomItemDefinition.AbilityBinding(
            normalizeToken(section.getString("use-spell", "")),
            section.getBoolean("use-active-spell", false),
            normalizeToken(section.getString("use-effect", "")),
            Math.max(1, section.getInt("effect-stacks", 1)),
            Math.max(0, section.getInt("effect-duration-ticks", 0)),
            Math.max(0.01D, section.getDouble("effect-potency", 1.0D)),
            section.getBoolean("effect-refresh-duration", true),
            Math.max(0L, section.getLong("cooldown-millis", 0L))
        );
    }

    private String normalizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String normalize(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    public record ReloadResult(int loaded, List<String> notes) {
    }
}
