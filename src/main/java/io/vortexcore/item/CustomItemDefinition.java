package io.vortexcore.item;

import io.vortexcore.progression.VortexAttribute;
import java.util.List;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

public record CustomItemDefinition(
    String id,
    Material material,
    String displayName,
    List<String> lore,
    Integer customModelData,
    boolean glow,
    Color potionColor,
    Map<Enchantment, Integer> enchantments,
    Map<VortexAttribute, Double> attributeBonuses,
    Map<VortexAttribute, Double> attributeMultipliers,
    String requiredClassId,
    AbilityBinding rightClickAbility,
    AbilityBinding leftClickAbility,
    boolean locked
) {

    public CustomItemDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        attributeBonuses = attributeBonuses == null ? Map.of() : Map.copyOf(attributeBonuses);
        attributeMultipliers = attributeMultipliers == null ? Map.of() : Map.copyOf(attributeMultipliers);
        requiredClassId = normalize(requiredClassId);
        rightClickAbility = rightClickAbility == null ? AbilityBinding.none() : rightClickAbility;
        leftClickAbility = leftClickAbility == null ? AbilityBinding.none() : leftClickAbility;
    }

    public CustomItemDefinition(
        String id,
        Material material,
        String displayName,
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
        long useCooldownMillis
    ) {
        this(
            id,
            material,
            displayName,
            lore,
            customModelData,
            glow,
            potionColor,
            enchantments,
            attributeBonuses,
            attributeMultipliers,
            requiredClassId,
            new AbilityBinding(useSpellId, false, useEffectId, useEffectStacks, useEffectDurationTicks, useEffectPotency, useEffectRefreshDuration, useCooldownMillis),
            AbilityBinding.none(),
            false
        );
    }

    public CustomItemDefinition(
        String id,
        Material material,
        String displayName,
        List<String> lore,
        Integer customModelData,
        boolean glow,
        Color potionColor,
        Map<Enchantment, Integer> enchantments
    ) {
        this(
            id,
            material,
            displayName,
            lore,
            customModelData,
            glow,
            potionColor,
            enchantments,
            Map.of(),
            Map.of(),
            null,
            AbilityBinding.none(),
            AbilityBinding.none(),
            false
        );
    }

    public boolean hasPassiveBonuses() {
        return !attributeBonuses.isEmpty() || !attributeMultipliers.isEmpty();
    }

    public boolean hasAbilityHook() {
        return rightClickAbility.isConfigured() || leftClickAbility.isConfigured();
    }

    public AbilityBinding ability(ActivationTrigger trigger) {
        return trigger == ActivationTrigger.LEFT_CLICK ? leftClickAbility : rightClickAbility;
    }

    public String useSpellId() {
        return rightClickAbility.useSpellId();
    }

    public boolean useActiveSpell() {
        return rightClickAbility.useActiveSpell();
    }

    public String useEffectId() {
        return rightClickAbility.useEffectId();
    }

    public int useEffectStacks() {
        return rightClickAbility.useEffectStacks();
    }

    public int useEffectDurationTicks() {
        return rightClickAbility.useEffectDurationTicks();
    }

    public double useEffectPotency() {
        return rightClickAbility.useEffectPotency();
    }

    public boolean useEffectRefreshDuration() {
        return rightClickAbility.useEffectRefreshDuration();
    }

    public long useCooldownMillis() {
        return rightClickAbility.useCooldownMillis();
    }

    private static String normalize(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase();
    }

    public enum ActivationTrigger {
        LEFT_CLICK,
        RIGHT_CLICK
    }

    public record AbilityBinding(
        String useSpellId,
        boolean useActiveSpell,
        String useEffectId,
        int useEffectStacks,
        int useEffectDurationTicks,
        double useEffectPotency,
        boolean useEffectRefreshDuration,
        long useCooldownMillis
    ) {

        public AbilityBinding {
            useSpellId = normalize(useSpellId);
            useEffectId = normalize(useEffectId);
            useEffectStacks = Math.max(1, useEffectStacks);
            useEffectDurationTicks = Math.max(0, useEffectDurationTicks);
            useEffectPotency = Math.max(0.01D, useEffectPotency);
            useCooldownMillis = Math.max(0L, useCooldownMillis);
        }

        public static AbilityBinding none() {
            return new AbilityBinding(null, false, null, 1, 0, 1.0D, true, 0L);
        }

        public boolean isConfigured() {
            return useActiveSpell || useSpellId != null || useEffectId != null;
        }
    }
}
