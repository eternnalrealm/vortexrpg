package io.vortexcore.rpg;

import io.vortexcore.item.CustomItemService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.progression.VortexAttribute;
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
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class ClassSkillService {

    private static final int[] CLASS_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private static final String META_CLASS = "rpg.class";
    private static final String META_SKILL_POINTS = "rpg.skill_points";
    private static final String META_UNLOCKED_SKILLS = "rpg.unlocked_skills";
    private static final String META_ACTIVE_SPELL = "rpg.active_spell";
    private static final String META_ASCENSION = "rpg.ascension";
    private static final String META_REWARDED_CLASSES = "rpg.class_rewarded";

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final CustomItemService customItemService;
    private final MessageService messageService;
    private final int ascensionLevelThreshold;
    private final Map<String, ClassProfileDefinition> classProfilesById;
    private final Set<String> builtinClassProfileIds;
    private final Set<String> customClassProfileIds;
    private final Map<VortexClass, List<VortexSkillDefinition>> skillTrees;
    private final Map<String, VortexSkillDefinition> skillsById;
    private final Map<VortexClass, List<ClassAscension>> ascensionsByClass;
    private final Map<String, ClassAscension> ascensionsById;
    private final CopyOnWriteArrayList<LiveAttributeAugmenter> liveAttributeAugmenters;
    private PlayerProfileService playerProfileService;

    public ClassSkillService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        CustomItemService customItemService,
        MessageService messageService,
        int ascensionLevelThreshold
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.customItemService = customItemService;
        this.messageService = messageService;
        this.ascensionLevelThreshold = Math.max(2, ascensionLevelThreshold);
        this.classProfilesById = new LinkedHashMap<>();
        this.builtinClassProfileIds = new LinkedHashSet<>();
        this.customClassProfileIds = new LinkedHashSet<>();
        this.skillTrees = new LinkedHashMap<>();
        this.skillsById = new LinkedHashMap<>();
        this.ascensionsByClass = new LinkedHashMap<>();
        this.ascensionsById = new LinkedHashMap<>();
        this.liveAttributeAugmenters = new CopyOnWriteArrayList<>();
        resetClassProfiles();
        registerDefaults();
    }

    public void setPlayerProfileService(PlayerProfileService playerProfileService) {
        this.playerProfileService = playerProfileService;
    }

    public List<ClassProfileDefinition> registeredClassProfiles() {
        return List.copyOf(classProfilesById.values());
    }

    public Collection<String> registeredClassIds() {
        return List.copyOf(classProfilesById.keySet());
    }

    public Optional<ClassProfileDefinition> classProfile(String classId) {
        String normalized = normalizeId(classId);
        if (normalized == null) {
            return Optional.empty();
        }
        ClassProfileDefinition definition = classProfilesById.get(normalized);
        if (definition != null) {
            return Optional.of(definition);
        }
        VortexClass archetype = VortexClass.parse(normalized);
        return Optional.ofNullable(archetype == null ? null : classProfilesById.get(archetype.commandId()));
    }

    public Optional<ClassProfileDefinition> selectedClassDefinition(UnifiedPlayer profile) {
        return classProfile(profile.metadata().get(META_CLASS));
    }

    public Optional<VortexClass> selectedClass(UnifiedPlayer profile) {
        return selectedClassDefinition(profile).map(ClassProfileDefinition::archetype);
    }

    public boolean hasClass(UnifiedPlayer profile) {
        return selectedClassDefinition(profile).isPresent();
    }

    public ReloadResult reloadCustomClasses() {
        resetClassProfiles();

        File file = new File(plugin.getDataFolder(), "classes.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection classesSection = configuration.getConfigurationSection("classes");
        if (classesSection == null) {
            return new ReloadResult(0, 0, List.of("No classes section found in classes.yml."));
        }

        int loaded = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        for (String rawId : classesSection.getKeys(false)) {
            ConfigurationSection section = classesSection.getConfigurationSection(rawId);
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
            Optional<ClassProfileDefinition> base = classProfile(id);
            try {
                VortexClass archetype = resolveArchetype(section.getString("base-archetype"), base.orElse(null), id);
                Material icon = resolveMaterial(section.getString("icon"), base.map(ClassProfileDefinition::icon).orElse(archetype.icon()), id);
                String starterSpellId = normalizeId(section.getString("starter-spell", base.map(ClassProfileDefinition::starterSpellId).orElse(archetype.starterSpellId())));
                ClassProfileDefinition definition = new ClassProfileDefinition(
                    id,
                    archetype,
                    section.getString("display-name", base.map(ClassProfileDefinition::displayName).orElse(NameFormat.friendly(id))),
                    section.getString("description", base.map(ClassProfileDefinition::description).orElse(archetype.description())),
                    icon,
                    starterSpellId,
                    loadBaseStats(section.getConfigurationSection("base-stats"), base.map(ClassProfileDefinition::baseStats).orElse(archetype.baseStats())),
                    base.map(ClassProfileDefinition::builtIn).orElse(false)
                );
                classProfilesById.put(id, definition);
                customClassProfileIds.add(id);
                loaded++;
            } catch (IllegalArgumentException exception) {
                skipped++;
                messages.add(id + ": " + exception.getMessage());
            }
        }
        return new ReloadResult(loaded, skipped, List.copyOf(messages));
    }

    public int ascensionLevelThreshold() {
        return ascensionLevelThreshold;
    }

    public Optional<ClassAscension> activeAscension(UnifiedPlayer profile) {
        String value = profile.metadata().get(META_ASCENSION);
        return Optional.ofNullable(value == null ? null : ascensionsById.get(value.toLowerCase(Locale.ROOT)));
    }

    public List<ClassAscension> ascensionsFor(UnifiedPlayer profile) {
        return selectedClass(profile).map(vortexClass -> ascensionsByClass.getOrDefault(vortexClass, List.of())).orElse(List.of());
    }

    public boolean canAscend(UnifiedPlayer profile) {
        return hasClass(profile) && profile.level() >= ascensionLevelThreshold && activeAscension(profile).isEmpty();
    }

    public int unspentSkillPoints(UnifiedPlayer profile) {
        return parseInt(profile.metadata().get(META_SKILL_POINTS), 0);
    }

    public List<VortexSkillDefinition> skillsFor(UnifiedPlayer profile) {
        return selectedClass(profile).map(vortexClass -> skillTrees.getOrDefault(vortexClass, List.of())).orElse(List.of());
    }

    public Set<String> unlockedSkillIds(UnifiedPlayer profile) {
        return parseCsv(profile.metadata().get(META_UNLOCKED_SKILLS));
    }

    public List<VortexSkillDefinition> unlockedSkills(UnifiedPlayer profile) {
        Set<String> unlockedIds = unlockedSkillIds(profile);
        return skillsFor(profile).stream().filter(skill -> unlockedIds.contains(skill.id())).toList();
    }

    public Set<String> availableSpellIds(UnifiedPlayer profile) {
        LinkedHashSet<String> spells = new LinkedHashSet<>();
        selectedClassDefinition(profile)
            .map(ClassProfileDefinition::starterSpellId)
            .filter(spellId -> spellId != null && !spellId.isBlank())
            .ifPresent(spells::add);
        unlockedSkills(profile).stream()
            .map(VortexSkillDefinition::grantedSpellId)
            .filter(spellId -> spellId != null && !spellId.isBlank())
            .forEach(spells::add);
        activeAscension(profile)
            .map(ClassAscension::grantedSpellId)
            .filter(spellId -> spellId != null && !spellId.isBlank())
            .ifPresent(spells::add);
        return Set.copyOf(spells);
    }

    public Optional<String> activeSpellId(UnifiedPlayer profile) {
        String active = profile.metadata().get(META_ACTIVE_SPELL);
        if (active != null && !active.isBlank()) {
            return Optional.of(active.toLowerCase(Locale.ROOT));
        }
        return selectedClassDefinition(profile).map(ClassProfileDefinition::starterSpellId);
    }

    public void openClassMenu(Player player, UnifiedPlayer profile) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Class Hall"), 6);
        Optional<ClassProfileDefinition> selected = selectedClassDefinition(profile);
        builder.button(4, infoIcon(profile), context -> CompletableFuture.completedFuture(null));
        int index = 0;
        for (ClassProfileDefinition classProfile : registeredClassProfiles()) {
            if (index >= CLASS_SLOTS.length) {
                break;
            }
            builder.button(CLASS_SLOTS[index++], iconForClass(classProfile, selected.map(value -> value.id().equals(classProfile.id())).orElse(false)), context -> {
                chooseClass(context.player(), profile, classProfile.id())
                    .thenRun(() -> messageService.send(context.player(), "Selected class: " + classProfile.displayName()));
                return CompletableFuture.completedFuture(null);
            }, true);
        }
        builder.button(49, ascensionInfoIcon(profile), context -> {
            openAscensionMenu(context.player(), profile);
            return CompletableFuture.completedFuture(null);
        });
        menuBuilder.open(player, builder.build());
    }

    public void openAscensionMenu(Player player, UnifiedPlayer profile) {
        if (!hasClass(profile)) {
            messageService.send(player, "Choose a class first with /class choose <class>.");
            openClassMenu(player, profile);
            return;
        }
        menuBuilder.open(player, buildAscensionMenu(player, profile));
    }

    public void openClassSwapMenu(Player player, UnifiedPlayer profile) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
            net.kyori.adventure.text.Component.text("\u26A1 Class Swap"), 6);

        // Warning header at centre of top row
        ItemStack warning = new ItemStack(Material.BARRIER);
        ItemMeta warnMeta = warning.getItemMeta();
        warnMeta.displayName(Component.text("\u26A0 Swap Warning"));
        warnMeta.lore(List.of(
            Component.text("Swapping RESETS skills and ascension."),
            Component.text("Your XP and level are preserved."),
            Component.text("Click a class below to confirm the swap.")
        ));
        warning.setItemMeta(warnMeta);
        builder.button(4, warning, context -> CompletableFuture.completedFuture(null));

        // Class selection row
        Optional<ClassProfileDefinition> selected = selectedClassDefinition(profile);
        int idx = 0;
        for (ClassProfileDefinition classProfile : registeredClassProfiles()) {
            if (idx >= CLASS_SLOTS.length) {
                break;
            }
            boolean isCurrent = selected.map(value -> value.id().equals(classProfile.id())).orElse(false);
            ItemStack icon = iconForClass(classProfile, isCurrent);
            builder.button(CLASS_SLOTS[idx++], icon, context -> {
                if (isCurrent) {
                    messageService.send(context.player(), "You are already the " + classProfile.displayName() + " class.");
                    return CompletableFuture.completedFuture(null);
                }
                return playerRequestClassSwap(context.player(), profile, classProfile.id())
                    .thenRun(() -> messageService.send(context.player(), "<green>Class swapped to " + classProfile.displayName() + ".</green>"))
                    .exceptionally(error -> {
                        messageService.send(context.player(), "<red>" + friendlyError(error) + "</red>");
                        return null;
                    });
            }, true);
        }

        // Cancel button
        ItemStack cancel = icon(Material.RED_STAINED_GLASS_PANE, "Cancel", List.of("Close without swapping."));
        builder.button(49, cancel, context -> CompletableFuture.completedFuture(null), true);

        menuBuilder.open(player, builder.build());
    }

    public CompletableFuture<Void> playerRequestClassSwap(Player player, UnifiedPlayer profile, VortexClass vortexClass) {
        return playerRequestClassSwap(player, profile, vortexClass.commandId());
    }

    public CompletableFuture<Void> playerRequestClassSwap(Player player, UnifiedPlayer profile, String classId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "class-swap-" + player.getUniqueId(), () -> {
            if (!plugin.getConfig().getBoolean("class-selection.allow-player-swap", false)) {
                future.completeExceptionally(new IllegalStateException("Class swapping is currently disabled on this server."));
                return;
            }
            ClassProfileDefinition classProfile = classProfile(classId)
                .orElseThrow(() -> new IllegalStateException("Unknown class " + classId + "."));
            ClassStateSnapshot previousState = snapshotClassState(profile);
            profile.putMetadata(META_CLASS, classProfile.id());
            profile.putMetadata(META_SKILL_POINTS, Integer.toString(Math.max(1, profile.level() - 1)));
            profile.putMetadata(META_UNLOCKED_SKILLS, "");
            profile.putMetadata(META_ASCENSION, "");
            profile.putMetadata(META_ACTIVE_SPELL, classProfile.starterSpellId());
            syncClassBonuses(profile, previousState);
            applyLiveAttributes(player, profile);
            applyClassSelectionSideEffects(player, profile, classProfile.archetype(), false);
            future.complete(null);
        });
        return future;
    }

    public CompletableFuture<Void> chooseClass(Player player, UnifiedPlayer profile, VortexClass vortexClass) {
        return chooseClass(player, profile, vortexClass.commandId());
    }

    public CompletableFuture<Void> chooseClass(Player player, UnifiedPlayer profile, String classId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "choose-class-" + player.getUniqueId(), () -> {
            ClassProfileDefinition classProfile = classProfile(classId)
                .orElseThrow(() -> new IllegalStateException("Unknown class " + classId + "."));
            Optional<ClassProfileDefinition> existing = selectedClassDefinition(profile);
            if (existing.isPresent()) {
                if (existing.get().id().equals(classProfile.id())) {
                    future.complete(null);
                    return;
                }
                future.completeExceptionally(new IllegalStateException("Class already selected as " + existing.get().displayName()));
                return;
            }

            ClassStateSnapshot previousState = snapshotClassState(profile);
            profile.putMetadata(META_CLASS, classProfile.id());
            profile.putMetadata(META_SKILL_POINTS, Integer.toString(Math.max(1, profile.level() - 1)));
            profile.putMetadata(META_UNLOCKED_SKILLS, "");
            profile.putMetadata(META_ACTIVE_SPELL, classProfile.starterSpellId());
            syncClassBonuses(profile, previousState);
            applyLiveAttributes(player, profile);
            applyClassSelectionSideEffects(player, profile, classProfile.archetype(), false);
            future.complete(null);
        });
        return future;
    }

    public CompletableFuture<Void> forceSetClass(Player player, UnifiedPlayer profile, VortexClass vortexClass) {
        return forceSetClass(player, profile, vortexClass.commandId());
    }

    public CompletableFuture<Void> forceSetClass(Player player, UnifiedPlayer profile, String classId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "force-class-" + player.getUniqueId(), () -> {
            ClassProfileDefinition classProfile = classProfile(classId)
                .orElseThrow(() -> new IllegalStateException("Unknown class " + classId + "."));
            ClassStateSnapshot previousState = snapshotClassState(profile);

            profile.putMetadata(META_CLASS, classProfile.id());
            profile.putMetadata(META_SKILL_POINTS, Integer.toString(Math.max(1, profile.level() - 1)));
            profile.putMetadata(META_UNLOCKED_SKILLS, "");
            profile.putMetadata(META_ASCENSION, "");
            profile.putMetadata(META_ACTIVE_SPELL, classProfile.starterSpellId());

            syncClassBonuses(profile, previousState);
            applyLiveAttributes(player, profile);
            applyClassSelectionSideEffects(player, profile, classProfile.archetype(), true);
            future.complete(null);
        });
        return future;
    }

    public CompletableFuture<AscensionResult> ascend(Player player, UnifiedPlayer profile, String ascensionId) {
        CompletableFuture<AscensionResult> future = new CompletableFuture<>();
        scheduler.runEntity(player, "class-ascend-" + player.getUniqueId(), () -> {
            if (!hasClass(profile)) {
                future.complete(new AscensionResult(false, "Choose a class before selecting an ascension."));
                return;
            }
            if (profile.level() < ascensionLevelThreshold) {
                future.complete(new AscensionResult(false, "Reach level " + ascensionLevelThreshold + " to unlock class ascensions."));
                return;
            }
            if (activeAscension(profile).isPresent()) {
                future.complete(new AscensionResult(false, "You already chose the " + activeAscension(profile).orElseThrow().displayName() + " ascension."));
                return;
            }
            ClassAscension ascension = ascensionsById.get(ascensionId.toLowerCase(Locale.ROOT));
            if (ascension == null) {
                future.complete(new AscensionResult(false, "Unknown ascension " + ascensionId + "."));
                return;
            }
            Optional<VortexClass> selected = selectedClass(profile);
            if (selected.isEmpty() || ascension.archetype() != selected.get()) {
                future.complete(new AscensionResult(false, "That ascension does not belong to your class path."));
                return;
            }

            ClassStateSnapshot previousState = snapshotClassState(profile);
            profile.putMetadata(META_ASCENSION, ascension.id());
            if (ascension.grantedSpellId() != null && !ascension.grantedSpellId().isBlank()) {
                profile.putMetadata(META_ACTIVE_SPELL, ascension.grantedSpellId());
            }
            syncClassBonuses(profile, previousState);
            applyLiveAttributes(player, profile);
            future.complete(new AscensionResult(true, "Ascended as " + ascension.displayName() + "."));
        });
        return future;
    }

    public CompletableFuture<SkillUnlockResult> unlockSkill(Player player, UnifiedPlayer profile, String skillId) {
        CompletableFuture<SkillUnlockResult> future = new CompletableFuture<>();
        scheduler.runEntity(player, "unlock-skill-" + player.getUniqueId(), () -> {
            VortexSkillDefinition skill = skillsById.get(skillId.toLowerCase(Locale.ROOT));
            if (skill == null) {
                future.complete(new SkillUnlockResult(false, "Unknown skill " + skillId));
                return;
            }
            Optional<VortexClass> selected = selectedClass(profile);
            if (selected.isEmpty()) {
                future.complete(new SkillUnlockResult(false, "Choose a class first with /class."));
                return;
            }
            if (selected.get() != skill.archetype()) {
                future.complete(new SkillUnlockResult(false, "That skill belongs to the " + skill.archetype().displayName() + " path."));
                return;
            }
            Set<String> unlocked = new LinkedHashSet<>(unlockedSkillIds(profile));
            if (unlocked.contains(skill.id())) {
                future.complete(new SkillUnlockResult(false, skill.displayName() + " is already unlocked."));
                return;
            }
            if (profile.level() < skill.requiredLevel()) {
                future.complete(new SkillUnlockResult(false, "Requires level " + skill.requiredLevel() + "."));
                return;
            }
            int points = unspentSkillPoints(profile);
            if (points < skill.cost()) {
                future.complete(new SkillUnlockResult(false, "You need " + skill.cost() + " skill point(s)."));
                return;
            }

            ClassStateSnapshot previousState = snapshotClassState(profile);
            unlocked.add(skill.id());
            profile.putMetadata(META_UNLOCKED_SKILLS, String.join(",", unlocked));
            profile.putMetadata(META_SKILL_POINTS, Integer.toString(points - skill.cost()));
            if (skill.grantedSpellId() != null && !skill.grantedSpellId().isBlank()) {
                profile.putMetadata(META_ACTIVE_SPELL, skill.grantedSpellId());
            }

            syncClassBonuses(profile, previousState);
            applyLiveAttributes(player, profile);
            future.complete(new SkillUnlockResult(true, "Unlocked " + skill.displayName() + "."));
        });
        return future;
    }

    public void setActiveSpell(Player player, UnifiedPlayer profile, String spellId) {
        if (!availableSpellIds(profile).contains(spellId)) {
            throw new IllegalArgumentException("Spell " + spellId + " is not unlocked.");
        }
        forceActiveSpell(profile, spellId);
    }

    public void forceActiveSpell(UnifiedPlayer profile, String spellId) {
        profile.putMetadata(META_ACTIVE_SPELL, spellId.toLowerCase(Locale.ROOT));
    }

    public void grantSkillPoints(Player player, UnifiedPlayer profile, int amount) {
        if (amount <= 0 || !hasClass(profile)) {
            return;
        }
        int updated = unspentSkillPoints(profile) + amount;
        profile.putMetadata(META_SKILL_POINTS, Integer.toString(updated));
        scheduler.runEntity(player, "skill-point-grant-" + player.getUniqueId(), () -> applyLiveAttributes(player, profile));
    }

    public void adjustSkillPoints(Player player, UnifiedPlayer profile, int delta) {
        if (delta == 0 || !hasClass(profile)) {
            return;
        }
        int updated = Math.max(0, unspentSkillPoints(profile) + delta);
        profile.putMetadata(META_SKILL_POINTS, Integer.toString(updated));
        scheduler.runEntity(player, "skill-point-adjust-" + player.getUniqueId(), () -> applyLiveAttributes(player, profile));
    }

    public void addLiveAttributeAugmenter(LiveAttributeAugmenter augmenter) {
        if (augmenter != null) {
            liveAttributeAugmenters.addIfAbsent(augmenter);
        }
    }

    public void applyLiveAttributes(Player player, UnifiedPlayer profile) {
        for (VortexAttribute attribute : VortexAttribute.values()) {
            profile.stat(attribute).resetTransientBonuses();
        }
        for (LiveAttributeAugmenter augmenter : liveAttributeAugmenters) {
            try {
                augmenter.augment(player, profile);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Live attribute augmenter failed: " + throwable.getMessage());
            }
        }

        double vitality = profile.stat(VortexAttribute.VITALITY).current();
        double strength = profile.stat(VortexAttribute.STRENGTH).current();
        double dexterity = profile.stat(VortexAttribute.DEXTERITY).current();
        double spirit = profile.stat(VortexAttribute.SPIRIT).current();

        setAttribute(player.getAttribute(Attribute.MAX_HEALTH), 20.0D + (vitality * 2.5D));
        setAttribute(player.getAttribute(Attribute.ATTACK_DAMAGE), 1.0D + (strength * 0.35D));
        setAttribute(player.getAttribute(Attribute.MOVEMENT_SPEED), 0.1D + (dexterity * 0.0025D));
        setAttribute(player.getAttribute(Attribute.ARMOR), vitality * 0.12D + spirit * 0.08D);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(Math.min(player.getHealth(), maxHealth.getValue()));
        }
    }

    /**
     * Re-grants the starter kit items for the player's current class.
     * Does NOT block on grant-once-per-class; always gives the configured items.
     *
     * @return the class whose kit was issued, or empty if the player has no class
     */
    public Optional<VortexClass> grantClassKit(Player player, UnifiedPlayer profile) {
        Optional<VortexClass> classOpt = selectedClass(profile);
        classOpt.ifPresent(vortexClass -> grantClassKitItems(player, vortexClass));
        return classOpt;
    }

    /**
     * Admin override — grants the starter kit items for any specified class to a player.
     */
    public void grantClassKitTo(Player player, VortexClass vortexClass) {
        grantClassKitItems(player, vortexClass);
    }

    private void grantClassKitItems(Player player, VortexClass vortexClass) {
        String path = "class-selection.rewards.classes." + vortexClass.commandId() + ".items";
        if (!plugin.getConfig().isConfigurationSection(path)) {
            messageService.send(player,
                "<yellow>No kit items are configured for " + vortexClass.displayName() + ".</yellow>");
            return;
        }
        List<String> given = new ArrayList<>();
        for (String itemId : plugin.getConfig().getConfigurationSection(path).getKeys(false)) {
            int amount = Math.max(1, plugin.getConfig().getInt(path + "." + itemId, 1));
            if (customItemService.definition(itemId).isEmpty()) {
                plugin.getLogger().warning("[ClassKit] Unknown item " + itemId + " in kit for " + vortexClass.commandId());
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(customItemService.create(itemId, amount));
            leftovers.values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            given.add(amount + "× " + NameFormat.friendly(itemId));
        }
        if (!given.isEmpty()) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.1F);
            messageService.send(player,
                "<gradient:#8B5CF6:#3B82F6><bold>⚔ Class Kit</bold></gradient> <dark_gray>»</dark_gray> "
                + "<green>" + vortexClass.displayName() + " kit issued:</green> <white>"
                + String.join(", ", given) + "</white>");
        }
    }

    private void applyClassSelectionSideEffects(Player player, UnifiedPlayer profile, VortexClass vortexClass, boolean adminChange) {
        if (shouldGrantClassRewards(profile, vortexClass, adminChange)) {
            grantClassRewards(player, profile, vortexClass);
        }
        if (!adminChange || plugin.getConfig().getBoolean("class-selection.celebrate-on-admin-change", true)) {
            playClassSelectionCelebration(player, vortexClass);
        }
    }

    private boolean shouldGrantClassRewards(UnifiedPlayer profile, VortexClass vortexClass, boolean adminChange) {
        if (adminChange && !plugin.getConfig().getBoolean("class-selection.rewards.grant-on-admin-change", false)) {
            return false;
        }
        Set<String> rewardedClasses = parseCsv(profile.metadata().get(META_REWARDED_CLASSES));
        return !plugin.getConfig().getBoolean("class-selection.rewards.grant-once-per-class", true)
            || !rewardedClasses.contains(vortexClass.commandId());
    }

    private void grantClassRewards(Player player, UnifiedPlayer profile, VortexClass vortexClass) {
        String path = "class-selection.rewards.classes." + vortexClass.commandId();
        long experienceReward = Math.max(0L, plugin.getConfig().getLong(path + ".experience", 0L));
        long breachTokenReward = Math.max(0L, plugin.getConfig().getLong(path + ".breach-tokens", 0L));

        List<String> grantedRewards = new ArrayList<>();
        if (playerProfileService != null) {
            if (experienceReward > 0L) {
                playerProfileService.grantExperience(player, experienceReward, vortexClass.displayName() + " class selection");
                grantedRewards.add("+" + experienceReward + " XP");
            }
            if (breachTokenReward > 0L) {
                playerProfileService.grantBreachTokens(player, breachTokenReward, vortexClass.displayName() + " class selection");
                grantedRewards.add("+" + breachTokenReward + " breach tokens");
            }
        }

        if (plugin.getConfig().isConfigurationSection(path + ".items")) {
            for (String itemId : plugin.getConfig().getConfigurationSection(path + ".items").getKeys(false)) {
                int amount = Math.max(1, plugin.getConfig().getInt(path + ".items." + itemId, 1));
                if (customItemService.definition(itemId).isEmpty()) {
                    plugin.getLogger().warning("Skipped unknown class reward item " + itemId + " for class " + vortexClass.commandId() + ".");
                    continue;
                }
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(customItemService.create(itemId, amount));
                leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                grantedRewards.add(amount + "x " + NameFormat.friendly(itemId));
            }
        }

        Set<String> rewardedClasses = new LinkedHashSet<>(parseCsv(profile.metadata().get(META_REWARDED_CLASSES)));
        rewardedClasses.add(vortexClass.commandId());
        profile.putMetadata(META_REWARDED_CLASSES, String.join(",", rewardedClasses));

        if (!grantedRewards.isEmpty()) {
            messageService.send(player, "Class rewards: " + String.join(", ", grantedRewards) + ".");
        }
    }

    private void playClassSelectionCelebration(Player player, VortexClass vortexClass) {
        if (plugin.getConfig().getBoolean("class-selection.effects.particles", true)) {
            Particle primaryParticle = switch (vortexClass) {
                case MAGE -> Particle.ENCHANT;
                case KNIGHT -> Particle.CRIT;
                case RANGER -> Particle.HAPPY_VILLAGER;
                case CLERIC -> Particle.GLOW;
                case ENGINEER -> Particle.WAX_ON;
                case BEASTMASTER -> Particle.TOTEM_OF_UNDYING;
            };
            Particle secondaryParticle = switch (vortexClass) {
                case MAGE -> Particle.END_ROD;
                case KNIGHT -> Particle.FLAME;
                case RANGER -> Particle.SPORE_BLOSSOM_AIR;
                case CLERIC -> Particle.HEART;
                case ENGINEER -> Particle.ELECTRIC_SPARK;
                case BEASTMASTER -> Particle.COMPOSTER;
            };
            player.getWorld().spawnParticle(primaryParticle, player.getLocation().add(0.0D, 1.0D, 0.0D), 40, 0.45D, 0.75D, 0.45D, 0.02D);
            player.getWorld().spawnParticle(secondaryParticle, player.getLocation().add(0.0D, 1.2D, 0.0D), 24, 0.35D, 0.6D, 0.35D, 0.01D);
        }

        if (plugin.getConfig().getBoolean("class-selection.effects.firework", true)) {
            Firework firework = player.getWorld().spawn(player.getLocation().add(0.0D, 1.0D, 0.0D), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(0);
            meta.addEffect(
                FireworkEffect.builder()
                    .withColor(classColor(vortexClass))
                    .withFade(Color.WHITE)
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .trail(true)
                    .flicker(true)
                    .build()
            );
            firework.setFireworkMeta(meta);
            firework.detonate();
        }

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.15F);
    }

    private Color classColor(VortexClass vortexClass) {
        return switch (vortexClass) {
            case MAGE -> Color.fromRGB(116, 123, 255);
            case KNIGHT -> Color.fromRGB(245, 191, 79);
            case RANGER -> Color.fromRGB(94, 201, 123);
            case CLERIC -> Color.fromRGB(255, 241, 154);
            case ENGINEER -> Color.fromRGB(255, 145, 77);
            case BEASTMASTER -> Color.fromRGB(127, 95, 67);
        };
    }

    public String summaryLine(UnifiedPlayer profile) {
        String className = selectedClassDefinition(profile).map(ClassProfileDefinition::displayName).orElse("Unchosen");
        String activeSpell = activeSpellId(profile).map(NameFormat::friendly).orElse("None");
        String ascension = activeAscension(profile).map(ClassAscension::displayName).orElse(
            profile.level() >= ascensionLevelThreshold ? "Ready" : "Locked until " + ascensionLevelThreshold
        );
        return "Class: " + className
            + " | Level: " + profile.level()
            + " | Skill Points: " + unspentSkillPoints(profile)
            + " | Active Spell: " + activeSpell
            + " | Ascension: " + ascension;
    }

    public List<String> ascensionStatusLines(UnifiedPlayer profile) {
        List<String> lines = new ArrayList<>();
        lines.add("Ascensions unlock at level " + ascensionLevelThreshold + ".");
        activeAscension(profile).ifPresentOrElse(
            ascension -> lines.add("Active Ascension: " + ascension.displayName()),
            () -> lines.add("Active Ascension: None")
        );
        for (ClassAscension ascension : ascensionsFor(profile)) {
            lines.add(ascension.displayName() + " | " + ascension.description());
        }
        return List.copyOf(lines);
    }

    private void setAttribute(AttributeInstance attribute, double value) {
        if (attribute != null) {
            attribute.setBaseValue(value);
        }
    }

    private ItemStack iconForClass(ClassProfileDefinition classProfile, boolean active) {
        List<String> lore = new ArrayList<>();
        lore.add(classProfile.description());
        lore.add("Starter Spell: " + NameFormat.friendly(classProfile.starterSpellId()));
        if (!classProfile.id().equals(classProfile.archetype().commandId())) {
            lore.add("Archetype: " + classProfile.archetype().displayName());
        }
        classProfile.baseStats().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().name()))
            .forEach(entry -> lore.add(NameFormat.friendly(entry.getKey().name()) + ": " + formatDouble(entry.getValue())));
        if (active) {
            lore.add("Selected on this profile.");
        }
        if (classProfile.builtIn()) {
            lore.add("Built-in class profile.");
        } else {
            lore.add("Custom class profile.");
        }
        return customIcon(classIconId(classProfile.archetype()), classProfile.icon(), classProfile.displayName(), lore);
    }

    private ItemStack iconForSkill(VortexSkillDefinition skill, UnifiedPlayer profile, boolean unlocked) {
        List<String> lore = new ArrayList<>();
        lore.add(skill.description());
        lore.add("Required Level: " + skill.requiredLevel());
        lore.add("Skill Cost: " + skill.cost());
        if (skill.grantedSpellId() != null && !skill.grantedSpellId().isBlank()) {
            lore.add("Grants Spell: " + NameFormat.friendly(skill.grantedSpellId()));
        }
        skill.permanentBonuses().forEach((attribute, bonus) -> lore.add("+" + formatDouble(bonus) + " " + NameFormat.friendly(attribute.name())));
        lore.add(unlocked ? "Unlocked. Click to equip spell if available." : "Click to unlock.");
        if (profile.level() < skill.requiredLevel()) {
            lore.add("You need more levels for this skill.");
        }
        return icon(skill.icon(), skill.displayName(), lore);
    }

    private ItemStack ascensionInfoIcon(UnifiedPlayer profile) {
        List<String> lore = new ArrayList<>();
        activeAscension(profile).ifPresentOrElse(
            ascension -> lore.add("Active: " + ascension.displayName()),
            () -> lore.add(profile.level() >= ascensionLevelThreshold ? "Ascension is ready to choose." : "Unlocks at level " + ascensionLevelThreshold + ".")
        );
        lore.add("Click to view class spec paths.");
        return customIcon("gui_spell_relic", Material.NETHER_STAR, "Ascensions", lore);
    }

    private ItemStack iconForAscension(UnifiedPlayer profile, ClassAscension ascension) {
        boolean active = activeAscension(profile).map(value -> value.id().equals(ascension.id())).orElse(false);
        List<String> lore = new ArrayList<>();
        lore.add(ascension.description());
        lore.add("Unlocks at level " + ascensionLevelThreshold + ".");
        ascension.permanentBonuses().forEach((attribute, bonus) ->
            lore.add("+" + formatDouble(bonus) + " " + NameFormat.friendly(attribute.name()))
        );
        if (ascension.grantedSpellId() != null && !ascension.grantedSpellId().isBlank()) {
            lore.add("Signature Spell: " + NameFormat.friendly(ascension.grantedSpellId()));
        }
        lore.add(active ? "Selected on this profile." : (profile.level() >= ascensionLevelThreshold ? "Click to ascend." : "Reach the level requirement first."));
        return customIcon(classIconId(ascension.archetype()), ascension.icon(), ascension.displayName(), lore);
    }

    private ItemStack infoIcon(UnifiedPlayer profile) {
        List<String> lore = new ArrayList<>();
        lore.add(summaryLine(profile));
        lore.add("Unlocked Skills: " + Math.max(0, unlockedSkillIds(profile).size()));
        lore.add("Spells: " + String.join(", ", availableSpellIds(profile).stream().map(NameFormat::friendly).toList()));
        return customIcon("gui_adventure_scroll", Material.BOOK, "Profile Summary", lore);
    }

    private ItemStack customIcon(String iconId, Material fallback, String name, List<String> loreLines) {
        return iconId == null ? icon(fallback, name, loreLines) : customItemService.createIcon(iconId, name, loreLines);
    }

    private String classIconId(VortexClass vortexClass) {
        if (vortexClass == null) {
            return null;
        }
        return switch (vortexClass) {
            case KNIGHT -> "gui_knight_crest";
            case MAGE -> "gui_mage_prism";
            case RANGER -> "gui_ranger_mark";
            case CLERIC -> "gui_cleric_seal";
            case BEASTMASTER -> "gui_beastmaster_fang";
            case ENGINEER -> "gui_engineer_gear";
        };
    }

    private ItemStack icon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String friendlyError(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? "That class action could not be completed." : message;
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    private String normalizeId(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private void resetClassProfiles() {
        classProfilesById.clear();
        builtinClassProfileIds.clear();
        customClassProfileIds.clear();
        for (VortexClass archetype : VortexClass.values()) {
            ClassProfileDefinition profile = ClassProfileDefinition.fromArchetype(archetype);
            classProfilesById.put(profile.id(), profile);
            builtinClassProfileIds.add(profile.id());
        }
    }

    private VortexClass resolveArchetype(String raw, ClassProfileDefinition fallback, String classId) {
        VortexClass archetype = raw == null || raw.isBlank()
            ? (fallback == null ? null : fallback.archetype())
            : VortexClass.parse(raw);
        if (archetype == null) {
            throw new IllegalArgumentException("unknown base archetype for class " + classId);
        }
        return archetype;
    }

    private Material resolveMaterial(String raw, Material fallback, String classId) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown icon material '" + raw + "' in " + classId);
        }
    }

    private Map<VortexAttribute, Double> loadBaseStats(ConfigurationSection section, Map<VortexAttribute, Double> fallback) {
        EnumMap<VortexAttribute, Double> stats = new EnumMap<>(VortexAttribute.class);
        if (fallback != null) {
            stats.putAll(fallback);
        }
        for (VortexAttribute attribute : VortexAttribute.values()) {
            if (section != null && section.contains(attribute.name().toLowerCase(Locale.ROOT))) {
                stats.put(attribute, section.getDouble(attribute.name().toLowerCase(Locale.ROOT), stats.getOrDefault(attribute, 0.0D)));
            } else {
                stats.putIfAbsent(attribute, 0.0D);
            }
        }
        return Map.copyOf(stats);
    }

    private ClassStateSnapshot snapshotClassState(UnifiedPlayer profile) {
        return new ClassStateSnapshot(
            selectedClassDefinition(profile).map(ClassProfileDefinition::id).orElse(null),
            unlockedSkillIds(profile),
            activeAscension(profile).map(ClassAscension::id).orElse(null)
        );
    }

    private void syncClassBonuses(UnifiedPlayer profile, ClassStateSnapshot previousState) {
        applyPermanentBonuses(profile, aggregateClassBonuses(previousState), -1.0D);
        applyPermanentBonuses(profile, aggregateClassBonuses(snapshotClassState(profile)), 1.0D);
    }

    private Map<VortexAttribute, Double> aggregateClassBonuses(ClassStateSnapshot state) {
        EnumMap<VortexAttribute, Double> totals = new EnumMap<>(VortexAttribute.class);

        if (state.classId() != null) {
            ClassProfileDefinition classProfile = classProfile(state.classId()).orElse(null);
            if (classProfile != null) {
                classProfile.baseStats().forEach((attribute, bonus) -> totals.merge(attribute, bonus, (a, b) -> a + b));
            }
        }

        for (String skillId : state.unlockedSkillIds()) {
            VortexSkillDefinition skill = skillsById.get(skillId);
            if (skill != null) {
                skill.permanentBonuses().forEach((attribute, bonus) -> totals.merge(attribute, bonus, (a, b) -> a + b));
            }
        }

        if (state.ascensionId() != null) {
            ClassAscension ascension = ascensionsById.get(state.ascensionId());
            if (ascension != null) {
                ascension.permanentBonuses().forEach((attribute, bonus) -> totals.merge(attribute, bonus, (a, b) -> a + b));
            }
        }

        return totals;
    }

    private void applyPermanentBonuses(UnifiedPlayer profile, Map<VortexAttribute, Double> bonuses, double direction) {
        bonuses.forEach((attribute, bonus) -> {
            double currentBase = profile.stat(attribute).snapshot().baseValue();
            profile.stat(attribute).setBase(currentBase + (bonus * direction));
        });
    }

    private VortexMenuBuilder.MenuDefinition buildAscensionMenu(Player player, UnifiedPlayer profile) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Ascension Hall"), 3);
        List<ClassAscension> ascensions = ascensionsFor(profile);
        int[] slots = {11, 15};
        for (int index = 0; index < Math.min(slots.length, ascensions.size()); index++) {
            ClassAscension ascension = ascensions.get(index);
            builder.button(slots[index], iconForAscension(profile, ascension), context -> {
                ascend(context.player(), profile, ascension.id())
                    .thenAccept(result -> messageService.send(context.player(), result.message()));
                return CompletableFuture.completedFuture(null);
            });
        }
        builder.button(22, icon(Material.BOOK, "Back to Class Hall", List.of("Return to your class overview.")), context -> {
            openClassMenu(context.player(), profile);
            return CompletableFuture.completedFuture(null);
        });
        return builder.build();
    }

    public void openSkillMenu(Player player, UnifiedPlayer profile) {
        if (!hasClass(profile)) {
            openClassMenu(player, profile);
            return;
        }

        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Skill Atlas"), 6);
        List<VortexSkillDefinition> skills = skillsFor(profile);
        Set<String> unlocked = unlockedSkillIds(profile);

        int slot = 10;
        for (VortexSkillDefinition skill : skills) {
            if (slot >= 44) {
                break;
            }

            boolean unlockedSkill = unlocked.contains(skill.id());
            builder.button(slot++, iconForSkill(skill, profile, unlockedSkill), context -> {
                if (unlockedSkill) {
                    if (skill.grantedSpellId() != null && !skill.grantedSpellId().isBlank()) {
                        setActiveSpell(context.player(), profile, skill.grantedSpellId());
                        messageService.send(context.player(), "Active spell set to " + NameFormat.friendly(skill.grantedSpellId()));
                    } else {
                        messageService.send(context.player(), skill.displayName() + " is already unlocked.");
                    }
                } else {
                    unlockSkill(context.player(), profile, skill.id())
                        .thenAccept(result -> messageService.send(context.player(), result.message()));
                }
                return CompletableFuture.completedFuture(null);
            }, false);
        }

        builder.button(49, infoIcon(profile), context -> {
            messageService.send(context.player(), summaryLine(profile));
            return CompletableFuture.completedFuture(null);
        });

        menuBuilder.open(player, builder.build());
    }

    private void registerDefaults() {
        register(new VortexSkillDefinition(
            "iron_body",
            VortexClass.KNIGHT,
            "Iron Body",
            "Raises your maximum survivability.",
            Material.IRON_CHESTPLATE,
            2,
            1,
            Map.of(VortexAttribute.VITALITY, 3.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "war_stomp",
            VortexClass.KNIGHT,
            "War Stomp",
            "Unlock a crushing frontline shockwave.",
            Material.IRON_BOOTS,
            4,
            1,
            Map.of(VortexAttribute.STRENGTH, 2.0D, VortexAttribute.VITALITY, 1.0D),
            "war_stomp"
        ));
        register(new VortexSkillDefinition(
            "bulwark_mastery",
            VortexClass.KNIGHT,
            "Bulwark Mastery",
            "Deepens your frontline durability.",
            Material.SHIELD,
            6,
            2,
            Map.of(VortexAttribute.VITALITY, 4.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));

        register(new VortexSkillDefinition(
            "focus_channel",
            VortexClass.MAGE,
            "Focus Channel",
            "Boosts your wisdom and intelligence growth.",
            Material.ENCHANTED_BOOK,
            2,
            1,
            Map.of(VortexAttribute.INTELLIGENCE, 3.0D, VortexAttribute.WISDOM, 2.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "nova_burst",
            VortexClass.MAGE,
            "Nova Burst",
            "Unlock a short-range arcane detonation.",
            Material.END_CRYSTAL,
            4,
            1,
            Map.of(VortexAttribute.INTELLIGENCE, 2.0D),
            "nova_burst"
        ));
        register(new VortexSkillDefinition(
            "astral_depth",
            VortexClass.MAGE,
            "Astral Depth",
            "Extends your raw magical throughput.",
            Material.AMETHYST_CLUSTER,
            6,
            2,
            Map.of(VortexAttribute.INTELLIGENCE, 4.0D, VortexAttribute.WISDOM, 3.0D),
            null
        ));

        register(new VortexSkillDefinition(
            "keen_eye",
            VortexClass.RANGER,
            "Keen Eye",
            "Tightens your precision and pursuit speed.",
            Material.SPYGLASS,
            2,
            1,
            Map.of(VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.WISDOM, 1.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "volley",
            VortexClass.RANGER,
            "Volley",
            "Unlock a multi-target ranged barrage.",
            Material.CROSSBOW,
            4,
            1,
            Map.of(VortexAttribute.DEXTERITY, 2.0D, VortexAttribute.STRENGTH, 1.0D),
            "volley"
        ));
        register(new VortexSkillDefinition(
            "windstep",
            VortexClass.RANGER,
            "Windstep",
            "Adds mobility and relentless pressure.",
            Material.FEATHER,
            6,
            2,
            Map.of(VortexAttribute.DEXTERITY, 4.0D, VortexAttribute.VITALITY, 2.0D),
            null
        ));

        register(new VortexSkillDefinition(
            "grace",
            VortexClass.CLERIC,
            "Grace",
            "Strengthens your healing and survival.",
            Material.GHAST_TEAR,
            2,
            1,
            Map.of(VortexAttribute.SPIRIT, 3.0D, VortexAttribute.VITALITY, 1.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "radiant_pulse",
            VortexClass.CLERIC,
            "Radiant Pulse",
            "Unlock a healing wave around the caster.",
            Material.BEACON,
            4,
            1,
            Map.of(VortexAttribute.SPIRIT, 2.0D, VortexAttribute.WISDOM, 1.0D),
            "radiant_pulse"
        ));
        register(new VortexSkillDefinition(
            "sanctified_core",
            VortexClass.CLERIC,
            "Sanctified Core",
            "Expands your spiritual presence and sustain.",
            Material.TOTEM_OF_UNDYING,
            6,
            2,
            Map.of(VortexAttribute.SPIRIT, 4.0D, VortexAttribute.WISDOM, 2.0D),
            null
        ));

        register(new VortexSkillDefinition(
            "pack_instinct",
            VortexClass.BEASTMASTER,
            "Pack Instinct",
            "Strengthens the bond between you and your beasts.",
            Material.BONE,
            2,
            1,
            Map.of(VortexAttribute.SPIRIT, 3.0D, VortexAttribute.VITALITY, 1.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "savage_charge",
            VortexClass.BEASTMASTER,
            "Savage Charge",
            "Unlock a ferocious lunge alongside your beasts.",
            Material.IRON_HORSE_ARMOR,
            4,
            1,
            Map.of(VortexAttribute.STRENGTH, 2.0D, VortexAttribute.DEXTERITY, 1.0D),
            "savage_charge"
        ));
        register(new VortexSkillDefinition(
            "primal_roar",
            VortexClass.BEASTMASTER,
            "Primal Roar",
            "Deepens your savage resilience and pack ferocity.",
            Material.GOAT_HORN,
            6,
            2,
            Map.of(VortexAttribute.SPIRIT, 4.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));

        register(new VortexSkillDefinition(
            "calibrate_optics",
            VortexClass.ENGINEER,
            "Calibrate Optics",
            "Refines targeting precision for your deployed gadgets.",
            Material.SPYGLASS,
            2,
            1,
            Map.of(VortexAttribute.INTELLIGENCE, 3.0D, VortexAttribute.DEXTERITY, 1.0D),
            null
        ));
        register(new VortexSkillDefinition(
            "concussion_mine",
            VortexClass.ENGINEER,
            "Concussion Mine",
            "Unlock a proximity-detonated explosive device.",
            Material.TNT,
            4,
            1,
            Map.of(VortexAttribute.INTELLIGENCE, 2.0D, VortexAttribute.WISDOM, 1.0D),
            "concussion_mine"
        ));
        register(new VortexSkillDefinition(
            "overclock",
            VortexClass.ENGINEER,
            "Overclock",
            "Pushes your gadgets beyond safe operating limits.",
            Material.COMPARATOR,
            6,
            2,
            Map.of(VortexAttribute.INTELLIGENCE, 4.0D, VortexAttribute.DEXTERITY, 3.0D),
            null
        ));

        registerAscension(new ClassAscension(
            "vanguard",
            VortexClass.KNIGHT,
            "Vanguard",
            "Trade restraint for battlefield pressure and shield-breaking momentum.",
            Material.NETHERITE_AXE,
            Map.of(VortexAttribute.STRENGTH, 5.0D, VortexAttribute.VITALITY, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "bastion",
            VortexClass.KNIGHT,
            "Bastion",
            "Anchor the line with heavier armor scaling and resilient spirit.",
            Material.SHIELD,
            Map.of(VortexAttribute.VITALITY, 5.0D, VortexAttribute.SPIRIT, 2.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "arcanist",
            VortexClass.MAGE,
            "Arcanist",
            "Push pure spell throughput and cleaner burst windows.",
            Material.END_CRYSTAL,
            Map.of(VortexAttribute.INTELLIGENCE, 5.0D, VortexAttribute.WISDOM, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "spellblade",
            VortexClass.MAGE,
            "Spellblade",
            "Blend movement and magic into faster skirmish pressure.",
            Material.AMETHYST_SHARD,
            Map.of(VortexAttribute.INTELLIGENCE, 3.0D, VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "marksman",
            VortexClass.RANGER,
            "Marksman",
            "Lean into long-range execution and perfect follow-up shots.",
            Material.SPECTRAL_ARROW,
            Map.of(VortexAttribute.DEXTERITY, 5.0D, VortexAttribute.WISDOM, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "pathfinder",
            VortexClass.RANGER,
            "Pathfinder",
            "Trade some peak burst for mobility and relentless survival.",
            Material.COMPASS,
            Map.of(VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.VITALITY, 3.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "oracle",
            VortexClass.CLERIC,
            "Oracle",
            "Deepen healing cadence and spiritual control over the field.",
            Material.BEACON,
            Map.of(VortexAttribute.SPIRIT, 5.0D, VortexAttribute.WISDOM, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "templar",
            VortexClass.CLERIC,
            "Templar",
            "Shift toward a frontline support role with stronger durability.",
            Material.GOLDEN_CHESTPLATE,
            Map.of(VortexAttribute.SPIRIT, 3.0D, VortexAttribute.VITALITY, 3.0D, VortexAttribute.STRENGTH, 2.0D),
            null
        ));

        registerAscension(new ClassAscension(
            "alpha",
            VortexClass.BEASTMASTER,
            "Alpha",
            "Become the pack leader with devastating coordinated strikes.",
            Material.WOLF_ARMOR,
            Map.of(VortexAttribute.STRENGTH, 5.0D, VortexAttribute.SPIRIT, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "wildspeaker",
            VortexClass.BEASTMASTER,
            "Wildspeaker",
            "Deepen the spiritual bond with nature for sustain and resilience.",
            Material.BAMBOO,
            Map.of(VortexAttribute.SPIRIT, 5.0D, VortexAttribute.VITALITY, 2.0D, VortexAttribute.WISDOM, 1.0D),
            null
        ));

        registerAscension(new ClassAscension(
            "artificer",
            VortexClass.ENGINEER,
            "Artificer",
            "Master the craft of arcane-infused gadgets with devastating effect.",
            Material.BREWING_STAND,
            Map.of(VortexAttribute.INTELLIGENCE, 5.0D, VortexAttribute.WISDOM, 3.0D),
            null
        ));
        registerAscension(new ClassAscension(
            "demolitionist",
            VortexClass.ENGINEER,
            "Demolitionist",
            "Trade finesse for raw explosive power and area dominance.",
            Material.TNT_MINECART,
            Map.of(VortexAttribute.INTELLIGENCE, 3.0D, VortexAttribute.STRENGTH, 3.0D, VortexAttribute.DEXTERITY, 2.0D),
            null
        ));
    }

    private void register(VortexSkillDefinition definition) {
        skillTrees.computeIfAbsent(definition.archetype(), ignored -> new ArrayList<>()).add(definition);
        skillsById.put(definition.id(), definition);
    }

    private void registerAscension(ClassAscension ascension) {
        ascensionsByClass.computeIfAbsent(ascension.archetype(), ignored -> new ArrayList<>()).add(ascension);
        ascensionsById.put(ascension.id(), ascension);
    }

    private record ClassStateSnapshot(String classId, Set<String> unlockedSkillIds, String ascensionId) {
    }

    public record ReloadResult(int loaded, int skipped, List<String> messages) {
    }

    public record SkillUnlockResult(boolean success, String message) {
    }

    public record AscensionResult(boolean success, String message) {
    }

    public record ClassAscension(
        String id,
        VortexClass archetype,
        String displayName,
        String description,
        Material icon,
        Map<VortexAttribute, Double> permanentBonuses,
        String grantedSpellId
    ) {
    }

    @FunctionalInterface
    public interface LiveAttributeAugmenter {
        void augment(Player player, UnifiedPlayer profile);
    }
}
