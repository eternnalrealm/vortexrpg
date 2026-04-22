package io.vortexcore.rpg.effects;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.magic.SpellContext;
import io.vortexcore.magic.SpellTarget;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class EffectService implements Listener {

    @SuppressWarnings("unused")
    private final JavaPlugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final MessageService messageService;
    private final long tickPeriodTicks;
    private final File effectsFile;
    private final ConcurrentMap<String, VortexEffectType> effects;
    private final ConcurrentMap<UUID, ConcurrentMap<String, ActiveEffect>> activeEffects;

    private VortexTask tickTask;

    public EffectService(JavaPlugin plugin, Logger logger, FoliaExecutionFacade scheduler, MessageService messageService, long tickPeriodTicks) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.messageService = messageService;
        this.tickPeriodTicks = Math.max(1L, tickPeriodTicks);
        this.effectsFile = new File(plugin.getDataFolder(), "effects.yml");
        this.effects = new ConcurrentHashMap<>();
        this.activeEffects = new ConcurrentHashMap<>();
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = scheduler.runGlobalTimer("vortex-effects-tick", tickPeriodTicks, tickPeriodTicks, ignored -> tick());
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        activeEffects.clear();
    }

    public Collection<String> registeredEffectIds() {
        return List.copyOf(effects.keySet());
    }

    public boolean hasRegisteredEffect(String effectId) {
        return effects.containsKey(normalizeId(effectId));
    }

    public ReloadResult reloadCustomEffects() {
        effects.clear();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(effectsFile);
        ConfigurationSection effectsSection = configuration.getConfigurationSection("effects");
        if (effectsSection == null) {
            return new ReloadResult(0, 0, List.of("No effects section found in effects.yml."));
        }

        int loaded = 0;
        int skipped = 0;
        ArrayList<String> messages = new ArrayList<>();

        for (String rawId : effectsSection.getKeys(false)) {
            ConfigurationSection section = effectsSection.getConfigurationSection(rawId);
            if (section == null) {
                skipped++;
                messages.add(rawId + ": entry is not a section.");
                continue;
            }
            if (!section.getBoolean("enabled", true)) {
                skipped++;
                continue;
            }

            try {
                VortexEffectType effect = parseEffect(rawId, section);
                effects.put(effect.id(), effect);
                loaded++;
            } catch (IllegalArgumentException exception) {
                skipped++;
                String message = rawId + ": " + exception.getMessage();
                messages.add(message);
                logger.warning("Unable to load custom effect " + rawId + ": " + exception.getMessage());
            }
        }

        logger.info("Loaded " + loaded + " custom effect(s) from effects.yml. Skipped " + skipped + ".");
        return new ReloadResult(loaded, skipped, List.copyOf(messages));
    }

    public CompletableFuture<Void> applyEffect(
        SpellContext context,
        SpellTarget target,
        String effectId,
        int stacks,
        int durationTicks,
        double potency,
        boolean refreshDuration
    ) {
        LivingEntity entity = resolveTarget(context, target);
        if (entity == null) {
            return CompletableFuture.completedFuture(null);
        }

        UUID sourceEntityId = context.triggerSource() instanceof Entity sourceEntity ? sourceEntity.getUniqueId() : null;
        return applyEffectInternal(entity, sourceEntityId, effectId, stacks, durationTicks, potency, refreshDuration);
    }

    public CompletableFuture<Void> applyEffectToEntity(
        LivingEntity entity,
        String effectId,
        int stacks,
        int durationTicks,
        double potency,
        boolean refreshDuration
    ) {
        if (entity == null) {
            return CompletableFuture.completedFuture(null);
        }
        return applyEffectInternal(entity, null, effectId, stacks, durationTicks, potency, refreshDuration);
    }

    private CompletableFuture<Void> applyEffectInternal(
        LivingEntity entity,
        UUID sourceEntityId,
        String effectId,
        int stacks,
        int durationTicks,
        double potency,
        boolean refreshDuration
    ) {
        String normalizedId = normalizeId(effectId);
        VortexEffectType definition = effects.get(normalizedId);
        if (definition == null) {
            return failedFuture(new IllegalStateException("Unknown custom effect: " + effectId));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean accepted = scheduler.runEntity(entity, "effect-apply-" + normalizedId + "-" + entity.getUniqueId(), () -> {
            ConcurrentMap<String, ActiveEffect> entityEffects = activeEffects.computeIfAbsent(entity.getUniqueId(), ignored -> new ConcurrentHashMap<>());
            ActiveEffect active = entityEffects.get(normalizedId);
            int resolvedDuration = durationTicks > 0 ? durationTicks : definition.defaultDurationTicks();
            int addedStacks = Math.max(1, stacks);
            double resolvedPotency = Math.max(0.01D, potency);

            if (active == null) {
                active = new ActiveEffect(
                    definition,
                    Math.min(definition.maxStacks(), addedStacks),
                    resolvedDuration,
                    resolvedPotency,
                    sourceEntityId
                );
                entityEffects.put(normalizedId, active);
            } else {
                active.stacks = Math.min(definition.maxStacks(), active.stacks + addedStacks);
                if (refreshDuration) {
                    active.remainingTicks = Math.max(active.remainingTicks, resolvedDuration);
                }
                active.potency = Math.max(active.potency, resolvedPotency);
                if (sourceEntityId != null) {
                    active.sourceEntityId = sourceEntityId;
                }
            }

            runOperations(entity, active, active.definition.onApply());
            future.complete(null);
        }, () -> {
            activeEffects.remove(entity.getUniqueId());
            future.complete(null);
        });
        if (!accepted) {
            return CompletableFuture.completedFuture(null);
        }
        return future;
    }

    public CompletableFuture<Void> removeEffect(SpellContext context, SpellTarget target, String effectId, int stacksToRemove) {
        LivingEntity entity = resolveTarget(context, target);
        if (entity == null) {
            return CompletableFuture.completedFuture(null);
        }

        String normalizedId = normalizeId(effectId);
        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean accepted = scheduler.runEntity(entity, "effect-remove-" + normalizedId + "-" + entity.getUniqueId(), () -> {
            ConcurrentMap<String, ActiveEffect> entityEffects = activeEffects.get(entity.getUniqueId());
            if (entityEffects == null) {
                future.complete(null);
                return;
            }

            ActiveEffect active = entityEffects.get(normalizedId);
            if (active == null) {
                future.complete(null);
                return;
            }

            int removedStacks = stacksToRemove <= 0 ? active.stacks : Math.min(active.stacks, stacksToRemove);
            active.stacks -= removedStacks;
            if (active.stacks <= 0) {
                runOperations(entity, active, active.definition.onRemove());
                entityEffects.remove(normalizedId);
                if (entityEffects.isEmpty()) {
                    activeEffects.remove(entity.getUniqueId());
                }
            }
            future.complete(null);
        }, () -> {
            activeEffects.remove(entity.getUniqueId());
            future.complete(null);
        });
        if (!accepted) {
            return CompletableFuture.completedFuture(null);
        }
        return future;
    }

    public CompletableFuture<Void> cleanseEffects(SpellContext context, SpellTarget target, Set<String> tags, int maxRemovals) {
        LivingEntity entity = resolveTarget(context, target);
        if (entity == null) {
            return CompletableFuture.completedFuture(null);
        }

        int limit = maxRemovals <= 0 ? Integer.MAX_VALUE : maxRemovals;
        Set<String> normalizedTags = tags.stream().map(this::normalizeId).filter(token -> !token.isBlank()).collect(java.util.stream.Collectors.toSet());

        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean accepted = scheduler.runEntity(entity, "effect-cleanse-" + entity.getUniqueId(), () -> {
            ConcurrentMap<String, ActiveEffect> entityEffects = activeEffects.get(entity.getUniqueId());
            if (entityEffects == null || entityEffects.isEmpty()) {
                future.complete(null);
                return;
            }

            int removed = 0;
            ArrayList<String> ids = new ArrayList<>(entityEffects.keySet());
            for (String id : ids) {
                if (removed >= limit) {
                    break;
                }
                ActiveEffect active = entityEffects.get(id);
                if (active == null) {
                    continue;
                }
                boolean matches = normalizedTags.isEmpty()
                    || active.definition.tags().stream().map(this::normalizeId).anyMatch(normalizedTags::contains);
                if (!matches) {
                    continue;
                }

                runOperations(entity, active, active.definition.onRemove());
                entityEffects.remove(id);
                removed++;
            }

            if (entityEffects.isEmpty()) {
                activeEffects.remove(entity.getUniqueId());
            }
            future.complete(null);
        }, () -> {
            activeEffects.remove(entity.getUniqueId());
            future.complete(null);
        });
        if (!accepted) {
            return CompletableFuture.completedFuture(null);
        }
        return future;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDeath(EntityDeathEvent event) {
        activeEffects.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeEffects.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        activeEffects.forEach((entityId, entityEffects) -> {
            Entity raw = Bukkit.getEntity(entityId);
            if (!(raw instanceof LivingEntity livingEntity) || !raw.isValid()) {
                activeEffects.remove(entityId);
                return;
            }

            scheduler.runEntity(livingEntity, "effect-tick-" + entityId, () -> {
                if (!livingEntity.isValid()) {
                    activeEffects.remove(entityId);
                    return;
                }

                ArrayList<String> expired = new ArrayList<>();
                entityEffects.forEach((effectId, active) -> {
                    active.elapsedTicks += tickPeriodTicks;
                    active.remainingTicks -= tickPeriodTicks;

                    if (active.definition.tickIntervalTicks() > 0 && active.elapsedTicks % active.definition.tickIntervalTicks() == 0L) {
                        runOperations(livingEntity, active, active.definition.onTick());
                    }

                    if (active.remainingTicks <= 0L) {
                        runOperations(livingEntity, active, active.definition.onRemove());
                        expired.add(effectId);
                    }
                });

                expired.forEach(entityEffects::remove);
                if (entityEffects.isEmpty()) {
                    activeEffects.remove(entityId);
                }
            }, () -> activeEffects.remove(entityId));
        });
    }

    private void runOperations(LivingEntity target, ActiveEffect activeEffect, List<EffectOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }

        Entity source = activeEffect.sourceEntityId == null ? null : Bukkit.getEntity(activeEffect.sourceEntityId);
        for (EffectOperation operation : operations) {
            switch (operation.type()) {
                case DAMAGE -> applyDamage(target, source, operation, activeEffect);
                case HEAL -> applyHeal(target, operation, activeEffect);
                case SEND_MESSAGE -> applyMessage(target, operation, activeEffect);
                case SPAWN_PARTICLES -> applyParticles(target, operation);
                case PLAY_SOUND -> applySound(target, operation);
            }
        }
    }

    private void applyDamage(LivingEntity target, Entity source, EffectOperation operation, ActiveEffect activeEffect) {
        double amount = operation.base()
            + (Math.max(0, activeEffect.stacks - 1) * operation.stackScale())
            + (activeEffect.potency * operation.potencyScale());
        if (amount <= 0.0D) {
            return;
        }
        if (source instanceof LivingEntity livingSource) {
            target.damage(amount, livingSource);
            return;
        }
        target.damage(amount);
    }

    private void applyHeal(LivingEntity target, EffectOperation operation, ActiveEffect activeEffect) {
        double amount = operation.base()
            + (Math.max(0, activeEffect.stacks - 1) * operation.stackScale())
            + (activeEffect.potency * operation.potencyScale());
        if (amount <= 0.0D) {
            return;
        }
        double maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
            ? target.getHealth()
            : target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        target.setHealth(Math.min(maxHealth, target.getHealth() + amount));
    }

    private void applyMessage(LivingEntity target, EffectOperation operation, ActiveEffect activeEffect) {
        if (!(target instanceof Player player)) {
            return;
        }
        String raw = operation.message() == null ? "" : operation.message();
        String resolved = raw
            .replace("{effect}", activeEffect.definition.displayName())
            .replace("{stacks}", Integer.toString(activeEffect.stacks))
            .replace("{duration}", Long.toString(activeEffect.remainingTicks));
        if (!resolved.isBlank()) {
            messageService.send(player, resolved);
        }
    }

    private void applyParticles(LivingEntity target, EffectOperation operation) {
        Location location = target.getLocation();
        if (location.getWorld() == null || operation.particle() == null) {
            return;
        }
        location.getWorld().spawnParticle(
            operation.particle(),
            location,
            Math.max(1, operation.particleCount()),
            operation.offsetX(),
            operation.offsetY(),
            operation.offsetZ(),
            operation.extra()
        );
    }

    private void applySound(LivingEntity target, EffectOperation operation) {
        Location location = target.getLocation();
        if (location.getWorld() == null || operation.sound() == null) {
            return;
        }
        location.getWorld().playSound(location, operation.sound(), operation.volume(), operation.pitch());
    }

    private VortexEffectType parseEffect(String rawId, ConfigurationSection section) {
        String id = normalizeId(rawId);
        if (id.isBlank()) {
            throw new IllegalArgumentException("effect id cannot be blank");
        }
        EffectCategory category = parseCategory(section.getString("category", "neutral"));
        int maxStacks = Math.max(1, section.getInt("max-stacks", 1));
        int defaultDurationTicks = Math.max(1, section.getInt("duration-ticks", 100));
        int tickIntervalTicks = Math.max(1, section.getInt("tick-interval-ticks", 20));
        Set<String> tags = new LinkedHashSet<>(section.getStringList("tags").stream().map(this::normalizeId).toList());

        return new VortexEffectType(
            id,
            section.getString("display-name", friendlyName(id)),
            category,
            maxStacks,
            defaultDurationTicks,
            tickIntervalTicks,
            Set.copyOf(tags),
            parseOperations(section.getMapList("on-apply")),
            parseOperations(section.getMapList("on-tick")),
            parseOperations(section.getMapList("on-remove"))
        );
    }

    private List<EffectOperation> parseOperations(List<Map<?, ?>> maps) {
        ArrayList<EffectOperation> operations = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            EffectOperationType type = parseOperationType(stringValue(map.get("type"), ""));
            EffectOperation operation = switch (type) {
                case DAMAGE, HEAL -> new EffectOperation(
                    type,
                    doubleValue(map.get("base"), 0.0D),
                    doubleValue(map.get("stack-scale"), 0.0D),
                    doubleValue(map.get("potency-scale"), 0.0D),
                    null,
                    null,
                    0,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    null,
                    1.0F,
                    1.0F
                );
                case SEND_MESSAGE -> new EffectOperation(
                    type,
                    0.0D,
                    0.0D,
                    0.0D,
                    stringValue(map.get("message"), ""),
                    null,
                    0,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    null,
                    1.0F,
                    1.0F
                );
                case SPAWN_PARTICLES -> new EffectOperation(
                    type,
                    0.0D,
                    0.0D,
                    0.0D,
                    null,
                    particleValue(map.get("particle")),
                    Math.max(1, intValue(map.get("count"), 12)),
                    doubleValue(map.get("offset-x"), 0.25D),
                    doubleValue(map.get("offset-y"), 0.25D),
                    doubleValue(map.get("offset-z"), 0.25D),
                    doubleValue(map.get("extra"), 0.01D),
                    null,
                    1.0F,
                    1.0F
                );
                case PLAY_SOUND -> new EffectOperation(
                    type,
                    0.0D,
                    0.0D,
                    0.0D,
                    null,
                    null,
                    0,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    soundValue(map.get("sound")),
                    (float) doubleValue(map.get("volume"), 1.0D),
                    (float) doubleValue(map.get("pitch"), 1.0D)
                );
            };
            operations.add(operation);
        }
        return List.copyOf(operations);
    }

    private LivingEntity resolveTarget(SpellContext context, SpellTarget target) {
        if (target.entityId() != null) {
            Entity entity = Bukkit.getEntity(target.entityId());
            return entity instanceof LivingEntity living ? living : null;
        }
        if (target.kind() == SpellTarget.TargetKind.SELF && context.triggerSource() instanceof LivingEntity livingSource) {
            return livingSource;
        }
        return null;
    }

    private EffectCategory parseCategory(String raw) {
        return switch (normalizeId(raw)) {
            case "beneficial", "buff", "positive" -> EffectCategory.BENEFICIAL;
            case "harmful", "debuff", "negative" -> EffectCategory.HARMFUL;
            default -> EffectCategory.NEUTRAL;
        };
    }

    private EffectOperationType parseOperationType(String raw) {
        return switch (normalizeId(raw)) {
            case "damage" -> EffectOperationType.DAMAGE;
            case "heal" -> EffectOperationType.HEAL;
            case "send_message", "message" -> EffectOperationType.SEND_MESSAGE;
            case "spawn_particles", "spawn_particle", "particles" -> EffectOperationType.SPAWN_PARTICLES;
            case "play_sound", "sound" -> EffectOperationType.PLAY_SOUND;
            default -> throw new IllegalArgumentException("unsupported effect operation type: " + raw);
        };
    }

    private String normalizeSoundKey(Object raw) {
        String value = stringValue(raw, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (!normalized.contains(":")) {
            normalized = NamespacedKey.MINECRAFT + ":" + normalized;
        }
        return normalized;
    }

    @SuppressWarnings("removal")
    private Sound soundValue(Object raw) {
        String keyValue = normalizeSoundKey(raw);
        if (keyValue == null) {
            throw new IllegalArgumentException("sound operation is missing a sound value");
        }
        NamespacedKey key = NamespacedKey.fromString(keyValue);
        if (key == null) {
            throw new IllegalArgumentException("unknown sound: " + raw);
        }
        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) {
            try {
                sound = Sound.valueOf(key.getKey().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (sound == null) {
            throw new IllegalArgumentException("unknown sound: " + raw);
        }
        return sound;
    }

    private Particle particleValue(Object raw) {
        String token = stringValue(raw, "");
        if (token.isBlank()) {
            throw new IllegalArgumentException("particle operation is missing a particle value");
        }
        try {
            return Particle.valueOf(token.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown particle: " + raw);
        }
    }

    private String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String friendlyName(String raw) {
        String[] parts = normalizeId(raw).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.toString();
        return value.isBlank() ? fallback : value;
    }

    private int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double doubleValue(Object raw, double fallback) {
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

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private static final class ActiveEffect {

        private final VortexEffectType definition;
        private int stacks;
        private long remainingTicks;
        private long elapsedTicks;
        private double potency;
        private UUID sourceEntityId;

        private ActiveEffect(
            VortexEffectType definition,
            int stacks,
            long remainingTicks,
            double potency,
            UUID sourceEntityId
        ) {
            this.definition = definition;
            this.stacks = stacks;
            this.remainingTicks = remainingTicks;
            this.elapsedTicks = 0L;
            this.potency = potency;
            this.sourceEntityId = sourceEntityId;
        }
    }

    public record ReloadResult(int loaded, int skipped, List<String> messages) {
    }
}
