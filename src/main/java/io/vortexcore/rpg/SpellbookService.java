package io.vortexcore.rpg;

import io.vortexcore.magic.CompiledSpell;
import io.vortexcore.magic.ConditionType;
import io.vortexcore.magic.MechanicType;
import io.vortexcore.magic.SpellCondition;
import io.vortexcore.magic.SpellContext;
import io.vortexcore.magic.SpellMechanic;
import io.vortexcore.magic.SpellTarget;
import io.vortexcore.magic.SpellTargeter;
import io.vortexcore.magic.TargeterType;
import io.vortexcore.magic.TriggerType;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpellbookService implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final NexusBus nexusBus;
    private final ClassSkillService classSkillService;
    private final EffectService effectService;
    private final ProtectionHookService protectionHookService;
    private final MessageService messageService;
    private final Map<String, RegisteredSpell> spells;
    private final Set<String> builtinSpellIds;
    private final Set<String> customSpellIds;
    private final Map<String, SpellVisuals> spellVisuals;
    private final Map<UUID, Map<String, Long>> cooldowns;
    private final CopyOnWriteArrayList<SpellCastListener> castListeners;

    public SpellbookService(
        JavaPlugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        NexusBus nexusBus,
        ClassSkillService classSkillService,
        EffectService effectService,
        ProtectionHookService protectionHookService,
        MessageService messageService
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.nexusBus = nexusBus;
        this.classSkillService = classSkillService;
        this.effectService = effectService;
        this.protectionHookService = protectionHookService;
        this.messageService = messageService;
        this.spells = new LinkedHashMap<>();
        this.builtinSpellIds = new LinkedHashSet<>();
        this.customSpellIds = ConcurrentHashMap.newKeySet();
        this.spellVisuals = new ConcurrentHashMap<>();
        this.cooldowns = new ConcurrentHashMap<>();
        this.castListeners = new CopyOnWriteArrayList<>();
        registerDefaults();
    }

    public Collection<String> availableSpells(UnifiedPlayer profile) {
        LinkedHashSet<String> available = new LinkedHashSet<>(classSkillService.availableSpellIds(profile));
        Optional<VortexClass> selectedClass = classSkillService.selectedClass(profile);
        spells.values().stream()
            .filter(spell -> spell.accessRule() != null && spell.accessRule().matches(profile, selectedClass))
            .map(RegisteredSpell::id)
            .forEach(available::add);
        return List.copyOf(available);
    }

    public Collection<String> registeredSpellIds() {
        return List.copyOf(spells.keySet());
    }

    public java.io.File dataFolder() {
        return plugin.getDataFolder();
    }

    public Optional<String> activeSpellId(UnifiedPlayer profile) {
        Collection<String> available = availableSpells(profile);
        String active = classSkillService.activeSpellId(profile).orElse(null);
        if (active != null) {
            String normalized = normalizeId(active);
            if (spells.containsKey(normalized) && available.contains(normalized)) {
                return Optional.of(normalized);
            }
        }
        return available.stream().findFirst();
    }

    public void setActiveSpell(UnifiedPlayer profile, String spellId) {
        String normalized = normalizeId(spellId);
        RegisteredSpell spell = spells.get(normalized);
        if (!availableSpells(profile).contains(normalized)) {
            throw new IllegalArgumentException(unavailableReason(profile, spell, normalized));
        }
        classSkillService.forceActiveSpell(profile, normalized);
    }

    public String spellLabel(String spellId) {
        RegisteredSpell spell = spells.get(normalizeId(spellId));
        return spell == null ? NameFormat.friendly(spellId) : spell.displayName();
    }

    public void openSpellMenu(Player player, UnifiedPlayer profile) {
        List<String> available = new ArrayList<>(availableSpells(profile));
        if (available.isEmpty()) {
            messageService.send(player, "<red>You do not have any unlocked spells.</red>");
            return;
        }
        Optional<String> activeSpellId = activeSpellId(profile);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Spellbook"), 6);
        builder.button(4, spellMenuIcon(activeSpellId.orElse(null), true), context -> CompletableFuture.completedFuture(null));

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int index = 0; index < Math.min(available.size(), slots.length); index++) {
            String spellId = available.get(index);
            boolean active = spellId.equals(activeSpellId.orElse(null));
            builder.button(slots[index], spellMenuIcon(spellId, active), context -> {
                setActiveSpell(profile, spellId);
                messageService.send(context.player(), "<green>Active spell set to " + spellLabel(spellId) + "</green>");
                openSpellMenu(context.player(), profile);
                return CompletableFuture.completedFuture(null);
            });
        }

        builder.button(49, menuIcon(Material.BLAZE_POWDER, "Cast Active Spell", List.of(
            activeSpellId.map(this::spellLabel).orElse("No active spell"),
            "Click to cast your active spell immediately."
        )), context -> {
            cast(context.player(), profile, null).exceptionally(error -> null);
            return CompletableFuture.completedFuture(null);
        });
        builder.button(50, menuIcon(Material.BOOK, "Spellbook Help", List.of(
            "Click any spell to equip it.",
            "Use /spell cast to fire the active spell.",
            "Right-click active-spell items casts the equipped spell."
        )), context -> CompletableFuture.completedFuture(null));
        if (player.hasPermission("vortex.admin.spells")) {
            builder.button(51, menuIcon(Material.ENCHANTING_TABLE, "Open Spell Forge", List.of(
                "Open the spell forge editor for built-in and custom spells."
            )), context -> {
                // Routed through the same player-facing command entry.
                messageService.send(context.player(), "<gray>Use /spell forge to open the spell forge.</gray>");
                return CompletableFuture.completedFuture(null);
            });
        }
        menuBuilder.open(player, builder.build());
    }

    public List<EditorSpellSnapshot> editorSpellSnapshots() {
        return spells.values().stream().map(this::editorSnapshot).toList();
    }

    public ReloadResult reloadCustomSpells() {
        resetRegistry();

        File file = new File(plugin.getDataFolder(), "spells.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection spellsSection = configuration.getConfigurationSection("spells");
        if (spellsSection == null) {
            return new ReloadResult(0, 0, List.of("No spells section found in spells.yml."));
        }

        int loaded = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();

        for (String rawId : spellsSection.getKeys(false)) {
            ConfigurationSection section = spellsSection.getConfigurationSection(rawId);
            if (section == null) {
                skipped++;
                messages.add(rawId + ": entry is not a configuration section.");
                continue;
            }

            if (!section.getBoolean("enabled", true)) {
                skipped++;
                continue;
            }

            String id = normalizeId(rawId);
            try {
                registerCustomSpell(id, section);
                loaded++;
            } catch (IllegalArgumentException exception) {
                skipped++;
                String message = id + ": " + exception.getMessage();
                messages.add(message);
                logger.warning("Unable to load custom spell " + id + ": " + exception.getMessage());
            }
        }

        logger.info("Loaded " + loaded + " custom spell(s) from spells.yml. Skipped " + skipped + ".");
        return new ReloadResult(loaded, skipped, List.copyOf(messages));
    }

    public CompletableFuture<Void> cast(Player player, UnifiedPlayer profile, String requestedSpellId) {
        String spellId = requestedSpellId == null || requestedSpellId.isBlank()
            ? activeSpellId(profile).orElse(null)
            : normalizeId(requestedSpellId);

        if (spellId == null) {
            return failedCast(player, "You do not have an active spell.");
        }

        RegisteredSpell spell = spells.get(spellId);
        if (!availableSpells(profile).contains(spellId)) {
            return failedCast(player, unavailableReason(profile, spell, spellId));
        }
        if (spell == null) {
            return failedCast(player, "Spell " + spellId + " is not registered.");
        }

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>()).getOrDefault(spellId, 0L);
        if (readyAt > now) {
            return failedCast(player, "Spell is on cooldown for " + ((readyAt - now) / 1000.0D) + "s");
        }
        ProtectionCheckResult originCheck = protectionHookService.check(player, player.getLocation(), ProtectionAction.SPELL_CAST);
        if (!originCheck.allowed()) {
            return failedCast(player, ProtectionHookService.denyMessage(originCheck, ProtectionAction.SPELL_CAST));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "cast-" + spellId + "-" + player.getUniqueId(), () -> {
            SpellContext context = new SpellContext(UUID.randomUUID(), profile, player.getLocation(), nexusBus, player);
            SpellVisuals visuals = spellVisuals.get(spellId);
            if (visuals != null) {
                context.putState("spell.visuals", visuals);
                playCastFx(player, visuals);
            }
            cooldowns.get(player.getUniqueId()).put(spellId, System.currentTimeMillis() + spell.cooldownMillis());
            spell.compiledSpell().execute(context).whenComplete((ignored, error) -> {
                if (error != null) {
                    cooldowns.get(player.getUniqueId()).remove(spellId);
                    scheduler.runEntity(player, "cast-error-" + spellId, () -> messageService.actionBar(player,
                        "<red>\u26A0 " + sanitizeForActionBar(error.getMessage()) + "</red>"));
                    future.completeExceptionally(error);
                    return;
                }

                for (SpellCastListener castListener : castListeners) {
                    castListener.onSpellCast(player, spellId);
                }
                scheduler.runEntity(player, "cast-success-" + spellId, () -> messageService.actionBar(player,
                    "<gradient:#8B5CF6:#3B82F6>\u2728 " + spell.displayName() + "</gradient>"));
                future.complete(null);
            });
        });
        return future;
    }

    private static String sanitizeForActionBar(String raw) {
        if (raw == null || raw.isBlank()) return "Spell failed";
        // Strip MiniMessage / legacy tags for safety in action bar
        return raw.replace('\u00A7', ' ').trim();
    }

    private CompletableFuture<Void> failedCast(Player player, String message) {
        scheduler.runEntity(player, "cast-failed-" + player.getUniqueId() + "-" + System.nanoTime(),
            () -> messageService.actionBar(player, "<red>⚠ " + sanitizeForActionBar(message) + "</red>"));
        return failedFuture(new IllegalStateException(message));
    }

    private void playCastFx(Player caster, SpellVisuals visuals) {
        if (visuals.castSound() != null) {
            scheduler.runRegion(caster.getLocation(), "spell-castsnd-" + caster.getUniqueId(),
                () -> caster.getWorld().playSound(caster.getLocation(), visuals.castSound(), 0.9F, 1.0F));
        }
        if (visuals.castParticle() != null) {
            Location loc = caster.getEyeLocation();
            scheduler.runRegion(loc, "spell-castfx-" + caster.getUniqueId(),
                () -> loc.getWorld().spawnParticle(visuals.castParticle(), loc, 18, 0.35D, 0.35D, 0.35D, 0.04D));
        }
    }

    void playBeamAndImpact(Player caster, LivingEntity target, SpellVisuals visuals) {
        if (target == null) return;
        Location end = target.getLocation().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        playBeamAndImpactAt(caster, end, visuals);
    }

    /** Beam + impact visuals to an arbitrary location (used for air casts where there is no entity). */
    void playBeamAndImpactAt(Player caster, Location end, SpellVisuals visuals) {
        if (visuals == null || caster == null || end == null) return;
        Location start = caster.getEyeLocation();
        if (start.getWorld() == null || end.getWorld() == null || !start.getWorld().equals(end.getWorld())) return;

        if (visuals.beamParticle() != null) {
            scheduler.runRegion(start, "spell-beam-" + caster.getUniqueId(), () -> {
                org.bukkit.util.Vector dir = end.toVector().subtract(start.toVector());
                double dist = dir.length();
                if (dist < 0.05D) return;
                int steps = Math.min(160, Math.max(8, (int) (dist * 8.0D)));
                org.bukkit.util.Vector step = dir.multiply(1.0D / steps);
                Location cur = start.clone();
                // Muzzle flash at the staff tip — makes it obvious the bolt is shooting out
                cur.getWorld().spawnParticle(visuals.beamParticle(), cur, 8, 0.12D, 0.12D, 0.12D, 0.0D);
                for (int i = 0; i < steps; i++) {
                    cur.add(step);
                    cur.getWorld().spawnParticle(visuals.beamParticle(), cur, 2, 0.02D, 0.02D, 0.02D, 0.0D);
                }
            });
        }
        Location impactLoc = end.clone();
        if (visuals.impactParticle() != null) {
            scheduler.runRegion(impactLoc, "spell-impfx-" + caster.getUniqueId() + "-" + System.nanoTime(),
                () -> impactLoc.getWorld().spawnParticle(visuals.impactParticle(), impactLoc, 30, 0.5D, 0.5D, 0.5D, 0.08D));
        }
        if (visuals.impactSound() != null) {
            scheduler.runRegion(impactLoc, "spell-impsnd-" + caster.getUniqueId() + "-" + System.nanoTime(),
                () -> impactLoc.getWorld().playSound(impactLoc, visuals.impactSound(), 0.9F, 1.05F));
        }
    }

    public void setCastListener(SpellCastListener castListener) {
        this.castListeners.clear();
        if (castListener != null) {
            this.castListeners.add(castListener);
        }
    }

    public void addCastListener(SpellCastListener castListener) {
        if (castListener != null) {
            this.castListeners.addIfAbsent(castListener);
        }
    }

    private void registerDefaults() {
        registerBuiltin(new RegisteredSpell(
            "fortify",
            "Fortify",
            5000L,
            new SimpleCompiledSpell(
                "fortify",
                List.of(new SelfTargeter()),
                List.of(),
                List.of(
                    new HealMechanic(4.0D, VortexAttribute.VITALITY, 0.65D),
                    new CasterMessageMechanic("Your stance hardens with protective force.")
                )
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "arcane_bolt",
            "Arcane Bolt",
            3000L,
            new SimpleCompiledSpell(
                "arcane_bolt",
                List.of(new RayTargeter(28.0D, true)),
                List.of(),
                List.of(new DamageMechanic(6.0D, VortexAttribute.INTELLIGENCE, 0.9D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "mage_staff_bolt",
            "Mage Staff Bolt",
            650L,
            new SimpleCompiledSpell(
                "mage_staff_bolt",
                List.of(new RayTargeter(32.0D, true)),
                List.of(),
                List.of(new DamageMechanic(3.0D, VortexAttribute.INTELLIGENCE, 0.45D))
            ),
            new SpellAccessRule(1, Set.of(VortexClass.MAGE))
        ));

        registerBuiltin(new RegisteredSpell(
            "hunter_mark",
            "Hunter Mark",
            3000L,
            new SimpleCompiledSpell(
                "hunter_mark",
                List.of(new RayTargeter(40.0D, true)),
                List.of(),
                List.of(new DamageMechanic(5.5D, VortexAttribute.DEXTERITY, 0.95D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "mend",
            "Mend",
            4500L,
            new SimpleCompiledSpell(
                "mend",
                List.of(new SelfTargeter()),
                List.of(),
                List.of(
                    new HealMechanic(5.5D, VortexAttribute.SPIRIT, 0.85D),
                    new CasterMessageMechanic("Restorative light flows through you.")
                )
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "war_stomp",
            "War Stomp",
            7000L,
            new SimpleCompiledSpell(
                "war_stomp",
                List.of(new NearbyMonsterTargeter(6.0D, 3)),
                List.of(new RequireTargetCondition()),
                List.of(new DamageMechanic(4.5D, VortexAttribute.STRENGTH, 0.8D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "nova_burst",
            "Nova Burst",
            7000L,
            new SimpleCompiledSpell(
                "nova_burst",
                List.of(new NearbyMonsterTargeter(8.0D, 4)),
                List.of(new RequireTargetCondition()),
                List.of(new DamageMechanic(4.0D, VortexAttribute.WISDOM, 0.75D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "volley",
            "Volley",
            7000L,
            new SimpleCompiledSpell(
                "volley",
                List.of(new NearbyMonsterTargeter(22.0D, 3)),
                List.of(new RequireTargetCondition()),
                List.of(new DamageMechanic(4.0D, VortexAttribute.DEXTERITY, 0.8D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "radiant_pulse",
            "Radiant Pulse",
            8000L,
            new SimpleCompiledSpell(
                "radiant_pulse",
                List.of(new NearbyPlayerTargeter(8.0D)),
                List.of(),
                List.of(new HealMechanic(4.5D, VortexAttribute.SPIRIT, 0.7D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "beast_call",
            "Beast Call",
            5000L,
            new SimpleCompiledSpell(
                "beast_call",
                List.of(new NearbyMonsterTargeter(12.0D, 2)),
                List.of(new RequireTargetCondition()),
                List.of(
                    new DamageMechanic(4.0D, VortexAttribute.SPIRIT, 0.75D),
                    new CasterMessageMechanic("Your beasts lunge at the enemy.")
                )
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "savage_charge",
            "Savage Charge",
            7000L,
            new SimpleCompiledSpell(
                "savage_charge",
                List.of(new NearbyMonsterTargeter(10.0D, 1)),
                List.of(new RequireTargetCondition()),
                List.of(new DamageMechanic(6.0D, VortexAttribute.STRENGTH, 0.85D))
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "deploy_turret",
            "Deploy Turret",
            6000L,
            new SimpleCompiledSpell(
                "deploy_turret",
                List.of(new NearbyMonsterTargeter(14.0D, 2)),
                List.of(new RequireTargetCondition()),
                List.of(
                    new DamageMechanic(5.0D, VortexAttribute.INTELLIGENCE, 0.8D),
                    new CasterMessageMechanic("Your turret locks on and fires.")
                )
            ),
            null
        ));

        registerBuiltin(new RegisteredSpell(
            "concussion_mine",
            "Concussion Mine",
            7000L,
            new SimpleCompiledSpell(
                "concussion_mine",
                List.of(new NearbyMonsterTargeter(8.0D, 3)),
                List.of(new RequireTargetCondition()),
                List.of(new DamageMechanic(4.5D, VortexAttribute.INTELLIGENCE, 0.75D))
            ),
            null
        ));
        registerDefaultVisuals();
    }

    private void registerDefaultVisuals() {
        // Mage / arcane: precise blue bolts and heavier void-purple detonations.
        spellVisuals.put("mage_staff_bolt", SpellVisuals.beam(
            Sound.ENTITY_BLAZE_SHOOT, Particle.ELECTRIC_SPARK,
            Particle.END_ROD, Particle.FLASH, Sound.BLOCK_AMETHYST_BLOCK_HIT));
        spellVisuals.put("arcane_bolt", SpellVisuals.beam(
            Sound.ENTITY_ILLUSIONER_CAST_SPELL, Particle.ENCHANT,
            Particle.WITCH, Particle.DRAGON_BREATH, Sound.ENTITY_EVOKER_CAST_SPELL));
        spellVisuals.put("nova_burst", SpellVisuals.burst(
            Sound.ENTITY_EVOKER_CAST_SPELL, Particle.ENCHANT,
            Particle.SOUL_FIRE_FLAME, Sound.ENTITY_GENERIC_EXPLODE));

        // Ranger / hunter: clean precision and marked-hit feedback.
        spellVisuals.put("hunter_mark", SpellVisuals.beam(
            Sound.ENTITY_ARROW_SHOOT, Particle.CRIT,
            Particle.CRIT, Particle.ENCHANTED_HIT, Sound.ENTITY_ARROW_HIT));
        spellVisuals.put("volley", SpellVisuals.beam(
            Sound.ENTITY_ARROW_SHOOT, Particle.CRIT,
            Particle.CRIT, Particle.SWEEP_ATTACK, Sound.ENTITY_ARROW_HIT));

        // Knight / warrior: weighty impacts and defensive golden magic.
        spellVisuals.put("war_stomp", SpellVisuals.burst(
            Sound.ENTITY_IRON_GOLEM_ATTACK, Particle.DUST_PLUME,
            Particle.EXPLOSION, Sound.ENTITY_GENERIC_EXPLODE));
        spellVisuals.put("savage_charge", SpellVisuals.beam(
            Sound.ENTITY_RAVAGER_ATTACK, Particle.ANGRY_VILLAGER,
            Particle.FLAME, Particle.LAVA, Sound.ENTITY_PLAYER_ATTACK_CRIT));
        spellVisuals.put("fortify", SpellVisuals.selfBuff(
            Sound.BLOCK_BEACON_ACTIVATE, Particle.TOTEM_OF_UNDYING));

        // Cleric: restorative light and heart beats.
        spellVisuals.put("mend", SpellVisuals.selfBuff(
            Sound.BLOCK_AMETHYST_BLOCK_CHIME, Particle.HAPPY_VILLAGER));
        spellVisuals.put("radiant_pulse", SpellVisuals.burst(
            Sound.BLOCK_BEACON_ACTIVATE, Particle.HAPPY_VILLAGER,
            Particle.HEART, Sound.BLOCK_AMETHYST_BLOCK_CHIME));

        // Beast / engineering: primal surges and gadget sparks.
        spellVisuals.put("beast_call", SpellVisuals.beam(
            Sound.ENTITY_WOLF_AMBIENT, Particle.COMPOSTER,
            Particle.HAPPY_VILLAGER, Particle.DAMAGE_INDICATOR, Sound.ENTITY_WOLF_GROWL));
        spellVisuals.put("deploy_turret", SpellVisuals.selfBuff(
            Sound.BLOCK_PISTON_EXTEND, Particle.ELECTRIC_SPARK));
        spellVisuals.put("concussion_mine", SpellVisuals.burst(
            Sound.ENTITY_CREEPER_PRIMED, Particle.SMOKE,
            Particle.EXPLOSION, Sound.ENTITY_GENERIC_EXPLODE));
    }

    private void registerBuiltin(RegisteredSpell spell) {
        builtinSpellIds.add(spell.id());
        spells.put(spell.id(), spell);
    }

    private void registerCustomSpell(String id, ConfigurationSection section) {
        List<SpellTargeter> targeters = loadTargeters(id, section);
        List<SpellCondition> conditions = loadConditions(id, section);
        List<SpellMechanic> mechanics = loadMechanics(id, section);
        if (targeters.isEmpty()) {
            throw new IllegalArgumentException("custom spells require at least one targeter.");
        }
        if (mechanics.isEmpty()) {
            throw new IllegalArgumentException("custom spells require at least one mechanic.");
        }

        spells.put(
            id,
            new RegisteredSpell(
                id,
                section.getString("display-name", NameFormat.friendly(id)),
                Math.max(0L, section.getLong("cooldown-millis", 0L)),
                new SimpleCompiledSpell(id, targeters, conditions, mechanics),
                new SpellAccessRule(
                    Math.max(1, section.getInt("min-level", 1)),
                    parseRequiredClasses(section.getStringList("required-classes"), id)
                )
            )
        );
        customSpellIds.add(id);
    }

    private void resetRegistry() {
        spells.clear();
        builtinSpellIds.clear();
        customSpellIds.clear();
        spellVisuals.clear();
        registerDefaults();
    }

    private EditorSpellSnapshot editorSnapshot(RegisteredSpell spell) {
        int minLevel = spell.accessRule() == null ? 1 : spell.accessRule().minLevel();
        List<String> requiredClasses = spell.accessRule() == null
            ? List.of()
            : spell.accessRule().requiredClasses().stream().map(VortexClass::commandId).toList();
        List<Map<String, Object>> targeters = spell.compiledSpell().targeters().stream().map(this::serializeTargeter).toList();
        List<Map<String, Object>> conditions = spell.compiledSpell().conditions().stream().map(this::serializeCondition).toList();
        List<Map<String, Object>> mechanics = spell.compiledSpell().mechanics().stream().map(this::serializeMechanic).toList();
        return new EditorSpellSnapshot(
            spell.id(),
            spell.displayName(),
            spell.cooldownMillis(),
            minLevel,
            requiredClasses,
            targeters,
            conditions,
            mechanics,
            builtinSpellIds.contains(spell.id())
        );
    }

    private Map<String, Object> serializeTargeter(SpellTargeter targeter) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        switch (targeter) {
            case SelfTargeter ignored -> map.put("type", "self");
            case NearbyMonsterTargeter nearbyMonsterTargeter -> {
                map.put("type", "nearby_monster");
                map.put("range", nearbyMonsterTargeter.range);
                map.put("limit", nearbyMonsterTargeter.limit);
            }
            case NearbyPlayerTargeter nearbyPlayerTargeter -> {
                map.put("type", "nearby_player");
                map.put("range", nearbyPlayerTargeter.range);
            }
            case RayTargeter rayTargeter -> {
                map.put("type", "ray");
                map.put("range", rayTargeter.range);
                map.put("blocked-by-solids", rayTargeter.blockedBySolids);
            }
            default -> map.put("type", targeter.type().name().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(map);
    }

    private Map<String, Object> serializeCondition(SpellCondition condition) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        switch (condition) {
            case RequireTargetCondition ignored -> map.put("type", "has_target");
            case HealthRatioCondition healthRatioCondition -> {
                map.put("type", healthRatioCondition.below ? "has_health_below" : "has_health_above");
                map.put("threshold", healthRatioCondition.threshold);
            }
            case MovementCondition movementCondition -> map.put("type", movementCondition.moving ? "caster_moving" : "caster_stationary");
            case ChancePassCondition chancePassCondition -> {
                map.put("type", "chance_pass");
                map.put("chance", chancePassCondition.chance);
            }
            case PermissionCondition permissionCondition -> {
                map.put("type", "has_permission");
                map.put("permission", permissionCondition.permission);
            }
            case TargetEntityKindCondition targetEntityKindCondition -> map.put("type", targetEntityKindCondition.playerTarget ? "target_is_player" : "target_is_mob");
            case SelfTargetCondition ignored -> map.put("type", "target_is_self");
            default -> map.put("type", condition.type().name().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(map);
    }

    private Map<String, Object> serializeMechanic(SpellMechanic mechanic) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        SpellMechanic actual = mechanic;
        MechanicTargetScope scope = MechanicTargetScope.ANY;
        if (mechanic instanceof ScopedMechanic scopedMechanic) {
            actual = scopedMechanic.delegate;
            scope = scopedMechanic.scope;
        }

        switch (actual) {
            case DamageMechanic damageMechanic -> {
                map.put("type", "damage");
                map.put("base", damageMechanic.baseDamage);
                if (damageMechanic.scalingAttribute != null) {
                    map.put("scaling-attribute", damageMechanic.scalingAttribute.name().toLowerCase(Locale.ROOT));
                }
                map.put("scale", damageMechanic.scale);
            }
            case HealMechanic healMechanic -> {
                map.put("type", "heal");
                map.put("base", healMechanic.baseHeal);
                if (healMechanic.scalingAttribute != null) {
                    map.put("scaling-attribute", healMechanic.scalingAttribute.name().toLowerCase(Locale.ROOT));
                }
                map.put("scale", healMechanic.scale);
            }
            case CasterMessageMechanic casterMessageMechanic -> {
                map.put("type", "send_message");
                map.put("message", casterMessageMechanic.message);
            }
            case ParticleMechanic particleMechanic -> {
                map.put("type", "spawn_particles");
                map.put("particle", particleMechanic.particle.name().toLowerCase(Locale.ROOT));
                map.put("count", particleMechanic.count);
                map.put("offset-x", particleMechanic.offsetX);
                map.put("offset-y", particleMechanic.offsetY);
                map.put("offset-z", particleMechanic.offsetZ);
                map.put("extra", particleMechanic.extra);
            }
            case PlaySoundMechanic playSoundMechanic -> {
                map.put("type", "play_sound");
                map.put("sound", Registry.SOUNDS.getKeyOrThrow(playSoundMechanic.sound).getKey());
                map.put("volume", playSoundMechanic.volume);
                map.put("pitch", playSoundMechanic.pitch);
            }
            case ApplyEffectMechanic applyEffectMechanic -> {
                map.put("type", "apply_effect");
                map.put("effect", applyEffectMechanic.effectId);
                map.put("stacks", applyEffectMechanic.stacks);
                map.put("duration-ticks", applyEffectMechanic.durationTicks);
                map.put("potency", applyEffectMechanic.potency);
                map.put("refresh-duration", applyEffectMechanic.refreshDuration);
            }
            case RemoveEffectMechanic removeEffectMechanic -> {
                map.put("type", "remove_effect");
                map.put("effect", removeEffectMechanic.effectId);
                map.put("stacks", removeEffectMechanic.stacksToRemove);
            }
            case CleanseMechanic cleanseMechanic -> {
                map.put("type", "cleanse");
                map.put("tags", List.copyOf(cleanseMechanic.tags));
                map.put("max-removals", cleanseMechanic.maxRemovals);
            }
            default -> map.put("type", actual.type().name().toLowerCase(Locale.ROOT));
        }
        if (scope != MechanicTargetScope.ANY) {
            map.put("target-filter", scope.name().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(map);
    }

    private ItemStack spellMenuIcon(String spellId, boolean active) {
        String title = spellId == null ? "Active Spell" : spellLabel(spellId);
        RegisteredSpell spell = spellId == null ? null : spells.get(normalizeId(spellId));
        List<String> lore = new ArrayList<>();
        if (spellId == null) {
            lore.add(active ? "Your current active spell." : "No active spell selected.");
        } else {
            lore.add("Id: " + spellId);
            if (spell != null) {
                lore.add("Cooldown: " + spell.cooldownMillis() + " ms");
            }
            lore.add(active ? "Currently equipped." : "Click to equip this spell.");
        }
        return menuIcon(spellIconMaterial(spellId), (active ? "* " : "") + title, lore);
    }

    private Material spellIconMaterial(String spellId) {
        if (spellId == null) {
            return Material.NETHER_STAR;
        }
        String normalized = spellId.toLowerCase(Locale.ROOT);
        if (normalized.contains("mend") || normalized.contains("radiant") || normalized.contains("heal")) {
            return Material.GHAST_TEAR;
        }
        if (normalized.contains("volley") || normalized.contains("hunter") || normalized.contains("mark")) {
            return Material.BOW;
        }
        if (normalized.contains("fortify") || normalized.contains("stomp") || normalized.contains("charge")) {
            return Material.IRON_SWORD;
        }
        if (normalized.contains("turret") || normalized.contains("mine")) {
            return Material.REDSTONE;
        }
        if (normalized.contains("beast")) {
            return Material.BONE;
        }
        return Material.BLAZE_POWDER;
    }

    private ItemStack menuIcon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Set<VortexClass> parseRequiredClasses(List<String> rawClasses, String spellId) {
        LinkedHashSet<VortexClass> classes = new LinkedHashSet<>();
        for (String raw : rawClasses) {
            VortexClass vortexClass = VortexClass.parse(raw);
            if (vortexClass == null) {
                throw new IllegalArgumentException("unknown class '" + raw + "' in " + spellId);
            }
            classes.add(vortexClass);
        }
        return Set.copyOf(classes);
    }

    private String unavailableReason(UnifiedPlayer profile, RegisteredSpell spell, String spellId) {
        if (spell != null && spell.accessRule() != null) {
            if (profile.level() < spell.accessRule().minLevel()) {
                return spell.displayName() + " requires level " + spell.accessRule().minLevel() + ".";
            }
            if (!spell.accessRule().requiredClasses().isEmpty()) {
                String required = spell.accessRule().requiredClasses().stream().map(VortexClass::displayName).sorted().reduce((left, right) -> left + ", " + right).orElse("a class");
                return spell.displayName() + " requires class: " + required + ".";
            }
        }
        return "Spell " + spellId + " is not unlocked.";
    }

    private List<SpellTargeter> loadTargeters(String spellId, ConfigurationSection section) {
        List<SpellTargeter> targeters = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("targeters")) {
            String type = normalizeToken(stringValue(entry.get("type"), null));
            switch (type) {
                case "SELF" -> targeters.add(new SelfTargeter());
                case "NEARBY_MONSTER", "MONSTER", "AREA_MONSTER" -> targeters.add(
                    new NearbyMonsterTargeter(doubleValue(entry.get("range"), 12.0D), Math.max(1, intValue(entry.get("limit"), 1)))
                );
                case "NEARBY_PLAYER", "PLAYER", "PARTY" -> targeters.add(new NearbyPlayerTargeter(doubleValue(entry.get("range"), 8.0D)));
                case "RAY", "LINE", "LINE_OF_SIGHT", "DIRECTIONAL" -> targeters.add(
                    new RayTargeter(doubleValue(entry.get("range"), 24.0D), booleanValue(entry.get("blocked-by-solids"), true))
                );
                default -> throw new IllegalArgumentException(
                    "unsupported targeter type '" + stringValue(entry.get("type"), "null") + "' in " + spellId
                );
            }
        }
        return targeters;
    }

    private List<SpellCondition> loadConditions(String spellId, ConfigurationSection section) {
        List<SpellCondition> conditions = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("conditions")) {
            String type = normalizeToken(stringValue(entry.get("type"), null));
            switch (type) {
                case "HAS_TARGET" -> conditions.add(new RequireTargetCondition());
                case "HAS_HEALTH_BELOW" -> conditions.add(new HealthRatioCondition(doubleValue(entry.get("threshold"), 0.5D), true));
                case "HAS_HEALTH_ABOVE" -> conditions.add(new HealthRatioCondition(doubleValue(entry.get("threshold"), 0.5D), false));
                case "CASTER_MOVING" -> conditions.add(new MovementCondition(true));
                case "CASTER_STATIONARY" -> conditions.add(new MovementCondition(false));
                case "CHANCE_PASS" -> conditions.add(new ChancePassCondition(doubleValue(entry.get("chance"), 1.0D)));
                case "HAS_PERMISSION" -> conditions.add(new PermissionCondition(stringValue(entry.get("permission"), "")));
                case "TARGET_IS_PLAYER" -> conditions.add(new TargetEntityKindCondition(true));
                case "TARGET_IS_MOB" -> conditions.add(new TargetEntityKindCondition(false));
                case "TARGET_IS_SELF" -> conditions.add(new SelfTargetCondition());
                default -> throw new IllegalArgumentException(
                    "unsupported condition type '" + stringValue(entry.get("type"), "null") + "' in " + spellId
                );
            }
        }
        return conditions;
    }

    private List<SpellMechanic> loadMechanics(String spellId, ConfigurationSection section) {
        List<SpellMechanic> mechanics = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("mechanics")) {
            String type = normalizeToken(stringValue(entry.get("type"), null));
            MechanicTargetScope scope = mechanicTargetScope(entry.get("target-filter"));
            SpellMechanic mechanic = switch (type) {
                case "DAMAGE" -> new DamageMechanic(
                    doubleValue(entry.get("base"), 0.0D),
                    attributeValue(entry.get("scaling-attribute")),
                    doubleValue(entry.get("scale"), 0.0D)
                );
                case "HEAL" -> new HealMechanic(
                    doubleValue(entry.get("base"), 0.0D),
                    attributeValue(entry.get("scaling-attribute")),
                    doubleValue(entry.get("scale"), 0.0D)
                );
                case "APPLY_EFFECT", "APPLY_STATUS" -> {
                    String effectId = normalizeId(stringValue(entry.get("effect"), stringValue(entry.get("status"), "")));
                    if (effectId == null || effectId.isBlank()) {
                        throw new IllegalArgumentException("apply_effect mechanic is missing an effect id in " + spellId);
                    }
                    if (!effectService.hasRegisteredEffect(effectId)) {
                        throw new IllegalArgumentException("unknown custom effect '" + effectId + "' in " + spellId);
                    }
                    yield new ApplyEffectMechanic(
                        effectId,
                        Math.max(1, intValue(entry.get("stacks"), 1)),
                        intValue(entry.get("duration-ticks"), -1),
                        Math.max(0.01D, doubleValue(entry.get("potency"), 1.0D)),
                        booleanValue(entry.get("refresh-duration"), true)
                    );
                }
                case "REMOVE_EFFECT", "REMOVE_STATUS" -> {
                    String effectId = normalizeId(stringValue(entry.get("effect"), stringValue(entry.get("status"), "")));
                    if (effectId == null || effectId.isBlank()) {
                        throw new IllegalArgumentException("remove_effect mechanic is missing an effect id in " + spellId);
                    }
                    if (!effectService.hasRegisteredEffect(effectId)) {
                        throw new IllegalArgumentException("unknown custom effect '" + effectId + "' in " + spellId);
                    }
                    yield new RemoveEffectMechanic(effectId, intValue(entry.get("stacks"), 0));
                }
                case "CLEANSE", "CLEANSE_EFFECTS" -> new CleanseMechanic(
                    stringSetValue(entry.get("tags")),
                    intValue(entry.get("max-removals"), 1)
                );
                case "SEND_MESSAGE" -> new CasterMessageMechanic(stringValue(entry.get("message"), "A spell surges around you."));
                case "PLAY_SOUND" -> new PlaySoundMechanic(
                    soundValue(entry.get("sound")),
                    (float) doubleValue(entry.get("volume"), 1.0D),
                    (float) doubleValue(entry.get("pitch"), 1.0D)
                );
                case "SPAWN_PARTICLES", "SPAWN_PARTICLE" -> new ParticleMechanic(
                    particleValue(entry.get("particle")),
                    Math.max(1, intValue(entry.get("count"), 12)),
                    doubleValue(entry.get("offset-x"), 0.25D),
                    doubleValue(entry.get("offset-y"), 0.25D),
                    doubleValue(entry.get("offset-z"), 0.25D),
                    doubleValue(entry.get("extra"), 0.01D)
                );
                default -> throw new IllegalArgumentException(
                    "unsupported mechanic type '" + stringValue(entry.get("type"), "null") + "' in " + spellId
                );
            };
            mechanics.add(new ScopedMechanic(mechanic, scope));
        }
        return mechanics;
    }

    @SuppressWarnings("removal")
    private Sound soundValue(Object raw) {
        String value = normalizeSoundKey(raw);
        if (value == null) {
            throw new IllegalArgumentException("mechanic is missing a sound value.");
        }
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null) {
            throw new IllegalArgumentException("unknown sound '" + raw + "'.");
        }
        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) {
            try {
                sound = Sound.valueOf(key.getKey().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (sound == null) {
            throw new IllegalArgumentException("unknown sound '" + raw + "'.");
        }
        return sound;
    }

    private Particle particleValue(Object raw) {
        String value = normalizeToken(stringValue(raw, null));
        if (value == null) {
            throw new IllegalArgumentException("mechanic is missing a particle value.");
        }
        try {
            return Particle.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown particle '" + raw + "'.");
        }
    }

    private VortexAttribute attributeValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = normalizeToken(stringValue(raw, null));
        if (value == null) {
            return null;
        }
        try {
            return VortexAttribute.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown scaling attribute '" + raw + "'.");
        }
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

    private String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.toString();
        return value.isBlank() ? fallback : value;
    }

    private boolean booleanValue(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String string && !string.isBlank()) {
            return Boolean.parseBoolean(string);
        }
        return fallback;
    }

    private Set<String> stringSetValue(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                .filter(token -> token != null && !token.toString().isBlank())
                .map(token -> normalizeId(token.toString()))
                .filter(token -> token != null && !token.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (raw instanceof String string) {
            if (string.isBlank()) {
                return Set.of();
            }
            String[] parts = string.split(",");
            LinkedHashSet<String> tokens = new LinkedHashSet<>();
            for (String part : parts) {
                String token = normalizeId(part);
                if (token != null && !token.isBlank()) {
                    tokens.add(token);
                }
            }
            return tokens;
        }
        return Set.of();
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private String normalizeId(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeToken(String raw) {
        return raw == null ? null : raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
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

    private MechanicTargetScope mechanicTargetScope(Object raw) {
        String value = normalizeToken(stringValue(raw, "ANY"));
        try {
            return MechanicTargetScope.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown target-filter '" + raw + "'.");
        }
    }

    public record EditorSpellSnapshot(
        String id,
        String displayName,
        long cooldownMillis,
        int minLevel,
        List<String> requiredClasses,
        List<Map<String, Object>> targeters,
        List<Map<String, Object>> conditions,
        List<Map<String, Object>> mechanics,
        boolean builtIn
    ) {
    }

    public record ReloadResult(int loaded, int skipped, List<String> messages) {
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    public interface SpellCastListener {

        void onSpellCast(Player player, String spellId);
    }

    private enum MechanicTargetScope {
        ANY,
        SELF,
        PLAYERS,
        MOBS
    }

    private record RegisteredSpell(
        String id,
        String displayName,
        long cooldownMillis,
        CompiledSpell compiledSpell,
        SpellAccessRule accessRule
    ) {
    }

    /** Visual + audio fx attached to a spell. Any field may be null. */
    private record SpellVisuals(
        Sound castSound,
        Particle castParticle,
        Particle beamParticle,
        Particle impactParticle,
        Sound impactSound
    ) {
        static SpellVisuals beam(Sound castSound, Particle castParticle, Particle beam, Particle impact, Sound impactSound) {
            return new SpellVisuals(castSound, castParticle, beam, impact, impactSound);
        }
        static SpellVisuals burst(Sound castSound, Particle castParticle, Particle impact, Sound impactSound) {
            return new SpellVisuals(castSound, castParticle, null, impact, impactSound);
        }
        static SpellVisuals selfBuff(Sound castSound, Particle castParticle) {
            return new SpellVisuals(castSound, castParticle, null, null, null);
        }
    }

    private record SpellAccessRule(int minLevel, Set<VortexClass> requiredClasses) {

        private boolean matches(UnifiedPlayer profile, Optional<VortexClass> selectedClass) {
            if (profile.level() < minLevel) {
                return false;
            }
            if (requiredClasses.isEmpty()) {
                return true;
            }
            return selectedClass.map(requiredClasses::contains).orElse(false);
        }
    }

    private final class SimpleCompiledSpell implements CompiledSpell {

        private final String id;
        private final List<SpellTargeter> targeters;
        private final List<SpellCondition> conditions;
        private final List<SpellMechanic> mechanics;

        private SimpleCompiledSpell(String id, List<SpellTargeter> targeters, List<SpellCondition> conditions, List<SpellMechanic> mechanics) {
            this.id = id;
            this.targeters = List.copyOf(targeters);
            this.conditions = List.copyOf(conditions);
            this.mechanics = List.copyOf(mechanics);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public TriggerType triggerType() {
            return TriggerType.CAST;
        }

        @Override
        public List<SpellTargeter> targeters() {
            return targeters;
        }

        @Override
        public List<SpellCondition> conditions() {
            return conditions;
        }

        @Override
        public List<SpellMechanic> mechanics() {
            return mechanics;
        }

        @Override
        public CompletionStage<Void> execute(SpellContext context) {
            LinkedHashMap<String, SpellTarget> uniqueTargets = new LinkedHashMap<>();
            for (SpellTargeter targeter : targeters) {
                for (SpellTarget target : targeter.acquireTargets(context)) {
                    if (!isTargetAllowed(context, target)) {
                        continue;
                    }
                    uniqueTargets.putIfAbsent(targetKey(target), target);
                }
            }
            List<SpellTarget> targets = new ArrayList<>(uniqueTargets.values());
            if (targets.isEmpty()) {
                Object protectionMessage = context.state("protection.denied");
                if (protectionMessage instanceof String message && !message.isBlank()) {
                    return failedFuture(new IllegalStateException(message));
                }
                Location fallbackLocation = context.origin();
                if (fallbackLocation == null) {
                    return failedFuture(new IllegalStateException("No valid target found for " + NameFormat.friendly(id)));
                }
                targets = List.of(new SpellTarget(SpellTarget.TargetKind.LOCATION, null, fallbackLocation));
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            SpellVisuals visuals = context.state("spell.visuals") instanceof SpellVisuals sv ? sv : null;
            Player playerCaster = context.triggerSource() instanceof Player p ? p : null;
            for (SpellTarget target : targets) {
                boolean valid = conditions.stream().allMatch(condition -> condition.test(context, target));
                if (!valid) {
                    continue;
                }

                // Per-target beam/impact — plays for entity AND location targets so air casts show the beam too.
                boolean hasTargetFx = visuals != null && playerCaster != null
                    && (visuals.beamParticle() != null || visuals.impactParticle() != null || visuals.impactSound() != null);
                if (hasTargetFx) {
                    Location targetLoc = resolveLocation(context, target);
                    if (target.kind() == SpellTarget.TargetKind.ENTITY && target.entityId() != null) {
                        Entity ent = Bukkit.getEntity(target.entityId());
                        if (ent instanceof LivingEntity living) {
                            playBeamAndImpact(playerCaster, living, visuals);
                        } else if (targetLoc != null) {
                            playBeamAndImpactAt(playerCaster, targetLoc, visuals);
                        }
                    } else if (targetLoc != null) {
                        playBeamAndImpactAt(playerCaster, targetLoc, visuals);
                    }
                }

                for (SpellMechanic mechanic : mechanics) {
                    futures.add(mechanic.apply(context, target).toCompletableFuture());
                }
            }

            if (futures.isEmpty()) {
                return failedFuture(new IllegalStateException("Spell conditions failed for " + NameFormat.friendly(id)));
            }

            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }

        private boolean isTargetAllowed(SpellContext context, SpellTarget target) {
            if (!(context.triggerSource() instanceof Player player)) {
                return true;
            }
            Location location = resolveLocation(context, target);
            if (location == null) {
                return true;
            }

            ProtectionCheckResult result = protectionHookService.check(player, location, ProtectionAction.SPELL_TARGET);
            if (result.allowed()) {
                return true;
            }
            context.putState("protection.denied", ProtectionHookService.denyMessage(result, ProtectionAction.SPELL_TARGET));
            return false;
        }
    }

    private static final class SelfTargeter implements SpellTargeter {

        @Override
        public TargeterType type() {
            return TargeterType.SELF;
        }

        @Override
        public Collection<SpellTarget> acquireTargets(SpellContext context) {
            if (!(context.triggerSource() instanceof Player player)) {
                return List.of();
            }
            return List.of(new SpellTarget(SpellTarget.TargetKind.SELF, player.getUniqueId(), player.getLocation()));
        }
    }

    /**
     * Line-of-sight ray targeter. Casts a ray from the caster's eye in the direction
     * they are looking, hits the FIRST {@link LivingEntity} (any kind — monsters, animals,
     * villagers, players, etc.) up to {@code range} blocks. Optionally also stops at solid
     * blocks. If nothing is hit, returns a single LOCATION target at the end of the ray
     * so spells still resolve and play visuals when cast into the air.
     */
    private static final class RayTargeter implements SpellTargeter {

        private final double range;
        private final boolean blockedBySolids;

        private RayTargeter(double range, boolean blockedBySolids) {
            this.range = range;
            this.blockedBySolids = blockedBySolids;
        }

        @Override
        public TargeterType type() {
            return TargeterType.LINEAR;
        }

        @Override
        public Collection<SpellTarget> acquireTargets(SpellContext context) {
            if (!(context.triggerSource() instanceof Player player)) {
                return List.of();
            }
            org.bukkit.World world = player.getWorld();
            Location eye = player.getEyeLocation();
            org.bukkit.util.Vector direction = eye.getDirection().normalize();
            org.bukkit.util.RayTraceResult hit = world.rayTrace(
                eye,
                direction,
                range,
                org.bukkit.FluidCollisionMode.NEVER,
                true,
                0.3D,
                entity -> entity instanceof LivingEntity && entity.isValid() && !entity.getUniqueId().equals(player.getUniqueId())
            );
            if (hit != null && hit.getHitEntity() instanceof LivingEntity living) {
                // Only return entity if a block didn't intercept the ray first.
                if (!blockedBySolids || hit.getHitBlock() == null
                    || living.getLocation().distanceSquared(eye) <= hit.getHitPosition().toLocation(world).distanceSquared(eye)) {
                    return List.of(new SpellTarget(SpellTarget.TargetKind.ENTITY, living.getUniqueId(), living.getLocation()));
                }
            }
            // Fallback: location at end of ray (or block-hit point) so air-casts still resolve.
            Location end = hit != null && hit.getHitPosition() != null
                ? hit.getHitPosition().toLocation(world)
                : eye.clone().add(direction.multiply(range));
            return List.of(new SpellTarget(SpellTarget.TargetKind.LOCATION, null, end));
        }
    }

    private static final class NearbyMonsterTargeter implements SpellTargeter {

        private final double range;
        private final int limit;

        private NearbyMonsterTargeter(double range, int limit) {
            this.range = range;
            this.limit = limit;
        }

        @Override
        public TargeterType type() {
            return TargeterType.AREA;
        }

        @Override
        public Collection<SpellTarget> acquireTargets(SpellContext context) {
            if (!(context.triggerSource() instanceof Player player)) {
                return List.of();
            }

            return player.getNearbyEntities(range, range, range).stream()
                .filter(entity -> entity instanceof Monster && entity.isValid())
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation())))
                .limit(limit)
                .map(entity -> new SpellTarget(SpellTarget.TargetKind.ENTITY, entity.getUniqueId(), entity.getLocation()))
                .toList();
        }
    }

    private static final class NearbyPlayerTargeter implements SpellTargeter {

        private final double range;

        private NearbyPlayerTargeter(double range) {
            this.range = range;
        }

        @Override
        public TargeterType type() {
            return TargeterType.PARTY;
        }

        @Override
        public Collection<SpellTarget> acquireTargets(SpellContext context) {
            if (!(context.triggerSource() instanceof Player player)) {
                return List.of();
            }

            List<SpellTarget> targets = new ArrayList<>();
            targets.add(new SpellTarget(SpellTarget.TargetKind.SELF, player.getUniqueId(), player.getLocation()));
            player.getNearbyEntities(range, range, range).stream()
                .filter(entity -> entity instanceof Player && entity.isValid() && !entity.getUniqueId().equals(player.getUniqueId()))
                .map(entity -> new SpellTarget(SpellTarget.TargetKind.ENTITY, entity.getUniqueId(), entity.getLocation()))
                .forEach(targets::add);
            return targets;
        }
    }

    private static final class RequireTargetCondition implements SpellCondition {

        @Override
        public ConditionType type() {
            return ConditionType.HAS_TARGET;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            return target.entityId() != null || target.location() != null;
        }
    }

    private static final class HealthRatioCondition implements SpellCondition {

        private final double threshold;
        private final boolean below;

        private HealthRatioCondition(double threshold, boolean below) {
            this.threshold = threshold;
            this.below = below;
        }

        @Override
        public ConditionType type() {
            return below ? ConditionType.HAS_HEALTH_BELOW : ConditionType.HAS_HEALTH_ABOVE;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            LivingEntity livingEntity = targetEntity(target);
            if (livingEntity == null && context.triggerSource() instanceof LivingEntity source) {
                livingEntity = source;
            }
            if (livingEntity == null || livingEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null) {
                return false;
            }

            double maxHealth = livingEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            if (maxHealth <= 0.0D) {
                return false;
            }

            double ratio = livingEntity.getHealth() / maxHealth;
            return below ? ratio <= threshold : ratio >= threshold;
        }
    }

    private static final class MovementCondition implements SpellCondition {

        private final boolean moving;

        private MovementCondition(boolean moving) {
            this.moving = moving;
        }

        @Override
        public ConditionType type() {
            return moving ? ConditionType.CASTER_MOVING : ConditionType.CASTER_STATIONARY;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            if (!(context.triggerSource() instanceof Player player)) {
                return false;
            }
            boolean currentlyMoving = player.getVelocity().lengthSquared() > 0.01D;
            return moving == currentlyMoving;
        }
    }

    private static final class ChancePassCondition implements SpellCondition {

        private final double chance;

        private ChancePassCondition(double chance) {
            this.chance = Math.max(0.0D, Math.min(1.0D, chance));
        }

        @Override
        public ConditionType type() {
            return ConditionType.CHANCE_PASS;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            return Math.random() <= chance;
        }
    }

    private static final class PermissionCondition implements SpellCondition {

        private final String permission;

        private PermissionCondition(String permission) {
            this.permission = permission == null ? "" : permission;
        }

        @Override
        public ConditionType type() {
            return ConditionType.HAS_PERMISSION;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            return context.triggerSource() instanceof Player player && !permission.isBlank() && player.hasPermission(permission);
        }
    }

    private static final class SelfTargetCondition implements SpellCondition {

        @Override
        public ConditionType type() {
            return ConditionType.TARGET_IS_SELF;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            if (!(context.triggerSource() instanceof Player player)) {
                return false;
            }
            return target.kind() == SpellTarget.TargetKind.SELF || player.getUniqueId().equals(target.entityId());
        }
    }

    private static final class TargetEntityKindCondition implements SpellCondition {

        private final boolean playerTarget;

        private TargetEntityKindCondition(boolean playerTarget) {
            this.playerTarget = playerTarget;
        }

        @Override
        public ConditionType type() {
            return playerTarget ? ConditionType.TARGET_IS_PLAYER : ConditionType.TARGET_IS_MOB;
        }

        @Override
        public boolean test(SpellContext context, SpellTarget target) {
            if (target.kind() == SpellTarget.TargetKind.SELF) {
                return playerTarget && context.triggerSource() instanceof Player;
            }
            Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
            return playerTarget ? entity instanceof Player : entity instanceof Monster;
        }
    }

    private final class ScopedMechanic implements SpellMechanic {

        private final SpellMechanic delegate;
        private final MechanicTargetScope scope;

        private ScopedMechanic(SpellMechanic delegate, MechanicTargetScope scope) {
            this.delegate = delegate;
            this.scope = scope;
        }

        @Override
        public MechanicType type() {
            return delegate.type();
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            return matchesTargetScope(context, target, scope)
                ? delegate.apply(context, target)
                : CompletableFuture.completedFuture(null);
        }
    }

    private final class DamageMechanic implements SpellMechanic {

        private final double baseDamage;
        private final VortexAttribute scalingAttribute;
        private final double scale;

        private DamageMechanic(double baseDamage, VortexAttribute scalingAttribute, double scale) {
            this.baseDamage = baseDamage;
            this.scalingAttribute = scalingAttribute;
            this.scale = scale;
        }

        @Override
        public MechanicType type() {
            return MechanicType.DAMAGE;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
            if (!(entity instanceof LivingEntity livingEntity)) {
                // Air cast — no entity to damage. Visuals already played at the location by the
                // compiled spell, so simply complete.
                return CompletableFuture.completedFuture(null);
            }

            Player caster = context.triggerSource() instanceof Player player ? player : null;
            double amount = baseDamage + scalingAmount(context);
            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runEntity(livingEntity, "spell-damage-" + livingEntity.getUniqueId(), () -> {
                if (caster != null) {
                    livingEntity.damage(amount, caster);
                } else {
                    livingEntity.damage(amount);
                }
                future.complete(null);
            }, () -> future.complete(null));
            return future;
        }

        private double scalingAmount(SpellContext context) {
            if (scalingAttribute == null) {
                return 0.0D;
            }
            return context.caster().stat(scalingAttribute).current() * scale;
        }
    }

    private final class HealMechanic implements SpellMechanic {

        private final double baseHeal;
        private final VortexAttribute scalingAttribute;
        private final double scale;

        private HealMechanic(double baseHeal, VortexAttribute scalingAttribute, double scale) {
            this.baseHeal = baseHeal;
            this.scalingAttribute = scalingAttribute;
            this.scale = scale;
        }

        @Override
        public MechanicType type() {
            return MechanicType.HEAL;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
            if (!(entity instanceof LivingEntity livingEntity)) {
                return CompletableFuture.completedFuture(null);
            }

            double amount = baseHeal + scalingAmount(context);
            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runEntity(livingEntity, "spell-heal-" + livingEntity.getUniqueId(), () -> {
                double maxHealth = livingEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                    ? livingEntity.getHealth()
                    : livingEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                livingEntity.setHealth(Math.min(maxHealth, livingEntity.getHealth() + amount));
                future.complete(null);
            }, () -> future.complete(null));
            return future;
        }

        private double scalingAmount(SpellContext context) {
            if (scalingAttribute == null) {
                return 0.0D;
            }
            return context.caster().stat(scalingAttribute).current() * scale;
        }
    }

    private final class CasterMessageMechanic implements SpellMechanic {

        private final String message;

        private CasterMessageMechanic(String message) {
            this.message = message;
        }

        @Override
        public MechanicType type() {
            return MechanicType.SEND_MESSAGE;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            if (!(context.triggerSource() instanceof Player player)) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runEntity(player, "spell-message-" + player.getUniqueId(), () -> {
                messageService.send(player, message);
                future.complete(null);
            });
            return future;
        }
    }

    private final class ParticleMechanic implements SpellMechanic {

        private final Particle particle;
        private final int count;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final double extra;

        private ParticleMechanic(Particle particle, int count, double offsetX, double offsetY, double offsetZ, double extra) {
            this.particle = particle;
            this.count = count;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.extra = extra;
        }

        @Override
        public MechanicType type() {
            return MechanicType.SPAWN_PARTICLES;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            Location location = resolveLocation(context, target);
            if (location == null || location.getWorld() == null) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runRegion(location, "spell-particles-" + context.executionId(), () -> {
                location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
                future.complete(null);
            });
            return future;
        }
    }

    private final class PlaySoundMechanic implements SpellMechanic {

        private final Sound sound;
        private final float volume;
        private final float pitch;

        private PlaySoundMechanic(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }

        @Override
        public MechanicType type() {
            return MechanicType.PLAY_SOUND;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            Location location = resolveLocation(context, target);
            if (location == null || location.getWorld() == null) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runRegion(location, "spell-sound-" + context.executionId(), () -> {
                location.getWorld().playSound(location, sound, volume, pitch);
                future.complete(null);
            });
            return future;
        }
    }

    private final class ApplyEffectMechanic implements SpellMechanic {

        private final String effectId;
        private final int stacks;
        private final int durationTicks;
        private final double potency;
        private final boolean refreshDuration;

        private ApplyEffectMechanic(String effectId, int stacks, int durationTicks, double potency, boolean refreshDuration) {
            this.effectId = effectId;
            this.stacks = stacks;
            this.durationTicks = durationTicks;
            this.potency = potency;
            this.refreshDuration = refreshDuration;
        }

        @Override
        public MechanicType type() {
            return MechanicType.APPLY_STATUS;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            return effectService.applyEffect(
                context,
                target,
                effectId,
                stacks,
                durationTicks,
                potency,
                refreshDuration
            );
        }
    }

    private final class RemoveEffectMechanic implements SpellMechanic {

        private final String effectId;
        private final int stacksToRemove;

        private RemoveEffectMechanic(String effectId, int stacksToRemove) {
            this.effectId = effectId;
            this.stacksToRemove = stacksToRemove;
        }

        @Override
        public MechanicType type() {
            return MechanicType.REMOVE_STATUS;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            return effectService.removeEffect(context, target, effectId, stacksToRemove);
        }
    }

    private final class CleanseMechanic implements SpellMechanic {

        private final Set<String> tags;
        private final int maxRemovals;

        private CleanseMechanic(Set<String> tags, int maxRemovals) {
            this.tags = Set.copyOf(tags);
            this.maxRemovals = maxRemovals;
        }

        @Override
        public MechanicType type() {
            return MechanicType.CLEANSE;
        }

        @Override
        public CompletionStage<Void> apply(SpellContext context, SpellTarget target) {
            return effectService.cleanseEffects(context, target, tags, maxRemovals);
        }
    }

    private static LivingEntity targetEntity(SpellTarget target) {
        Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
        return entity instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private static Location resolveLocation(SpellContext context, SpellTarget target) {
        if (target.location() != null) {
            return target.location();
        }
        Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
        if (entity != null) {
            return entity.getLocation();
        }
        return context.origin();
    }

    private static String targetKey(SpellTarget target) {
        if (target.entityId() != null) {
            return "entity:" + target.entityId();
        }
        if (target.location() != null && target.location().getWorld() != null) {
            Location location = target.location();
            return "location:" + location.getWorld().getUID() + ":" + location.getX() + ":" + location.getY() + ":" + location.getZ();
        }
        return "fallback:" + target.kind();
    }

    private static boolean matchesTargetScope(SpellContext context, SpellTarget target, MechanicTargetScope scope) {
        return switch (scope) {
            case ANY -> true;
            case SELF -> context.triggerSource() instanceof Player player
                && (target.kind() == SpellTarget.TargetKind.SELF || player.getUniqueId().equals(target.entityId()));
            case PLAYERS -> {
                if (target.kind() == SpellTarget.TargetKind.SELF) {
                    yield context.triggerSource() instanceof Player;
                }
                Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
                yield entity instanceof Player;
            }
            case MOBS -> {
                Entity entity = target.entityId() == null ? null : Bukkit.getEntity(target.entityId());
                yield entity instanceof Monster;
            }
        };
    }
}
