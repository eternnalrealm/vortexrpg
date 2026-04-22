package io.vortexcore.profession;

import io.vortexcore.economy.DynamicMarketManager;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.item.CustomItemDefinition;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.math.ExperienceCurve;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.effects.EffectService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class ProfessionService implements Listener {

    private static final String META_LEVEL_PREFIX = "profession.level.";
    private static final String META_XP_PREFIX = "profession.xp.";
    private static final String META_MASTERY_PREFIX = "profession.mastery.";
    private static final String PERM_STATION = "vortex.player.professions.station";
    private static final String PERM_GATHER = "vortex.player.professions.gather";
    private static final String PERM_CRAFT = "vortex.player.professions.craft";

    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final PlayerProfileService playerProfileService;
    private final DynamicMarketManager marketManager;
    private final ProtectionHookService protectionHookService;
    private final EffectService effectService;
    private final CustomItemService customItemService;
    private final MessageService messageService;
    private final ExperienceCurve curve;
    private final boolean stationOpenRequiresSneak;
    private final int masteryLevelThreshold;
    private final NamespacedKey petTierKey;
    private final NamespacedKey petBaseHealthKey;
    private final NamespacedKey petBaseDamageKey;
    private final NamespacedKey petBaseSpeedKey;
    private final Map<Material, GatherReward> gatherRewards;
    private final Map<ProfessionType, List<ProfessionRecipe>> recipesByProfession;
    private final Map<Material, ProfessionType> stationLookup;
    private final Map<ProfessionType, ProfessionMastery> masteries;
    private final Map<ProfessionType, List<ProfessionUnlock>> unlocksByProfession;
    private final Map<java.util.UUID, Integer> swordComboCounter;
    private final Map<java.util.UUID, Long> parryWindows;
    private final Map<java.util.UUID, Long> bladeStormCooldowns;
    private final Map<java.util.UUID, Long> rainOfArrowsCooldowns;
    private final Map<java.util.UUID, Long> alphaCommandCooldowns;
    private final Map<java.util.UUID, java.util.UUID> activeTurrets;
    private final Map<java.util.UUID, java.util.UUID> activeShockTraps;
    private final Map<java.util.UUID, java.util.UUID> bondedCompanions;
    private ProfessionActivityListener activityListener;

    public ProfessionService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        PlayerProfileService playerProfileService,
        DynamicMarketManager marketManager,
        ProtectionHookService protectionHookService,
        EffectService effectService,
        CustomItemService customItemService,
        MessageService messageService,
        ExperienceCurve curve,
        boolean stationOpenRequiresSneak,
        int masteryLevelThreshold,
        long miningOreExperience,
        long miningStoneExperience,
        long foragingLogExperience,
        long farmingHarvestExperience,
        long fishingCatchExperience
    ) {
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.playerProfileService = playerProfileService;
        this.marketManager = marketManager;
        this.protectionHookService = protectionHookService;
        this.effectService = effectService;
        this.customItemService = customItemService;
        this.messageService = messageService;
        this.curve = curve;
        this.stationOpenRequiresSneak = stationOpenRequiresSneak;
        this.masteryLevelThreshold = Math.max(5, masteryLevelThreshold);
        this.petTierKey = new NamespacedKey(plugin, "pet_mastery_tier");
        this.petBaseHealthKey = new NamespacedKey(plugin, "pet_base_health");
        this.petBaseDamageKey = new NamespacedKey(plugin, "pet_base_damage");
        this.petBaseSpeedKey = new NamespacedKey(plugin, "pet_base_speed");
        this.gatherRewards = new LinkedHashMap<>();
        this.recipesByProfession = new EnumMap<>(ProfessionType.class);
        this.stationLookup = new LinkedHashMap<>();
        this.masteries = new EnumMap<>(ProfessionType.class);
        this.unlocksByProfession = new EnumMap<>(ProfessionType.class);
        this.swordComboCounter = new ConcurrentHashMap<>();
        this.parryWindows = new ConcurrentHashMap<>();
        this.bladeStormCooldowns = new ConcurrentHashMap<>();
        this.rainOfArrowsCooldowns = new ConcurrentHashMap<>();
        this.alphaCommandCooldowns = new ConcurrentHashMap<>();
        this.activeTurrets = new ConcurrentHashMap<>();
        this.activeShockTraps = new ConcurrentHashMap<>();
        this.bondedCompanions = new ConcurrentHashMap<>();

        registerGathering(miningOreExperience, miningStoneExperience, foragingLogExperience, farmingHarvestExperience, fishingCatchExperience);
        registerRecipes();
        registerMarketItems();
        registerUnlocks();
        registerMasteries();
        registerCustomItems();
    }

    public void openLedger(Player player) {
        menuBuilder.open(player, buildLedgerMenu(player));
    }

    public void openCompanionHud(Player player) {
        menuBuilder.open(player, buildCompanionHud(player));
    }

    public void openEngineeringConsole(Player player) {
        menuBuilder.open(player, buildEngineeringConsole(player));
    }

    public void openStationMenu(Player player, ProfessionType profession) {
        openStationMenu(player, profession, player.getLocation());
    }

    public void openStationMenu(Player player, ProfessionType profession, org.bukkit.Location anchorLocation) {
        if (profession == null || !profession.craftingProfession()) {
            scheduler.runEntity(player, "profession-station-invalid-" + player.getUniqueId(), () ->
                messageService.send(player, "That profession does not have a crafting station.")
            );
            return;
        }
        ProtectionCheckResult result = protectionHookService.check(player, anchorLocation, ProtectionAction.STATION_USE);
        if (!result.allowed()) {
            scheduler.runEntity(player, "profession-station-denied-" + player.getUniqueId(), () ->
                messageService.send(player, ProtectionHookService.denyMessage(result, ProtectionAction.STATION_USE))
            );
            return;
        }
        menuBuilder.open(player, buildStationMenu(player, profession));
    }

    public List<String> stationNames() {
        return recipesByProfession.keySet().stream().filter(ProfessionType::craftingProfession).map(ProfessionType::id).toList();
    }

    public void setActivityListener(ProfessionActivityListener activityListener) {
        this.activityListener = activityListener;
    }

    public ProfessionProgress progress(UnifiedPlayer profile, ProfessionType type) {
        long experience = readLong(profile, META_XP_PREFIX + type.id());
        int level = Math.max(1, readInt(profile, META_LEVEL_PREFIX + type.id(), curve.solveLevel(experience)));
        int resolvedLevel = Math.max(level, curve.solveLevel(experience));
        if (resolvedLevel != level) {
            profile.putMetadata(META_LEVEL_PREFIX + type.id(), Integer.toString(resolvedLevel));
        }
        reconcileMastery(profile, type, null);
        return new ProfessionProgress(type, resolvedLevel, experience, Math.max(0L, curve.totalExperienceToReach(resolvedLevel + 1) - experience));
    }

    public String summaryLine(UnifiedPlayer profile) {
        return "Professions | Combat: "
            + compact(profile, ProfessionType.SWORDSMANSHIP) + " "
            + compact(profile, ProfessionType.AXEMANSHIP) + " "
            + compact(profile, ProfessionType.ARCHERY) + " "
            + compact(profile, ProfessionType.SPELLCRAFT) + " "
            + compact(profile, ProfessionType.DEFENSE) + " "
            + compact(profile, ProfessionType.DUAL_WIELD) + " "
            + compact(profile, ProfessionType.BEAST_MASTERY)
            + " | Gathering: "
            + compact(profile, ProfessionType.MINING) + " "
            + compact(profile, ProfessionType.FORAGING) + " "
            + compact(profile, ProfessionType.FARMING) + " "
            + compact(profile, ProfessionType.FISHING)
            + " | Crafting: "
            + compact(profile, ProfessionType.SMITHING) + " "
            + compact(profile, ProfessionType.FLETCHING) + " "
            + compact(profile, ProfessionType.ARMORSMITHING) + " "
            + compact(profile, ProfessionType.ENGINEERING) + " "
            + compact(profile, ProfessionType.ALCHEMY) + " "
            + compact(profile, ProfessionType.COOKING)
            + " | Masteries: " + unlockedMasteryCount(profile) + "/" + masteries.size();
    }

    public List<String> detailLines(UnifiedPlayer profile) {
        List<String> lines = new ArrayList<>();
        for (ProfessionType type : ProfessionType.values()) {
            ProfessionProgress progress = progress(profile, type);
            String mastery = hasMastery(profile, type) ? " | Mastery: " + mastery(type).displayName() : "";
            String nextUnlock = nextUnlockLine(type, progress.level());
            lines.add(
                type.displayName() + ": Level " + progress.level()
                    + " | XP " + progress.experience()
                    + " | To Next " + progress.experienceToNextLevel()
                    + mastery
                    + (nextUnlock == null ? "" : " | Next: " + nextUnlock)
            );
        }
        return List.copyOf(lines);
    }

    public int masteryLevelThreshold() {
        return masteryLevelThreshold;
    }

    public boolean hasMastery(UnifiedPlayer profile, ProfessionType type) {
        return Boolean.parseBoolean(profile.metadata().getOrDefault(META_MASTERY_PREFIX + type.id(), "false"));
    }

    public ProfessionMastery mastery(ProfessionType type) {
        return masteries.get(type);
    }

    public List<String> masteryLines(UnifiedPlayer profile) {
        List<String> lines = new ArrayList<>();
        lines.add("Profession masteries unlock at level " + masteryLevelThreshold + ".");
        for (ProfessionType type : ProfessionType.values()) {
            ProfessionMastery mastery = masteries.get(type);
            String status = hasMastery(profile, type) ? "Unlocked" : "Locked";
            lines.add(type.displayName() + " | " + mastery.displayName() + " | " + status + " | " + mastery.description());
            unlocksByProfession.getOrDefault(type, List.of()).stream()
                .limit(3)
                .forEach(unlock -> lines.add("  L" + unlock.level() + " " + unlock.title() + " | " + unlock.description()));
        }
        return List.copyOf(lines);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission(PERM_GATHER)) {
            return;
        }
        GatherReward reward = gatherReward(event.getBlock());
        if (reward == null) {
            return;
        }
        if (!protectionHookService.allows(event.getPlayer(), event.getBlock().getLocation(), ProtectionAction.GATHER)) {
            return;
        }
        scheduler.runEntity(event.getPlayer(), "profession-gather-" + event.getPlayer().getUniqueId(), () ->
            grantGatheringExperience(event.getPlayer(), reward.profession(), reward.experience(), reward.reason())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (!(event.getCaught() instanceof Item) || event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!event.getPlayer().hasPermission(PERM_GATHER)) {
            return;
        }
        scheduler.runEntity(event.getPlayer(), "profession-fish-" + event.getPlayer().getUniqueId(), () ->
            grantGatheringExperience(event.getPlayer(), ProfessionType.FISHING, 16L, "landing a clean catch")
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
            && isSword(event.getPlayer().getInventory().getItemInMainHand().getType())
            && progress(playerProfileService.profile(event.getPlayer()), ProfessionType.SWORDSMANSHIP).level() >= 35) {
            parryWindows.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 900L);
        }
        String professionItemId = professionItemId(event.getItem());
        if (professionItemId != null
            && event.getAction() == Action.RIGHT_CLICK_BLOCK
            && event.getClickedBlock() != null
            && handleEngineeringItemUse(event.getPlayer(), event.getClickedBlock(), professionItemId)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (!event.getPlayer().hasPermission(PERM_STATION)) {
            return;
        }
        if (stationOpenRequiresSneak && !event.getPlayer().isSneaking()) {
            return;
        }
        ProfessionType profession = stationLookup.get(event.getClickedBlock().getType());
        if (profession == null) {
            return;
        }
        ProtectionCheckResult result = protectionHookService.check(
            event.getPlayer(),
            event.getClickedBlock().getLocation(),
            ProtectionAction.STATION_USE
        );
        if (!result.allowed()) {
            event.setCancelled(true);
            messageService.send(event.getPlayer(), ProtectionHookService.denyMessage(result, ProtectionAction.STATION_USE));
            return;
        }
        event.setCancelled(true);
        openStationMenu(event.getPlayer(), profession, event.getClickedBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player) || !(event.getEntity() instanceof Tameable tameable)) {
            return;
        }
        bondedCompanions.put(player.getUniqueId(), event.getEntity().getUniqueId());
        scheduler.runEntity(player, "profession-beast-tame-" + player.getUniqueId(), () -> {
            grantExperience(player, ProfessionType.BEAST_MASTERY, 24L, "bonding with a companion", false);
            refreshPetBonuses(tameable, player, true);
            player.sendActionBar(Component.text("Beast Mastery bond formed."));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Tameable tameable) || !(tameable.getOwner() instanceof Player owner)) {
            return;
        }
        if (!owner.getUniqueId().equals(event.getPlayer().getUniqueId())) {
            return;
        }
        bondedCompanions.put(owner.getUniqueId(), ((Entity) tameable).getUniqueId());
        refreshPetBonuses(tameable, owner, false);
        int level = progress(playerProfileService.profile(owner), ProfessionType.BEAST_MASTERY).level();
        if (!owner.isSneaking() || level < 50) {
            owner.sendActionBar(Component.text("Companion bond refreshed."));
            return;
        }
        event.setCancelled(true);
        if (!cooldownReady(alphaCommandCooldowns, owner.getUniqueId(), 12_000L)) {
            owner.sendActionBar(Component.text("Alpha Command is recovering."));
            return;
        }
        Entity entity = (Entity) tameable;
        scheduler.runEntity(entity, "profession-alpha-command-" + entity.getUniqueId(), () -> alphaCommand(owner, tameable));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        String itemId = professionItemId(event.getItem());
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        scheduler.runEntity(event.getPlayer(), "profession-consume-" + event.getPlayer().getUniqueId(), () -> applyConsumable(event.getPlayer(), itemId));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        Player player = null;
        Tameable companion = null;
        if (event.getDamager() instanceof Player direct) {
            player = direct;
        } else if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter) {
            player = shooter;
        } else if (event.getDamager() instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            player = owner;
            companion = tameable;
        }
        if (!(event.getEntity() instanceof LivingEntity livingEntity) || player == null) {
            return;
        }

        if (companion != null) {
            handleBeastMastery(player, companion, livingEntity, event);
            return;
        }

        handleCombatProgression(player, livingEntity, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDefense(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        handleDefenseProgression(player, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        handleCombatKill(killer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        swordComboCounter.remove(id);
        parryWindows.remove(id);
        bladeStormCooldowns.remove(id);
        rainOfArrowsCooldowns.remove(id);
        alphaCommandCooldowns.remove(id);
        retireOwnedGadget(activeTurrets, id, "active-turret");
        retireOwnedGadget(activeShockTraps, id, "active-shock-trap");
        bondedCompanions.remove(id);
    }

    public void recordSpellCast(Player player, String spellId) {
        scheduler.runEntity(player, "profession-spellcast-" + player.getUniqueId(), () -> {
            grantExperience(player, ProfessionType.SPELLCRAFT, 12L, "casting " + NameFormat.friendly(spellId), false);
            if (activityListener != null) {
                activityListener.onGather(player, ProfessionType.SPELLCRAFT, 12L);
            }
        });
    }

    private void grantExperience(Player player, ProfessionType type, long amount, String reason) {
        grantExperience(player, type, amount, reason, true);
    }

    private void grantExperience(Player player, ProfessionType type, long amount, String reason, boolean actionBarFeedback) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        ProfessionProgress before = progress(profile, type);
        long nextExperience = before.experience() + Math.max(0L, amount);
        int nextLevel = Math.max(before.level(), curve.solveLevel(nextExperience));
        profile.putMetadata(META_XP_PREFIX + type.id(), Long.toString(nextExperience));
        profile.putMetadata(META_LEVEL_PREFIX + type.id(), Integer.toString(nextLevel));
        if (actionBarFeedback) {
            player.sendActionBar(Component.text(type.displayName() + " +" + amount + " XP"));
        }
        if (nextLevel > before.level()) {
            messageService.send(player, type.displayName() + " advanced to level " + nextLevel + " from " + reason + ".");
        }
        reconcileMastery(profile, type, player);
    }

    private void grantGatheringExperience(Player player, ProfessionType type, long amount, String reason) {
        grantExperience(player, type, amount, reason);
        if (activityListener != null) {
            activityListener.onGather(player, type, amount);
        }
    }

    private boolean handleEngineeringItemUse(Player player, Block clickedBlock, String itemId) {
        int level = progress(playerProfileService.profile(player), ProfessionType.ENGINEERING).level();
        return switch (itemId) {
            case "turret_kit" -> {
                if (level < 10) {
                    messageService.send(player, "Engineering level 10 is required to deploy a Turret Kit.");
                } else {
                    deployTurret(player, clickedBlock, level);
                }
                yield true;
            }
            case "shock_trap" -> {
                if (level < 20) {
                    messageService.send(player, "Engineering level 20 is required to deploy a Shock Trap.");
                } else {
                    deployShockTrap(player, clickedBlock, level);
                }
                yield true;
            }
            case "auto_miner" -> {
                if (level < 50) {
                    messageService.send(player, "Engineering level 50 is required to deploy an Auto Miner.");
                } else {
                    deployAutoMiner(player, clickedBlock, level);
                }
                yield true;
            }
            default -> false;
        };
    }

    private void deployTurret(Player player, Block clickedBlock, int level) {
        scheduler.runRegion(clickedBlock.getLocation(), "profession-engineering-turret-" + player.getUniqueId(), () -> {
            ProtectionCheckResult result = protectionHookService.check(player, clickedBlock.getLocation(), ProtectionAction.STATION_USE);
            if (!result.allowed()) {
                messageService.send(player, ProtectionHookService.denyMessage(result, ProtectionAction.STATION_USE));
                return;
            }

            Block deployBlock = clickedBlock.getRelative(BlockFace.UP);
            if (!deployBlock.getType().isAir() && !deployBlock.isPassable()) {
                messageService.send(player, "You need clear space above that block for a turret.");
                return;
            }
            if (!consumeHeldItem(player)) {
                return;
            }

            retireOwnedGadget(activeTurrets, player.getUniqueId(), "active-turret");

            Location spawnLocation = deployBlock.getLocation().add(0.5D, 0.05D, 0.5D);
            ArmorStand turret = spawnLocation.getWorld().spawn(spawnLocation, ArmorStand.class, stand -> {
                stand.setGravity(false);
                stand.setSilent(true);
                stand.setSmall(true);
                stand.setBasePlate(false);
                stand.setArms(false);
                stand.setCustomNameVisible(true);
                stand.customName(Component.text(player.getName() + "'s Turret"));
                stand.getEquipment().setHelmet(new ItemStack(Material.DISPENSER));
            });
            activeTurrets.put(player.getUniqueId(), turret.getUniqueId());

            long expireAt = System.currentTimeMillis() + (level >= 35 ? 45_000L : 30_000L);
            double radius = level >= 35 ? 8.0D : 6.0D;
            double damage = level >= 35 ? 7.5D : 5.5D;
            long periodTicks = level >= 50 ? 10L : 20L;
            int chunkX = spawnLocation.getBlockX() >> 4;
            int chunkZ = spawnLocation.getBlockZ() >> 4;

            scheduler.runRegionTimer(
                spawnLocation.getWorld(),
                chunkX,
                chunkZ,
                "profession-engineering-turret-loop-" + turret.getUniqueId(),
                10L,
                periodTicks,
                task -> {
                    if (!turret.isValid() || System.currentTimeMillis() >= expireAt) {
                        clearOwnedGadget(activeTurrets, player.getUniqueId(), turret.getUniqueId());
                        if (turret.isValid()) {
                            turret.getWorld().spawnParticle(Particle.SMOKE, turret.getLocation().add(0.0D, 0.6D, 0.0D), 10, 0.2D, 0.2D, 0.2D, 0.02D);
                            turret.remove();
                        }
                        task.cancel();
                        return;
                    }

                    Monster target = nearestMonsterInChunk(turret.getLocation(), radius);
                    if (target == null) {
                        turret.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, turret.getLocation().add(0.0D, 1.0D, 0.0D), 3, 0.15D, 0.15D, 0.15D, 0.0D);
                        return;
                    }

                    target.damage(damage, player);
                    target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.35D, 0.45D, 0.35D, 0.08D);
                    turret.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, turret.getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.15D, 0.15D, 0.15D, 0.03D);
                    turret.getWorld().playSound(turret.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6F, 1.6F);
                }
            );

            grantExperience(player, ProfessionType.ENGINEERING, 14L, "deploying a field turret", false);
            messageService.send(player, "Turret deployed. It will cover this lane for a short time.");
        });
    }

    private void deployShockTrap(Player player, Block clickedBlock, int level) {
        scheduler.runRegion(clickedBlock.getLocation(), "profession-engineering-trap-" + player.getUniqueId(), () -> {
            ProtectionCheckResult result = protectionHookService.check(player, clickedBlock.getLocation(), ProtectionAction.STATION_USE);
            if (!result.allowed()) {
                messageService.send(player, ProtectionHookService.denyMessage(result, ProtectionAction.STATION_USE));
                return;
            }

            Block deployBlock = clickedBlock.getRelative(BlockFace.UP);
            if (!deployBlock.getType().isAir() && !deployBlock.isPassable()) {
                messageService.send(player, "You need clear space above that block for a Shock Trap.");
                return;
            }
            if (!consumeHeldItem(player)) {
                return;
            }

            retireOwnedGadget(activeShockTraps, player.getUniqueId(), "active-shock-trap");

            Location spawnLocation = deployBlock.getLocation().add(0.5D, 0.05D, 0.5D);
            ArmorStand trap = spawnLocation.getWorld().spawn(spawnLocation, ArmorStand.class, stand -> {
                stand.setGravity(false);
                stand.setSilent(true);
                stand.setSmall(true);
                stand.setMarker(true);
                stand.setInvisible(true);
                stand.setBasePlate(false);
                stand.getEquipment().setHelmet(new ItemStack(Material.LIGHTNING_ROD));
            });
            activeShockTraps.put(player.getUniqueId(), trap.getUniqueId());

            long expireAt = System.currentTimeMillis() + (level >= 35 ? 50_000L : 35_000L);
            double triggerRadius = level >= 35 ? 2.5D : 1.9D;
            double burstRadius = level >= 35 ? 3.5D : 2.8D;
            double damage = level >= 35 ? 8.0D : 5.5D;
            int chunkX = spawnLocation.getBlockX() >> 4;
            int chunkZ = spawnLocation.getBlockZ() >> 4;

            scheduler.runRegionTimer(
                spawnLocation.getWorld(),
                chunkX,
                chunkZ,
                "profession-engineering-trap-loop-" + trap.getUniqueId(),
                10L,
                10L,
                task -> {
                    if (!trap.isValid() || System.currentTimeMillis() >= expireAt) {
                        clearOwnedGadget(activeShockTraps, player.getUniqueId(), trap.getUniqueId());
                        if (trap.isValid()) {
                            trap.getWorld().spawnParticle(Particle.SMOKE, trap.getLocation().add(0.0D, 0.15D, 0.0D), 6, 0.15D, 0.05D, 0.15D, 0.01D);
                            trap.remove();
                        }
                        task.cancel();
                        return;
                    }

                    Monster trigger = nearestMonsterInChunk(trap.getLocation(), triggerRadius);
                    if (trigger == null) {
                        trap.getWorld().spawnParticle(Particle.WAX_OFF, trap.getLocation().add(0.0D, 0.1D, 0.0D), 2, 0.12D, 0.02D, 0.12D, 0.0D);
                        return;
                    }

                    nearbyMonstersInChunk(trap.getLocation(), burstRadius).forEach(monster -> {
                        monster.damage(damage, player);
                        monster.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, level >= 50 ? 2 : 1, false, true, true));
                    });
                    trap.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, trap.getLocation().add(0.0D, 0.15D, 0.0D), 28, 0.55D, 0.15D, 0.55D, 0.06D);
                    trap.getWorld().spawnParticle(Particle.FLASH, trap.getLocation().add(0.0D, 0.2D, 0.0D), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    trap.getWorld().playSound(trap.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.7F, 1.5F);
                    clearOwnedGadget(activeShockTraps, player.getUniqueId(), trap.getUniqueId());
                    trap.remove();
                    grantExperience(player, ProfessionType.ENGINEERING, 18L, "springing a shock trap", false);
                    task.cancel();
                }
            );

            messageService.send(player, "Shock Trap armed. The next hostile that crosses it will trigger the burst.");
        });
    }

    private void deployAutoMiner(Player player, Block clickedBlock, int level) {
        scheduler.runRegion(clickedBlock.getLocation(), "profession-engineering-miner-" + player.getUniqueId(), () -> {
            GatherReward reward = gatherReward(clickedBlock);
            if (reward == null || reward.profession() != ProfessionType.MINING) {
                messageService.send(player, "Auto Miner needs a mineable ore or stone block.");
                return;
            }
            ProtectionCheckResult result = protectionHookService.check(player, clickedBlock.getLocation(), ProtectionAction.GATHER);
            if (!result.allowed()) {
                messageService.send(player, ProtectionHookService.denyMessage(result, ProtectionAction.GATHER));
                return;
            }
            if (!consumeHeldItem(player)) {
                return;
            }

            List<Block> vein = collectMineableVein(clickedBlock, level >= 50 ? 8 : 5);
            ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
            long miningXp = 0L;

            for (Block block : vein) {
                GatherReward blockReward = gatherReward(block);
                if (blockReward == null || blockReward.profession() != ProfessionType.MINING) {
                    continue;
                }
                miningXp += blockReward.experience();
                Collection<ItemStack> drops = block.getDrops(tool, player);
                block.setType(Material.AIR, false);
                drops.forEach(drop -> block.getWorld().dropItemNaturally(block.getLocation().add(0.5D, 0.4D, 0.5D), drop));
                block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5D, 0.5D, 0.5D), 14, 0.25D, 0.25D, 0.25D, block.getBlockData());
            }

            grantExperience(player, ProfessionType.ENGINEERING, 24L, "deploying an auto miner", false);
            grantGatheringExperience(player, ProfessionType.MINING, Math.max(18L, miningXp), "auto-mining a dense vein");
            clickedBlock.getWorld().playSound(clickedBlock.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.8F, 0.85F);
            clickedBlock.getWorld().playSound(clickedBlock.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, 0.7F, 0.9F);
            messageService.send(player, "Auto Miner carved through " + vein.size() + " blocks.");
        });
    }

    private boolean consumeHeldItem(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return false;
        }
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(held);
        }
        return true;
    }

    private void retireOwnedGadget(Map<java.util.UUID, java.util.UUID> gadgetMap, java.util.UUID ownerId, String taskPrefix) {
        java.util.UUID existingId = gadgetMap.remove(ownerId);
        if (existingId == null) {
            return;
        }
        Entity existing = org.bukkit.Bukkit.getEntity(existingId);
        if (existing != null && existing.isValid()) {
            scheduler.runEntity(existing, "profession-" + taskPrefix + "-retire-" + existingId, existing::remove);
        }
    }

    private void clearOwnedGadget(Map<java.util.UUID, java.util.UUID> gadgetMap, java.util.UUID ownerId, java.util.UUID entityId) {
        gadgetMap.computeIfPresent(ownerId, (ignored, current) -> current.equals(entityId) ? null : current);
    }

    private List<Block> collectMineableVein(Block origin, int maxBlocks) {
        List<Block> collected = new ArrayList<>();
        ArrayDeque<Block> frontier = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        frontier.add(origin);
        Material rootType = origin.getType();

        while (!frontier.isEmpty() && collected.size() < maxBlocks) {
            Block next = frontier.removeFirst();
            String key = next.getX() + ":" + next.getY() + ":" + next.getZ();
            if (!visited.add(key)) {
                continue;
            }
            GatherReward reward = gatherReward(next);
            if (reward == null || reward.profession() != ProfessionType.MINING) {
                continue;
            }
            if (next.getType() != rootType && !isOre(rootType) && !isOre(next.getType())) {
                continue;
            }
            collected.add(next);

            frontier.add(next.getRelative(BlockFace.NORTH));
            frontier.add(next.getRelative(BlockFace.SOUTH));
            frontier.add(next.getRelative(BlockFace.EAST));
            frontier.add(next.getRelative(BlockFace.WEST));
            frontier.add(next.getRelative(BlockFace.UP));
            frontier.add(next.getRelative(BlockFace.DOWN));
        }

        return collected;
    }

    private boolean isOre(Material material) {
        return material.name().endsWith("_ORE")
            || material == Material.ANCIENT_DEBRIS
            || material == Material.NETHER_QUARTZ_ORE
            || material == Material.NETHER_GOLD_ORE;
    }

    private Tameable bondedCompanion(Player owner) {
        java.util.UUID entityId = bondedCompanions.get(owner.getUniqueId());
        if (entityId != null) {
            Entity entity = org.bukkit.Bukkit.getEntity(entityId);
            if (entity instanceof Tameable tameable
                && tameable.getOwner() instanceof Player playerOwner
                && playerOwner.getUniqueId().equals(owner.getUniqueId())
                && entity.isValid()) {
                return tameable;
            }
            bondedCompanions.remove(owner.getUniqueId(), entityId);
        }

        for (Entity entity : owner.getChunk().getEntities()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwner() instanceof Player playerOwner
                && playerOwner.getUniqueId().equals(owner.getUniqueId())
                && entity.isValid()) {
                bondedCompanions.put(owner.getUniqueId(), entity.getUniqueId());
                return tameable;
            }
        }
        return null;
    }

    private int countCustomItems(PlayerInventory inventory, String itemId) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null) {
                continue;
            }
            if (itemId.equalsIgnoreCase(professionItemId(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private String activeGadgetLine(String name, java.util.UUID entityId) {
        if (entityId == null) {
            return name + ": None deployed.";
        }
        Entity entity = org.bukkit.Bukkit.getEntity(entityId);
        if (entity == null || !entity.isValid()) {
            return name + ": None deployed.";
        }
        return name + ": " + entity.getWorld().getName() + " @ "
            + entity.getLocation().getBlockX() + ", "
            + entity.getLocation().getBlockY() + ", "
            + entity.getLocation().getBlockZ();
    }

    private void handleBeastMastery(Player player, Tameable companion, LivingEntity target, EntityDamageByEntityEvent event) {
        int level = progress(playerProfileService.profile(player), ProfessionType.BEAST_MASTERY).level();
        grantExperience(player, ProfessionType.BEAST_MASTERY, 9L, "commanding a companion", false);
        if (activityListener != null) {
            activityListener.onGather(player, ProfessionType.BEAST_MASTERY, 9L);
        }

        double multiplier = 1.0D;
        if (level >= 10) {
            multiplier += 0.18D;
        }
        if (level >= 35 && player.getLocation().distanceSquared(target.getLocation()) <= 64.0D) {
            multiplier += 0.10D;
        }
        if (level >= 50 && player.isSneaking()) {
            multiplier += 0.15D;
        }
        event.setDamage(event.getDamage() * multiplier);
        if (level >= 35) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 0, false, true, true));
        }
        refreshPetBonuses(companion, player, false);
    }

    private void refreshPetBonuses(Tameable tameable, Player owner, boolean announce) {
        Entity entity = (Entity) tameable;
        scheduler.runEntity(entity, "profession-beast-refresh-" + entity.getUniqueId(), () -> {
            if (!entity.isValid()) {
                return;
            }

            UnifiedPlayer profile = playerProfileService.profile(owner);
            int level = progress(profile, ProfessionType.BEAST_MASTERY).level();
            boolean masteryUnlocked = hasMastery(profile, ProfessionType.BEAST_MASTERY);
            PersistentDataContainer data = entity.getPersistentDataContainer();

            double baseHealth = rememberBaseAttribute(data, petBaseHealthKey, entity instanceof LivingEntity livingEntity
                ? attributeBase(livingEntity, Attribute.MAX_HEALTH, 20.0D)
                : 20.0D);
            double baseDamage = rememberBaseAttribute(data, petBaseDamageKey, entity instanceof LivingEntity livingEntity
                ? attributeBase(livingEntity, Attribute.ATTACK_DAMAGE, 3.0D)
                : 3.0D);
            double baseSpeed = rememberBaseAttribute(data, petBaseSpeedKey, entity instanceof LivingEntity livingEntity
                ? attributeBase(livingEntity, Attribute.MOVEMENT_SPEED, 0.3D)
                : 0.3D);

            double healthScale = 1.0D + (level >= 5 ? 0.25D : 0.0D) + (level >= 35 ? 0.15D : 0.0D) + (masteryUnlocked ? 0.10D : 0.0D);
            double damageScale = 1.0D + (level >= 10 ? 0.22D : 0.0D) + (level >= 50 ? 0.12D : 0.0D);
            double speedScale = 1.0D + (level >= 20 ? 0.12D : 0.0D) + (level >= 50 ? 0.08D : 0.0D);

            if (entity instanceof LivingEntity livingEntity) {
                updateAttribute(livingEntity, Attribute.MAX_HEALTH, baseHealth * healthScale);
                updateAttribute(livingEntity, Attribute.ATTACK_DAMAGE, baseDamage * damageScale);
                updateAttribute(livingEntity, Attribute.MOVEMENT_SPEED, baseSpeed * speedScale);
                double maxHealth = attributeValue(livingEntity, Attribute.MAX_HEALTH, baseHealth * healthScale);
                if (announce) {
                    livingEntity.setHealth(Math.min(maxHealth, Math.max(livingEntity.getHealth(), maxHealth)));
                } else {
                    livingEntity.setHealth(Math.min(maxHealth, livingEntity.getHealth()));
                }
                if (level >= 20) {
                    livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 30, 0, false, false, true));
                }
            }

            data.set(petTierKey, PersistentDataType.INTEGER, level);
            if (announce) {
                messageService.send(owner, "Your companion answers the bond of Beast Mastery.");
            }
        });
    }

    private void alphaCommand(Player owner, Tameable tameable) {
        int level = progress(playerProfileService.profile(owner), ProfessionType.BEAST_MASTERY).level();
        if (level < 50 || !(tameable instanceof Entity entity) || !entity.isValid()) {
            return;
        }

        refreshPetBonuses(tameable, owner, false);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 12, 1, false, true, true));
            livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 12, 0, false, true, true));
            livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 8, 0, false, true, true));
        }
        if (tameable instanceof Mob mob) {
            Monster target = nearestMonsterInChunk(entity.getLocation(), 12.0D);
            if (target != null) {
                mob.setTarget(target);
            }
        }
        entity.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, entity.getLocation().add(0.0D, 0.8D, 0.0D), 18, 0.35D, 0.45D, 0.35D, 0.02D);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 0.85F, 0.75F);
        messageService.send(owner, "Alpha Command sent your companion surging forward.");
    }

    private double rememberBaseAttribute(PersistentDataContainer data, NamespacedKey key, double currentBase) {
        Double stored = data.get(key, PersistentDataType.DOUBLE);
        if (stored != null && stored > 0.0D) {
            return stored;
        }
        data.set(key, PersistentDataType.DOUBLE, currentBase);
        return currentBase;
    }

    private double attributeBase(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getBaseValue();
    }

    private double attributeValue(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private void updateAttribute(LivingEntity entity, Attribute attribute, double baseValue) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(baseValue);
        }
    }

    private Monster nearestMonsterInChunk(Location center, double radius) {
        double bestDistance = radius * radius;
        Monster best = null;
        for (Monster monster : nearbyMonstersInChunk(center, radius)) {
            double distance = monster.getLocation().distanceSquared(center);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = monster;
            }
        }
        return best;
    }

    private List<Monster> nearbyMonstersInChunk(Location center, double radius) {
        List<Monster> monsters = new ArrayList<>();
        double radiusSquared = radius * radius;
        for (Entity entity : center.getChunk().getEntities()) {
            if (entity instanceof Monster monster && entity.isValid() && entity.getLocation().distanceSquared(center) <= radiusSquared) {
                monsters.add(monster);
            }
        }
        return monsters;
    }

    private boolean hasNearbyOwnedPet(Player owner, double radius) {
        double radiusSquared = radius * radius;
        for (Entity entity : owner.getLocation().getChunk().getEntities()) {
            if (!(entity instanceof Tameable tameable) || !(tameable.getOwner() instanceof Player playerOwner)) {
                continue;
            }
            if (playerOwner.getUniqueId().equals(owner.getUniqueId())
                && entity.isValid()
                && entity.getLocation().distanceSquared(owner.getLocation()) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private long cooldownRemaining(Map<java.util.UUID, Long> cooldowns, java.util.UUID playerId) {
        Long readyAt = cooldowns.get(playerId);
        if (readyAt == null) {
            return 0L;
        }
        return Math.max(0L, readyAt - System.currentTimeMillis());
    }

    private String formatMillis(long millis) {
        long seconds = Math.max(1L, Math.round(millis / 1000.0D));
        return seconds + "s";
    }

    private void handleCombatProgression(Player player, LivingEntity target, EntityDamageByEntityEvent event) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        Material mainType = mainHand.getType();
        UnifiedPlayer profile = playerProfileService.profile(player);

        if (event.getDamager() instanceof AbstractArrow arrow) {
            handleArchery(player, target, event, arrow, progress(profile, ProfessionType.ARCHERY).level());
            return;
        }

        if (isSword(mainType) && isSword(offHand.getType())) {
            handleDualWield(player, target, event, progress(profile, ProfessionType.DUAL_WIELD).level());
            return;
        }

        if (isSword(mainType)) {
            handleSwordsmanship(player, target, event, progress(profile, ProfessionType.SWORDSMANSHIP).level());
            return;
        }

        if (isAxe(mainType)) {
            handleAxemanship(player, target, event, progress(profile, ProfessionType.AXEMANSHIP).level());
        }
    }

    private void handleDefenseProgression(Player player, EntityDamageEvent event) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        int level = progress(profile, ProfessionType.DEFENSE).level();
        long experience = Math.max(4L, Math.round(event.getFinalDamage() * (player.isBlocking() ? 2.0D : 1.25D)));
        grantExperience(player, ProfessionType.DEFENSE, experience, "holding the line", false);

        double reduction = 0.0D;
        if (level >= 5) {
            reduction += 0.04D;
        }
        if (level >= 10) {
            reduction += 0.05D;
        }
        if (level >= 20 && player.isBlocking()) {
            reduction += 0.12D;
        }
        if (level >= 50 && (player.isBlocking() || player.isSneaking())) {
            reduction += 0.08D;
        }
        int beastLevel = progress(profile, ProfessionType.BEAST_MASTERY).level();
        if (beastLevel >= 35 && hasNearbyOwnedPet(player, 10.0D)) {
            reduction += 0.08D;
            player.sendActionBar(Component.text("Guardian Bond absorbs part of the impact."));
        }

        Long parryUntil = parryWindows.get(player.getUniqueId());
        UnifiedPlayer swordProfile = playerProfileService.profile(player);
        if (parryUntil != null && parryUntil >= System.currentTimeMillis() && progress(swordProfile, ProfessionType.SWORDSMANSHIP).level() >= 35) {
            reduction += 0.35D;
            parryWindows.remove(player.getUniqueId());
            player.sendActionBar(Component.text("Parry softened the blow."));
        }

        reduction = Math.min(0.65D, reduction);
        event.setDamage(event.getDamage() * (1.0D - reduction));

        if (level >= 35 && player.isBlocking()) {
            Location location = player.getLocation();
            for (Entity nearby : player.getWorld().getNearbyEntities(location, 8.0D, 4.0D, 8.0D)) {
                if (nearby instanceof Monster monster) {
                    monster.setTarget(player);
                }
            }
        }
    }

    private void handleCombatKill(Player killer) {
        if (isSword(killer.getInventory().getItemInMainHand().getType())
            && isSword(killer.getInventory().getItemInOffHand().getType())
            && progress(playerProfileService.profile(killer), ProfessionType.DUAL_WIELD).level() >= 35) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, true));
        }
    }

    private void handleSwordsmanship(Player player, LivingEntity target, EntityDamageByEntityEvent event, int level) {
        grantExperience(player, ProfessionType.SWORDSMANSHIP, 8L, "clean sword strikes", false);
        if (level >= 5) {
            event.setDamage(event.getDamage() * 1.05D);
        }
        if (level >= 10) {
            int combo = swordComboCounter.merge(player.getUniqueId(), 1, (left, right) -> {
                int total = left + right;
                return total > 3 ? 1 : total;
            });
            if (combo == 3) {
                event.setDamage(event.getDamage() * 1.35D);
                swordComboCounter.put(player.getUniqueId(), 0);
                player.sendActionBar(Component.text("Combo Strike!"));
            }
        }
        if (level >= 20 && player.isSprinting()) {
            event.setDamage(event.getDamage() * 1.20D);
        }
        if (level >= 50 && cooldownReady(bladeStormCooldowns, player.getUniqueId(), 6_000L)) {
            applyAreaDamage(player, target, event.getDamage() * 0.35D, 2.7D, "Blade Storm");
        }
    }

    private void handleAxemanship(Player player, LivingEntity target, EntityDamageByEntityEvent event, int level) {
        grantExperience(player, ProfessionType.AXEMANSHIP, 10L, "heavy axe blows", false);
        if (level >= 5) {
            event.setDamage(event.getDamage() * 1.08D);
        }
        if (level >= 20 && player.getAttackCooldown() >= 0.95F) {
            event.setDamage(event.getDamage() * 1.25D);
        }
        if (level >= 10) {
            applyAreaDamage(player, target, event.getDamage() * 0.30D, 2.2D, null);
        }
        if (level >= 35) {
            tryApplyCustomEffect(target, "bleed");
        }
        if (level >= 50 && player.getHealth() <= maxHealth(player) * 0.50D) {
            double boosted = event.getDamage() * 1.20D;
            event.setDamage(boosted);
            player.setHealth(Math.min(maxHealth(player), player.getHealth() + (boosted * 0.15D)));
        }
    }

    private void handleArchery(Player player, LivingEntity target, EntityDamageByEntityEvent event, AbstractArrow arrow, int level) {
        grantExperience(player, ProfessionType.ARCHERY, 10L, "landing arrow shots", false);
        if (level >= 5) {
            event.setDamage(event.getDamage() * 1.05D);
        }
        if (level >= 10 && arrow.isCritical()) {
            event.setDamage(event.getDamage() * 1.25D);
        }
        if (level >= 35) {
            event.setDamage(event.getDamage() + 2.0D);
        }
        if (level >= 20) {
            applyAreaDamage(player, target, event.getDamage() * 0.25D, 2.8D, null);
        }
        if (level >= 50 && cooldownReady(rainOfArrowsCooldowns, player.getUniqueId(), 8_000L)) {
            applyAreaDamage(player, target, event.getDamage() * 0.40D, 3.5D, "Rain of Arrows");
            target.getWorld().spawnParticle(org.bukkit.Particle.CRIT, target.getLocation(), 24, 1.6D, 1.1D, 1.6D, 0.1D);
        }
    }

    private void handleDualWield(Player player, LivingEntity target, EntityDamageByEntityEvent event, int level) {
        grantExperience(player, ProfessionType.DUAL_WIELD, 11L, "dual-wield skirmishing", false);
        if (level >= 5 && Math.random() <= 0.12D) {
            event.setDamage(event.getDamage() * 1.25D);
        }
        if (level >= 10 && isBehindTarget(player, target)) {
            event.setDamage(event.getDamage() * 1.25D);
        }
        if (level >= 20) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        }
        if (level >= 50 && player.isSprinting()) {
            event.setDamage(event.getDamage() * 1.18D);
        }
    }

    private void applyAreaDamage(Player player, LivingEntity primaryTarget, double amount, double radius, String message) {
        if (amount <= 0.0D) {
            return;
        }
        Location center = primaryTarget.getLocation();
        for (Entity nearby : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity livingEntity)
                || livingEntity.getUniqueId().equals(primaryTarget.getUniqueId())
                || livingEntity.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            livingEntity.damage(amount, player);
        }
        if (message != null) {
            player.sendActionBar(Component.text(message + "!"));
        }
    }

    private void tryApplyCustomEffect(LivingEntity target, String effectId) {
        effectService.applyEffectToEntity(target, effectId, 1, 0, 1.0D, true).exceptionally(error -> null);
    }

    private boolean cooldownReady(Map<java.util.UUID, Long> cooldowns, java.util.UUID playerId, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long readyAt = cooldowns.get(playerId);
        if (readyAt != null && readyAt > now) {
            return false;
        }
        cooldowns.put(playerId, now + cooldownMillis);
        return true;
    }

    private boolean isSword(Material material) {
        return switch (material) {
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD -> true;
            default -> false;
        };
    }

    private boolean isAxe(Material material) {
        return switch (material) {
            case WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE -> true;
            default -> false;
        };
    }

    private boolean isBehindTarget(Player player, LivingEntity target) {
        org.bukkit.util.Vector targetFacing = target.getLocation().getDirection().setY(0.0D).normalize();
        org.bukkit.util.Vector toPlayer = player.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0.0D).normalize();
        return targetFacing.dot(toPlayer) > 0.45D;
    }

    private double maxHealth(Player player) {
        return player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
            ? 20.0D
            : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
    }

    private String nextUnlockLine(ProfessionType type, int level) {
        return unlocksByProfession.getOrDefault(type, List.of()).stream()
            .filter(unlock -> unlock.level() > level)
            .findFirst()
            .map(unlock -> "L" + unlock.level() + " " + unlock.title())
            .orElse(null);
    }

    private void registerUnlock(ProfessionType type, int level, String title, String description) {
        unlocksByProfession.computeIfAbsent(type, ignored -> new ArrayList<>()).add(new ProfessionUnlock(level, title, description));
    }

    private VortexMenuBuilder.MenuDefinition buildLedgerMenu(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Profession Ledger"), 6);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30};
        int index = 0;
        for (ProfessionType type : ProfessionType.values()) {
            builder.button(slots[index++], professionIcon(profile, progress(profile, type)), context -> {
                if (type == ProfessionType.BEAST_MASTERY) {
                    context.open(buildCompanionHud(context.player()));
                } else if (type == ProfessionType.ENGINEERING) {
                    context.open(buildEngineeringConsole(context.player()));
                } else if (type.craftingProfession()) {
                    context.open(buildStationMenu(context.player(), type));
                } else {
                    ProfessionProgress current = progress(playerProfileService.profile(context.player()), type);
                    messageService.send(context.player(), type.displayName() + " | Level " + current.level() + " | XP " + current.experience());
                }
                return CompletableFuture.completedFuture(null);
            });
        }
        builder.button(49, icon(Material.BOOK, "Profession Flow", List.of(
            "Combat tracks level through real combat actions, class play, pets, and weapon usage.",
            "Mining, Woodcutting, Farming, and Fishing level through world actions.",
            "Weaponsmithing, Fletching, Armorsmithing, Engineering, Alchemy, and Cooking level from stations.",
            "Sneak-right-click a smithing table, fletching table, anvil, crafting table, brewing stand, or smoker.",
            "Use /professions station <name> for a direct shortcut."
        )), context -> CompletableFuture.completedFuture(null));
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildCompanionHud(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        ProfessionProgress progress = progress(profile, ProfessionType.BEAST_MASTERY);
        Tameable companion = bondedCompanion(player);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Companion Command HUD"), 5);

        builder.button(4, professionIcon(profile, progress), context -> CompletableFuture.completedFuture(null));
        builder.button(13, companionStatusIcon(player, companion, progress), context -> {
            context.open(buildCompanionHud(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(20, icon(Material.HEART_OF_THE_SEA, "Refresh Bond", List.of(
            "Reapply Beast Mastery bonuses to your bonded companion.",
            companion == null ? "No bonded companion detected." : "Current companion: " + NameFormat.friendly(((Entity) companion).getType().name())
        )), context -> {
            Tameable bonded = bondedCompanion(context.player());
            if (bonded == null) {
                messageService.send(context.player(), "No bonded companion found. Tame or interact with a pet first.");
            } else {
                refreshPetBonuses(bonded, context.player(), true);
                messageService.send(context.player(), "Your companion bond has been refreshed.");
            }
            context.open(buildCompanionHud(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(22, alphaCommandIcon(player, progress, companion), context -> {
            Tameable bonded = bondedCompanion(context.player());
            if (bonded == null) {
                messageService.send(context.player(), "No bonded companion found for Alpha Command.");
            } else if (progress.level() < 50) {
                messageService.send(context.player(), "Beast Mastery level 50 is required for Alpha Command.");
            } else {
                long remaining = cooldownRemaining(alphaCommandCooldowns, context.player().getUniqueId());
                if (remaining > 0L) {
                    messageService.send(context.player(), "Alpha Command is recovering for " + formatMillis(remaining) + ".");
                } else if (cooldownReady(alphaCommandCooldowns, context.player().getUniqueId(), 12_000L)) {
                    Entity entity = (Entity) bonded;
                    scheduler.runEntity(entity, "profession-alpha-command-gui-" + entity.getUniqueId(), () -> alphaCommand(context.player(), bonded));
                }
            }
            context.open(buildCompanionHud(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(24, icon(Material.BOOK, "Pack Progression", beastUnlockLore(progress.level())), context -> CompletableFuture.completedFuture(null));
        builder.button(31, icon(Material.NAME_TAG, "Bonding Tips", List.of(
            "Tame a wolf or other companion to start leveling Beast Mastery.",
            "Right-click your own pet to refresh the bond.",
            "Sneak-right-click after level 50 to trigger Alpha Command.",
            hasMastery(profile, ProfessionType.BEAST_MASTERY)
                ? "Mastery active: " + mastery(ProfessionType.BEAST_MASTERY).displayName()
                : "Mastery unlocks at level " + masteryLevelThreshold + "."
        )), context -> CompletableFuture.completedFuture(null));
        builder.button(40, icon(Material.BARRIER, "Back to Ledger", List.of("Return to the full profession overview.")), context -> {
            context.open(buildLedgerMenu(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildEngineeringConsole(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        ProfessionProgress progress = progress(profile, ProfessionType.ENGINEERING);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Engineering Console"), 5);

        builder.button(4, professionIcon(profile, progress), context -> CompletableFuture.completedFuture(null));
        builder.button(11, engineeringStatusIcon(
            player,
            "Turret Kit",
            10,
            "turret_kit",
            activeTurrets.get(player.getUniqueId()),
            List.of(
                "Deploy on a solid block to create an autonomous lane turret.",
                "Best for defending breach lanes and fracture pushes."
            )
        ), context -> {
            context.open(buildStationMenu(context.player(), ProfessionType.ENGINEERING));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(13, engineeringStatusIcon(
            player,
            "Shock Trap",
            20,
            "shock_trap",
            activeShockTraps.get(player.getUniqueId()),
            List.of(
                "Deploy on a solid block to arm a burst slow trap.",
                "Best for holding corridors and protecting squishier allies."
            )
        ), context -> {
            context.open(buildStationMenu(context.player(), ProfessionType.ENGINEERING));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(15, engineeringAutoMinerIcon(player, progress), context -> {
            context.open(buildStationMenu(context.player(), ProfessionType.ENGINEERING));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(22, icon(Material.CRAFTING_TABLE, "Open Engineering Station", List.of(
            "Craft Turret Kits, Shock Traps, and Auto Miners.",
            "You can also sneak-right-click a crafting table in the world."
        )), context -> {
            context.open(buildStationMenu(context.player(), ProfessionType.ENGINEERING));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(31, icon(Material.COMPASS, "Field Readout", engineeringReadoutLore(player, progress.level())), context -> {
            context.open(buildEngineeringConsole(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        builder.button(40, icon(Material.BARRIER, "Back to Ledger", List.of("Return to the full profession overview.")), context -> {
            context.open(buildLedgerMenu(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        return builder.build();
    }

    private VortexMenuBuilder.MenuDefinition buildStationMenu(Player player, ProfessionType profession) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text(profession.displayName() + " Station"), 6);
        builder.button(4, professionIcon(profile, progress(profile, profession)), context -> CompletableFuture.completedFuture(null));
        builder.button(49, icon(Material.BARRIER, "Back to Ledger", List.of("Return to your profession overview.")), context -> {
            context.open(buildLedgerMenu(context.player()));
            return CompletableFuture.completedFuture(null);
        });
        int[] slots = {19, 20, 21, 23, 24, 25, 28, 29, 30, 32, 33, 34};
        List<ProfessionRecipe> recipes = recipesByProfession.getOrDefault(profession, List.of());
        for (int index = 0; index < Math.min(slots.length, recipes.size()); index++) {
            ProfessionRecipe recipe = recipes.get(index);
            builder.button(slots[index], recipeIcon(profile, recipe), context ->
                craftRecipe(context.player(), recipe).whenComplete((ignored, error) -> context.open(buildStationMenu(context.player(), profession)))
            );
        }
        return builder.build();
    }

    private CompletableFuture<Void> craftRecipe(Player player, ProfessionRecipe recipe) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runEntity(player, "profession-craft-" + player.getUniqueId(), () -> {
            if (!player.hasPermission(PERM_CRAFT)) {
                messageService.send(player, "You do not have permission: " + PERM_CRAFT);
                future.complete(null);
                return;
            }
            UnifiedPlayer profile = playerProfileService.profile(player);
            ProfessionProgress current = progress(profile, recipe.profession());
            if (current.level() < recipe.requiredLevel()) {
                messageService.send(player, recipe.profession().displayName() + " level " + recipe.requiredLevel() + " is required.");
                future.complete(null);
                return;
            }
            if (!hasIngredients(player.getInventory(), recipe.ingredients())) {
                messageService.send(player, "Missing ingredients for " + recipe.displayName() + ".");
                future.complete(null);
                return;
            }
            consumeIngredients(player.getInventory(), recipe.ingredients());
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(buildCraftedItem(recipe.outputItemId(), recipe.outputAmount()));
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            marketManager.recordSale(recipe.outputItemId(), recipe.outputAmount());
            grantExperience(player, recipe.profession(), recipe.experienceReward(), "crafting " + recipe.displayName());
            if (activityListener != null) {
                activityListener.onCraft(player, recipe.profession(), recipe.outputItemId(), 1);
            }
            messageService.send(player, "Crafted " + recipe.displayName() + ".");
            future.complete(null);
        });
        return future;
    }

    private GatherReward gatherReward(Block block) {
        GatherReward reward = gatherRewards.get(block.getType());
        if (reward == null) {
            return null;
        }
        if (!reward.requiresMaturityCheck()) {
            return reward;
        }
        return block.getBlockData() instanceof Ageable ageable && ageable.getAge() == ageable.getMaximumAge() ? reward : null;
    }

    private boolean hasIngredients(PlayerInventory inventory, Collection<RecipeIngredient> ingredients) {
        for (RecipeIngredient ingredient : ingredients) {
            if (countMatching(inventory, ingredient) < ingredient.amount()) {
                return false;
            }
        }
        return true;
    }

    private int countMatching(PlayerInventory inventory, RecipeIngredient ingredient) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            if (matches(item, ingredient)) {
                amount += item.getAmount();
            }
        }
        return amount;
    }

    private void consumeIngredients(PlayerInventory inventory, Collection<RecipeIngredient> ingredients) {
        for (RecipeIngredient ingredient : ingredients) {
            int remaining = ingredient.amount();
            ItemStack[] contents = inventory.getContents();
            for (int index = 0; index < contents.length && remaining > 0; index++) {
                ItemStack item = contents[index];
                if (!matches(item, ingredient)) {
                    continue;
                }
                int remove = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - remove);
                remaining -= remove;
                if (item.getAmount() <= 0) {
                    inventory.setItem(index, null);
                }
            }
        }
    }

    private boolean matches(ItemStack item, RecipeIngredient ingredient) {
        if (item == null || item.getType() != ingredient.material()) {
            return false;
        }
        if (ingredient.customItemId() == null || ingredient.customItemId().isBlank()) {
            return true;
        }
        return ingredient.customItemId().equalsIgnoreCase(customItemId(item, ingredient.source()));
    }

    private String customItemId(ItemStack item, ItemSource source) {
        return customItemService.resolveId(item);
    }

    private String professionItemId(ItemStack item) {
        return customItemService.resolveId(item);
    }

    private ItemStack buildCraftedItem(String itemId, int amount) {
        return customItemService.create(itemId, amount);
    }

    private void applyConsumable(Player player, String itemId) {
        switch (itemId) {
            case "focus_draught" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 90, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 180, 0));
            }
            case "breach_salve" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 18, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 90, 0));
            }
            case "riftguard_tonic" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 120, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 1));
            }
            case "hunter_stew" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 45, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 0));
            }
            case "ember_skewer" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 90, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60, 0));
            }
            case "overrun_feast" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 12, 1));
            }
            default -> {
            }
        }
    }

    private ItemStack professionIcon(UnifiedPlayer profile, ProfessionProgress progress) {
        List<String> lore = new ArrayList<>();
        lore.add(progress.type().category() == ProfessionType.Category.COMBAT
            ? "Level through combat actions, class play, and weapon usage."
            : progress.type().category() == ProfessionType.Category.GATHERING
            ? "Level through world actions and gathering loops."
            : "Level through station crafting and refined outputs.");
        lore.add("Level: " + progress.level());
        lore.add("XP: " + progress.experience());
        lore.add("To Next: " + progress.experienceToNextLevel());
        ProfessionMastery mastery = mastery(progress.type());
        lore.add(hasMastery(profile, progress.type())
            ? "Mastery: " + mastery.displayName()
            : "Mastery unlocks at level " + masteryLevelThreshold);
        if (progress.type().craftingProfession()) {
            lore.add("Station: " + NameFormat.friendly(progress.type().stationMaterial().name()));
        }
        String nextUnlock = nextUnlockLine(progress.type(), progress.level());
        if (nextUnlock != null) {
            lore.add("Next Unlock: " + nextUnlock);
        }
        if (progress.type() == ProfessionType.BEAST_MASTERY) {
            lore.add("Click to open the Companion Command HUD.");
        } else if (progress.type() == ProfessionType.ENGINEERING) {
            lore.add("Click to open the Engineering Console.");
        }
        String iconId = switch (progress.type()) {
            case SWORDSMANSHIP, AXEMANSHIP, DEFENSE, SMITHING, ARMORSMITHING -> "gui_knight_crest";
            case ARCHERY, FLETCHING -> "gui_ranger_mark";
            case SPELLCRAFT -> "gui_mage_prism";
            case BEAST_MASTERY -> "gui_beast_totem";
            case MINING -> "gui_mining_compass";
            case ENGINEERING -> "gui_engineering_core";
            case ALCHEMY -> "gui_alchemy_flask";
            default -> null;
        };
        return iconId == null
            ? icon(progress.type().icon(), progress.type().displayName(), lore)
            : customItemService.createIcon(iconId, progress.type().displayName(), lore);
    }

    private ItemStack recipeIcon(UnifiedPlayer profile, ProfessionRecipe recipe) {
        List<String> lore = new ArrayList<>();
        lore.add(recipe.description());
        lore.add("Requires " + recipe.profession().displayName() + " " + recipe.requiredLevel());
        lore.add("Craft XP: +" + recipe.experienceReward());
        lore.add("Ingredients:");
        recipe.ingredients().forEach(ingredient -> lore.add("- " + ingredient.amount() + "x " + ingredient.displayName()));
        lore.add("Estimated Market: " + String.format(Locale.US, "%.1f", marketManager.quotePrice(recipe.outputItemId())));
        lore.add(progress(profile, recipe.profession()).level() >= recipe.requiredLevel() ? "Click to craft." : "Level this profession first.");
        return icon(recipe.icon(), recipe.displayName(), lore);
    }

    private ItemStack companionStatusIcon(Player player, Tameable companion, ProfessionProgress progress) {
        List<String> lore = new ArrayList<>();
        if (companion == null) {
            lore.add("No bonded companion is currently linked.");
            lore.add("Tame a pet or right-click your own pet to bind it.");
            lore.add("Beast Mastery thrives when you fight together.");
            return customItemService.createIcon("beast_companion_sigil", "No Bonded Companion", lore);
        }

        Entity entity = (Entity) companion;
        String typeName = NameFormat.friendly(entity.getType().name());
        lore.add("Type: " + typeName);
        lore.add("World: " + entity.getWorld().getName());
        lore.add("Location: " + entity.getLocation().getBlockX() + ", " + entity.getLocation().getBlockY() + ", " + entity.getLocation().getBlockZ());
        if (entity instanceof LivingEntity livingEntity) {
            lore.add("Health: " + String.format(Locale.US, "%.1f/%.1f", livingEntity.getHealth(), attributeValue(livingEntity, Attribute.MAX_HEALTH, livingEntity.getHealth())));
            lore.add("Attack: " + String.format(Locale.US, "%.1f", attributeBase(livingEntity, Attribute.ATTACK_DAMAGE, 3.0D)));
            lore.add("Move Speed: " + String.format(Locale.US, "%.2f", attributeBase(livingEntity, Attribute.MOVEMENT_SPEED, 0.30D)));
        }
        lore.add("Beast Mastery Level: " + progress.level());
        lore.add(progress.level() >= 35 ? "Guardian Bond is active while your companion stays nearby." : "Guardian Bond unlocks at level 35.");
        lore.add(progress.level() >= 50 ? "Alpha Command is ready from this HUD." : "Alpha Command unlocks at level 50.");
        return customItemService.createIcon("beast_companion_sigil", "Bonded Companion: " + typeName, lore);
    }

    private ItemStack alphaCommandIcon(Player player, ProfessionProgress progress, Tameable companion) {
        List<String> lore = new ArrayList<>();
        if (progress.level() < 50) {
            lore.add("Requires Beast Mastery level 50.");
            lore.add("Sneak-right-clicking your companion also unlocks at level 50.");
            return customItemService.createIcon("alpha_command_horn", "Alpha Command Locked", lore);
        }

        long remaining = cooldownRemaining(alphaCommandCooldowns, player.getUniqueId());
        lore.add(companion == null ? "No bonded companion found." : "Send your bonded companion into a focused assault.");
        lore.add(remaining > 0L ? "Cooldown: " + formatMillis(remaining) : "Cooldown: Ready");
        lore.add("Grants burst speed, strength, resistance, and target focus.");
        return customItemService.createIcon("alpha_command_horn", "Alpha Command", lore);
    }

    private List<String> beastUnlockLore(int level) {
        List<String> lore = new ArrayList<>();
        for (ProfessionUnlock unlock : unlocksByProfession.getOrDefault(ProfessionType.BEAST_MASTERY, List.of())) {
            lore.add((level >= unlock.level() ? "Unlocked " : "L" + unlock.level() + " ") + unlock.title() + " | " + unlock.description());
        }
        return lore;
    }

    private ItemStack engineeringStatusIcon(
        Player player,
        String title,
        int requiredLevel,
        String itemId,
        java.util.UUID activeEntityId,
        List<String> flavor
    ) {
        ProfessionProgress progress = progress(playerProfileService.profile(player), ProfessionType.ENGINEERING);
        List<String> lore = new ArrayList<>(flavor);
        lore.add("Requires Engineering " + requiredLevel);
        lore.add("Inventory: " + countCustomItems(player.getInventory(), itemId));
        lore.add(progress.level() >= requiredLevel ? "Unlocked for field use." : "Still locked.");

        Entity entity = activeEntityId == null ? null : org.bukkit.Bukkit.getEntity(activeEntityId);
        if (entity != null && entity.isValid()) {
            lore.add("Active: " + entity.getWorld().getName() + " @ "
                + entity.getLocation().getBlockX() + ", "
                + entity.getLocation().getBlockY() + ", "
                + entity.getLocation().getBlockZ());
        } else {
            lore.add("Active: None deployed.");
        }
        lore.add("Click to open the Engineering Station.");
        return customItemService.createIcon(itemId, title, lore);
    }

    private ItemStack engineeringAutoMinerIcon(Player player, ProfessionProgress progress) {
        List<String> lore = new ArrayList<>();
        lore.add("Requires Engineering 50.");
        lore.add("Inventory: " + countCustomItems(player.getInventory(), "auto_miner"));
        lore.add(progress.level() >= 50 ? "Unlocked for instant vein drilling." : "Still locked.");
        lore.add("Right-click an ore or stone block to carve through a compact vein.");
        lore.add("Click to open the Engineering Station.");
        return customItemService.createIcon("auto_miner", "Auto Miner", lore);
    }

    private List<String> engineeringReadoutLore(Player player, int level) {
        List<String> lore = new ArrayList<>();
        lore.add("Engineering Level: " + level);
        lore.add("Turret Kits: " + countCustomItems(player.getInventory(), "turret_kit"));
        lore.add("Shock Traps: " + countCustomItems(player.getInventory(), "shock_trap"));
        lore.add("Auto Miners: " + countCustomItems(player.getInventory(), "auto_miner"));
        lore.add(activeGadgetLine("Turret", activeTurrets.get(player.getUniqueId())));
        lore.add(activeGadgetLine("Shock Trap", activeShockTraps.get(player.getUniqueId())));
        lore.add(level >= 35 ? "Survey Logic: Active gadgets gain sturdier field time." : "Survey Logic unlocks at Engineering 35.");
        lore.add("Deploy gadgets by right-clicking a solid block with the crafted item.");
        return lore;
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void registerCustomItems() {
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "tempered_ingot",
            Material.IRON_INGOT,
            "Tempered Ingot",
            List.of("Forged alloy used in advanced Vortex crafting.", "Stable enough to channel fracture energy."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "breachsplitter",
            Material.DIAMOND_SWORD,
            "Breachsplitter",
            List.of("Forged to shear through breach champions."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "riftstring_longbow",
            Material.BOW,
            "Riftstring Longbow",
            List.of("A breach-tuned bow for precision hunts."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "wardplate",
            Material.DIAMOND_CHESTPLATE,
            "Wardplate",
            List.of("Layered armor infused with stabilizing alloy."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "turret_kit",
            Material.DISPENSER,
            "Turret Kit",
            List.of("Right-click a block to deploy a temporary auto-turret."),
            null,
            true,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "shock_trap",
            Material.TRIPWIRE_HOOK,
            "Shock Trap",
            List.of("Right-click a block to place a trap that detonates on enemy contact."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "auto_miner",
            Material.PISTON,
            "Auto Miner",
            List.of("Right-click an ore block to drill a compact vein instantly."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "focus_draught",
            Material.POTION,
            "Focus Draught",
            List.of("Consume to gain speed and night vision."),
            null,
            false,
            org.bukkit.Color.fromRGB(104, 169, 255),
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "breach_salve",
            Material.HONEY_BOTTLE,
            "Breach Salve",
            List.of("Consume to gain regeneration and absorption."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "riftguard_tonic",
            Material.POTION,
            "Riftguard Tonic",
            List.of("Consume to gain fire resistance and absorption."),
            null,
            false,
            org.bukkit.Color.fromRGB(136, 88, 214),
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "hunter_stew",
            Material.RABBIT_STEW,
            "Hunter's Stew",
            List.of("Consume to gain speed and a short regeneration burst."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "ember_skewer",
            Material.COOKED_BEEF,
            "Ember Skewer",
            List.of("Consume to gain fire resistance and speed."),
            null,
            false,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "overrun_feast",
            Material.GOLDEN_CARROT,
            "Overrun Feast",
            List.of("Consume to gain absorption and regeneration."),
            null,
            true,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "beast_companion_sigil",
            Material.LEAD,
            "Companion Sigil",
            List.of("A command focus for Beast Mastery bonds."),
            null,
            true,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "alpha_command_horn",
            Material.GOAT_HORN,
            "Alpha Command",
            List.of("Rally your bonded companion into a focused assault."),
            null,
            true,
            null,
            Map.of()
        ));
        customItemService.registerIfAbsent(new CustomItemDefinition(
            "engineering_console_core",
            Material.REDSTONE,
            "Engineering Console",
            List.of("Field telemetry for deployed gadgets and crafted kits."),
            null,
            true,
            null,
            Map.of()
        ));
    }

    private void registerGathering(long miningOreExperience, long miningStoneExperience, long foragingLogExperience, long farmingHarvestExperience, long fishingCatchExperience) {
        registerGather(ProfessionType.MINING, miningStoneExperience, "clearing stone veins", Material.STONE, Material.DEEPSLATE, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE);
        registerGather(ProfessionType.MINING, miningOreExperience, "extracting ore seams",
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS, Material.OBSIDIAN);
        registerGather(ProfessionType.FORAGING, foragingLogExperience, "harvesting timber",
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG,
            Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.CRIMSON_STEM, Material.WARPED_STEM);
        registerGather(ProfessionType.FARMING, farmingHarvestExperience, "bringing in a mature harvest", true,
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOT, Material.NETHER_WART);
        registerGather(ProfessionType.FISHING, fishingCatchExperience, "landing a clean catch", Material.KELP, Material.SEAGRASS);
        stationLookup.put(Material.SMITHING_TABLE, ProfessionType.SMITHING);
        stationLookup.put(Material.FLETCHING_TABLE, ProfessionType.FLETCHING);
        stationLookup.put(Material.ANVIL, ProfessionType.ARMORSMITHING);
        stationLookup.put(Material.CHIPPED_ANVIL, ProfessionType.ARMORSMITHING);
        stationLookup.put(Material.DAMAGED_ANVIL, ProfessionType.ARMORSMITHING);
        stationLookup.put(Material.CRAFTING_TABLE, ProfessionType.ENGINEERING);
        stationLookup.put(Material.BREWING_STAND, ProfessionType.ALCHEMY);
        stationLookup.put(Material.SMOKER, ProfessionType.COOKING);
        stationLookup.put(Material.CAMPFIRE, ProfessionType.COOKING);
    }

    private void registerRecipes() {
        registerRecipe(new ProfessionRecipe("tempered_ingot_recipe", ProfessionType.SMITHING, "Tempered Ingot", "Refine expedition metals into a stable alloy.", Material.IRON_INGOT, 1, 24, List.of(
            ingredient(Material.IRON_INGOT, 6, "Iron Ingot"),
            ingredient(Material.COAL, 2, "Coal"),
            ingredient(Material.ECHO_SHARD, 1, "Tainted Essence", "tainted_essence", ItemSource.FRACTURE)
        ), "tempered_ingot", 1));
        registerRecipe(new ProfessionRecipe("breachsplitter_recipe", ProfessionType.SMITHING, "Breachsplitter", "Upgrade a standard blade into an expedition weapon.", Material.DIAMOND_SWORD, 6, 68, List.of(
            ingredient(Material.DIAMOND_SWORD, 1, "Diamond Sword"),
            ingredient(Material.IRON_INGOT, 2, "Tempered Ingot", "tempered_ingot", ItemSource.PROFESSION),
            ingredient(Material.NETHERITE_SCRAP, 1, "Stabilized Relic", "stabilized_relic", ItemSource.FRACTURE)
        ), "breachsplitter", 1));
        registerRecipe(new ProfessionRecipe("riftstring_longbow_recipe", ProfessionType.FLETCHING, "Riftstring Longbow", "Tune a ranger bow with fracture-drawn tension.", Material.BOW, 6, 74, List.of(
            ingredient(Material.BOW, 1, "Bow"),
            ingredient(Material.STRING, 4, "String"),
            ingredient(Material.PHANTOM_MEMBRANE, 2, "Phantom Membrane"),
            ingredient(Material.NETHERITE_SCRAP, 1, "Stabilized Relic", "stabilized_relic", ItemSource.FRACTURE)
        ), "riftstring_longbow", 1));
        registerRecipe(new ProfessionRecipe("wardplate_recipe", ProfessionType.ARMORSMITHING, "Wardplate", "Layer breach alloy into a defensive chestpiece.", Material.DIAMOND_CHESTPLATE, 8, 96, List.of(
            ingredient(Material.DIAMOND_CHESTPLATE, 1, "Diamond Chestplate"),
            ingredient(Material.IRON_INGOT, 6, "Iron Ingot"),
            ingredient(Material.NETHER_STAR, 1, "Breach Heart", "breach_heart", ItemSource.FRACTURE)
        ), "wardplate", 1));
        registerRecipe(new ProfessionRecipe("turret_kit_recipe", ProfessionType.ENGINEERING, "Turret Kit", "Assemble an autonomous defense pod for field control.", Material.DISPENSER, 4, 56, List.of(
            ingredient(Material.DISPENSER, 1, "Dispenser"),
            ingredient(Material.REDSTONE, 4, "Redstone"),
            ingredient(Material.IRON_INGOT, 3, "Iron Ingot"),
            ingredient(Material.QUARTZ, 2, "Quartz")
        ), "turret_kit", 1));
        registerRecipe(new ProfessionRecipe("shock_trap_recipe", ProfessionType.ENGINEERING, "Shock Trap", "Pack a compact field trap that detonates on contact.", Material.TRIPWIRE_HOOK, 8, 62, List.of(
            ingredient(Material.TRIPWIRE_HOOK, 2, "Tripwire Hook"),
            ingredient(Material.REDSTONE, 5, "Redstone"),
            ingredient(Material.COPPER_INGOT, 3, "Copper Ingot")
        ), "shock_trap", 2));
        registerRecipe(new ProfessionRecipe("auto_miner_recipe", ProfessionType.ENGINEERING, "Auto Miner", "Build a compact drilling charge for dense ore pockets.", Material.PISTON, 12, 92, List.of(
            ingredient(Material.PISTON, 1, "Piston"),
            ingredient(Material.REDSTONE_BLOCK, 1, "Redstone Block"),
            ingredient(Material.IRON_INGOT, 4, "Iron Ingot"),
            ingredient(Material.DIAMOND_PICKAXE, 1, "Diamond Pickaxe")
        ), "auto_miner", 1));
        registerRecipe(new ProfessionRecipe("focus_draught_recipe", ProfessionType.ALCHEMY, "Focus Draught", "A reconnaissance tonic for scouts and mages.", Material.POTION, 1, 22, List.of(
            ingredient(Material.GLASS_BOTTLE, 1, "Glass Bottle"),
            ingredient(Material.GLOW_BERRIES, 3, "Glow Berries"),
            ingredient(Material.SUGAR, 1, "Sugar")
        ), "focus_draught", 1));
        registerRecipe(new ProfessionRecipe("breach_salve_recipe", ProfessionType.ALCHEMY, "Breach Salve", "A thick restorative mixture for hard pushes.", Material.HONEY_BOTTLE, 4, 52, List.of(
            ingredient(Material.HONEY_BOTTLE, 1, "Honey Bottle"),
            ingredient(Material.ECHO_SHARD, 1, "Tainted Essence", "tainted_essence", ItemSource.FRACTURE),
            ingredient(Material.GOLDEN_APPLE, 1, "Golden Apple")
        ), "breach_salve", 1));
        registerRecipe(new ProfessionRecipe("riftguard_tonic_recipe", ProfessionType.ALCHEMY, "Riftguard Tonic", "A defensive tonic for breach tanks and supports.", Material.POTION, 8, 78, List.of(
            ingredient(Material.GLASS_BOTTLE, 1, "Glass Bottle"),
            ingredient(Material.NETHER_WART, 2, "Nether Wart"),
            ingredient(Material.NETHERITE_SCRAP, 1, "Stabilized Relic", "stabilized_relic", ItemSource.FRACTURE)
        ), "riftguard_tonic", 1));
        registerRecipe(new ProfessionRecipe("hunter_stew_recipe", ProfessionType.COOKING, "Hunter's Stew", "Comfort food for long gathering loops.", Material.RABBIT_STEW, 1, 20, List.of(
            ingredient(Material.COOKED_BEEF, 1, "Cooked Beef"),
            ingredient(Material.CARROT, 2, "Carrot"),
            ingredient(Material.BOWL, 1, "Bowl")
        ), "hunter_stew", 1));
        registerRecipe(new ProfessionRecipe("ember_skewer_recipe", ProfessionType.COOKING, "Ember Skewer", "Smoked meat prepared for volatile biomes.", Material.COOKED_BEEF, 4, 46, List.of(
            ingredient(Material.COOKED_BEEF, 2, "Cooked Beef"),
            ingredient(Material.BLAZE_POWDER, 1, "Blaze Powder"),
            ingredient(Material.STICK, 1, "Stick")
        ), "ember_skewer", 2));
        registerRecipe(new ProfessionRecipe("overrun_feast_recipe", ProfessionType.COOKING, "Overrun Feast", "High-end expedition food for boss pushes.", Material.GOLDEN_CARROT, 8, 72, List.of(
            ingredient(Material.GOLDEN_CARROT, 2, "Golden Carrot"),
            ingredient(Material.COOKED_SALMON, 2, "Cooked Salmon"),
            ingredient(Material.NETHER_STAR, 1, "Breach Heart", "breach_heart", ItemSource.FRACTURE)
        ), "overrun_feast", 1));
    }

    private void registerMarketItems() {
        marketManager.registerItem("tempered_ingot", 24.0D, 96L);
        marketManager.registerItem("breachsplitter", 340.0D, 18L);
        marketManager.registerItem("riftstring_longbow", 320.0D, 16L);
        marketManager.registerItem("wardplate", 420.0D, 14L);
        marketManager.registerItem("turret_kit", 180.0D, 32L);
        marketManager.registerItem("shock_trap", 94.0D, 48L);
        marketManager.registerItem("auto_miner", 260.0D, 18L);
        marketManager.registerItem("focus_draught", 18.0D, 80L);
        marketManager.registerItem("breach_salve", 54.0D, 40L);
        marketManager.registerItem("riftguard_tonic", 86.0D, 28L);
        marketManager.registerItem("hunter_stew", 14.0D, 120L);
        marketManager.registerItem("ember_skewer", 28.0D, 80L);
        marketManager.registerItem("overrun_feast", 75.0D, 20L);
    }

    private void registerMasteries() {
        registerMastery(new ProfessionMastery(
            ProfessionType.SWORDSMANSHIP,
            "Blade Tempo",
            "Sword practice sharpens tempo, spacing, and clean melee execution.",
            Map.of(VortexAttribute.STRENGTH, 2.0D, VortexAttribute.DEXTERITY, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.AXEMANSHIP,
            "Executioner's Drive",
            "Heavy weapon discipline rewards forceful finishing pressure.",
            Map.of(VortexAttribute.STRENGTH, 3.0D, VortexAttribute.VITALITY, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.ARCHERY,
            "Deadeye Rhythm",
            "Constant ranged practice improves pursuit and precision.",
            Map.of(VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.WISDOM, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.SPELLCRAFT,
            "Arcane Cadence",
            "Spell repetition reinforces magical control and battle focus.",
            Map.of(VortexAttribute.INTELLIGENCE, 2.0D, VortexAttribute.WISDOM, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.DEFENSE,
            "Bulwark Instinct",
            "Enduring pressure hardens your tanking fundamentals.",
            Map.of(VortexAttribute.VITALITY, 3.0D, VortexAttribute.SPIRIT, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.DUAL_WIELD,
            "Shadow Tempo",
            "Fast off-hand skirmishing rewards precise burst windows.",
            Map.of(VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.STRENGTH, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.BEAST_MASTERY,
            "Pack Sovereign",
            "Your bond with companions sharpens survival and coordinated pressure.",
            Map.of(VortexAttribute.WISDOM, 2.0D, VortexAttribute.SPIRIT, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.MINING,
            "Ironheart",
            "Permanent mining discipline hardens your frontline body.",
            Map.of(VortexAttribute.STRENGTH, 2.0D, VortexAttribute.VITALITY, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.FORAGING,
            "Trailwarden",
            "Fieldcraft sharpens your survival reads and route discipline.",
            Map.of(VortexAttribute.VITALITY, 2.0D, VortexAttribute.WISDOM, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.FARMING,
            "Harvest Sage",
            "Steady field work strengthens sustain and route planning.",
            Map.of(VortexAttribute.SPIRIT, 2.0D, VortexAttribute.WISDOM, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.FISHING,
            "Deepcurrent",
            "Patient angling feeds calmer judgment and support instincts.",
            Map.of(VortexAttribute.WISDOM, 2.0D, VortexAttribute.SPIRIT, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.SMITHING,
            "Bladebound",
            "Weapon discipline sharpens melee finishing power.",
            Map.of(VortexAttribute.STRENGTH, 3.0D, VortexAttribute.DEXTERITY, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.FLETCHING,
            "Windstring",
            "Bow craft steadies ranged hands and route judgment.",
            Map.of(VortexAttribute.DEXTERITY, 3.0D, VortexAttribute.WISDOM, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.ARMORSMITHING,
            "Bulwark Temper",
            "Armor forging permanently toughens your front line frame.",
            Map.of(VortexAttribute.VITALITY, 3.0D, VortexAttribute.STRENGTH, 1.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.ENGINEERING,
            "Siegebreak Logic",
            "Field devices reward technical discipline and route control.",
            Map.of(VortexAttribute.INTELLIGENCE, 2.0D, VortexAttribute.DEXTERITY, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.ALCHEMY,
            "Elixir Savant",
            "Mixing volatile compounds improves magical insight.",
            Map.of(VortexAttribute.INTELLIGENCE, 3.0D, VortexAttribute.WISDOM, 2.0D)
        ));
        registerMastery(new ProfessionMastery(
            ProfessionType.COOKING,
            "Field Quartermaster",
            "Supplying the campaign deepens resilience and sustain.",
            Map.of(VortexAttribute.VITALITY, 2.0D, VortexAttribute.SPIRIT, 2.0D)
        ));
    }

    private void registerUnlocks() {
        registerUnlock(ProfessionType.SWORDSMANSHIP, 5, "Edge Tempo", "+5% sword damage on clean strikes.");
        registerUnlock(ProfessionType.SWORDSMANSHIP, 10, "Combo Strike", "Every third sword hit deals bonus damage.");
        registerUnlock(ProfessionType.SWORDSMANSHIP, 20, "Dash Slash", "Sprinting sword hits hit harder.");
        registerUnlock(ProfessionType.SWORDSMANSHIP, 35, "Parry", "Timed right-clicks soften incoming hits.");
        registerUnlock(ProfessionType.SWORDSMANSHIP, 50, "Blade Storm", "Sword hits cleave nearby enemies on cooldown.");

        registerUnlock(ProfessionType.AXEMANSHIP, 5, "Sunder Edge", "Axes punch harder through targets.");
        registerUnlock(ProfessionType.AXEMANSHIP, 10, "Cleave", "Heavy swings splash nearby enemies.");
        registerUnlock(ProfessionType.AXEMANSHIP, 20, "Heavy Strike", "Fully charged axe hits spike harder.");
        registerUnlock(ProfessionType.AXEMANSHIP, 35, "Bleed", "Axes can apply bleed pressure.");
        registerUnlock(ProfessionType.AXEMANSHIP, 50, "Rage Mode", "Low-health axe swings gain lifesteal.");

        registerUnlock(ProfessionType.ARCHERY, 5, "Draw Tension", "Arrow hits deal more damage.");
        registerUnlock(ProfessionType.ARCHERY, 10, "Power Shot", "Critical arrows spike harder.");
        registerUnlock(ProfessionType.ARCHERY, 20, "Split Flight", "Arrow impacts splash nearby enemies.");
        registerUnlock(ProfessionType.ARCHERY, 35, "Piercing Arrow", "Arrow hits gain extra flat damage.");
        registerUnlock(ProfessionType.ARCHERY, 50, "Rain of Arrows", "Arrow impacts can trigger a strike zone.");

        registerUnlock(ProfessionType.SPELLCRAFT, 5, "Arcane Flow", "Spell use advances magical momentum.");
        registerUnlock(ProfessionType.SPELLCRAFT, 10, "Focused Casting", "Custom class-gated spells open faster to you.");
        registerUnlock(ProfessionType.SPELLCRAFT, 20, "Spell Synergy", "Spellcraft scales with your class path.");
        registerUnlock(ProfessionType.SPELLCRAFT, 35, "Chain Insight", "High-end spell chains become easier to build.");
        registerUnlock(ProfessionType.SPELLCRAFT, 50, "Meteor Logic", "Top-end spell profiles are ready for content authors.");

        registerUnlock(ProfessionType.DEFENSE, 5, "Battle Conditioning", "You take slightly less incoming damage.");
        registerUnlock(ProfessionType.DEFENSE, 10, "Damage Reduction", "Tanking discipline further hardens you.");
        registerUnlock(ProfessionType.DEFENSE, 20, "Shield Block", "Blocking shields more of the hit.");
        registerUnlock(ProfessionType.DEFENSE, 35, "Taunt Presence", "Strong blocks drag nearby mobs toward you.");
        registerUnlock(ProfessionType.DEFENSE, 50, "Fortress Mode", "Blocking or sneaking hardens you further.");

        registerUnlock(ProfessionType.DUAL_WIELD, 5, "Crit Edge", "Dual wielding unlocks crit bursts.");
        registerUnlock(ProfessionType.DUAL_WIELD, 10, "Backstab", "Rear-angle attacks hit harder.");
        registerUnlock(ProfessionType.DUAL_WIELD, 20, "Poisoned Steel", "Dual strikes can poison targets.");
        registerUnlock(ProfessionType.DUAL_WIELD, 35, "Fade Step", "Finishing dual-wield kills can veil you briefly.");
        registerUnlock(ProfessionType.DUAL_WIELD, 50, "Shadow Strike", "Dual-wield rushes spike harder.");

        registerUnlock(ProfessionType.BEAST_MASTERY, 5, "Bonded Hide", "Tamed companions gain bonus health.");
        registerUnlock(ProfessionType.BEAST_MASTERY, 10, "Feral Bite", "Tamed companions hit harder.");
        registerUnlock(ProfessionType.BEAST_MASTERY, 20, "Pack Rush", "Companions gain chase speed.");
        registerUnlock(ProfessionType.BEAST_MASTERY, 35, "Guardian Bond", "Nearby companions help cover you.");
        registerUnlock(ProfessionType.BEAST_MASTERY, 50, "Alpha Command", "Sneak-right-click your pet to rally it.");

        registerUnlock(ProfessionType.MINING, 5, "Clean Break", "Mining yields faster progression.");
        registerUnlock(ProfessionType.MINING, 20, "Double Drop", "Ore routes pay out better over time.");
        registerUnlock(ProfessionType.MINING, 35, "Deep Veins", "Rare seams feel more rewarding.");
        registerUnlock(ProfessionType.FORAGING, 5, "Fell Stroke", "Woodcutting routes speed up.");
        registerUnlock(ProfessionType.FORAGING, 20, "Timber Flow", "Double logs feel more natural.");
        registerUnlock(ProfessionType.FARMING, 5, "Green Thumb", "Harvest loops feel smoother.");
        registerUnlock(ProfessionType.FARMING, 20, "Abundant Yield", "Crop returns scale upward.");
        registerUnlock(ProfessionType.FISHING, 5, "Lucky Cast", "Fishing pulls feel better.");
        registerUnlock(ProfessionType.FISHING, 20, "Treasure Drift", "Rare catches appear more often.");

        registerUnlock(ProfessionType.ENGINEERING, 5, "Field Wiring", "Engineering crafts become more consistent.");
        registerUnlock(ProfessionType.ENGINEERING, 10, "Turret Kit", "You can field an autonomous turret.");
        registerUnlock(ProfessionType.ENGINEERING, 20, "Shock Trap", "You can lock down lanes with trap bursts.");
        registerUnlock(ProfessionType.ENGINEERING, 35, "Survey Logic", "Your gadgets become sturdier.");
        registerUnlock(ProfessionType.ENGINEERING, 50, "Auto Miner", "You can drill ore pockets with compact machines.");
    }

    private void registerGather(ProfessionType profession, long experience, String reason, Material... materials) {
        registerGather(profession, experience, reason, false, materials);
    }

    private void registerGather(ProfessionType profession, long experience, String reason, boolean maturityCheck, Material... materials) {
        GatherReward reward = new GatherReward(profession, experience, reason, maturityCheck);
        for (Material material : materials) {
            gatherRewards.put(material, reward);
        }
    }

    private void registerRecipe(ProfessionRecipe recipe) {
        recipesByProfession.computeIfAbsent(recipe.profession(), ignored -> new ArrayList<>()).add(recipe);
    }

    private void registerMastery(ProfessionMastery mastery) {
        masteries.put(mastery.type(), mastery);
    }

    private RecipeIngredient ingredient(Material material, int amount, String displayName) {
        return ingredient(material, amount, displayName, null, ItemSource.ANY);
    }

    private RecipeIngredient ingredient(Material material, int amount, String displayName, String customItemId, ItemSource source) {
        return new RecipeIngredient(material, amount, displayName, customItemId, source);
    }

    private String compact(UnifiedPlayer profile, ProfessionType type) {
        return abbreviation(type) + progress(profile, type).level();
    }

    private String abbreviation(ProfessionType type) {
        return switch (type) {
            case SWORDSMANSHIP -> "Sw";
            case AXEMANSHIP -> "Ax";
            case ARCHERY -> "Ar";
            case SPELLCRAFT -> "Sp";
            case DEFENSE -> "Df";
            case DUAL_WIELD -> "Dw";
            case BEAST_MASTERY -> "Bm";
            case MINING -> "Mi";
            case FORAGING -> "Wd";
            case FARMING -> "Fm";
            case FISHING -> "Fi";
            case SMITHING -> "Ws";
            case FLETCHING -> "Fl";
            case ARMORSMITHING -> "Am";
            case ENGINEERING -> "En";
            case ALCHEMY -> "Al";
            case COOKING -> "Co";
        };
    }

    private int unlockedMasteryCount(UnifiedPlayer profile) {
        int unlocked = 0;
        for (ProfessionType type : ProfessionType.values()) {
            if (hasMastery(profile, type)) {
                unlocked++;
            }
        }
        return unlocked;
    }

    private void reconcileMastery(UnifiedPlayer profile, ProfessionType type, Player player) {
        if (hasMastery(profile, type)) {
            return;
        }
        int level = Math.max(1, readInt(profile, META_LEVEL_PREFIX + type.id(), 1));
        if (level < masteryLevelThreshold) {
            return;
        }
        ProfessionMastery mastery = masteries.get(type);
        if (mastery == null) {
            return;
        }
        profile.putMetadata(META_MASTERY_PREFIX + type.id(), Boolean.TRUE.toString());
        mastery.permanentBonuses().forEach((attribute, bonus) -> {
            double currentBase = profile.stat(attribute).snapshot().baseValue();
            profile.stat(attribute).setBase(currentBase + bonus);
        });
        if (player != null) {
            playerProfileService.refreshProfile(player);
            messageService.send(player, type.displayName() + " mastery unlocked: " + mastery.displayName() + ".");
        }
    }

    private long readLong(UnifiedPlayer profile, String key) {
        try {
            return Long.parseLong(profile.metadata().getOrDefault(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private int readInt(UnifiedPlayer profile, String key, int fallback) {
        try {
            return Integer.parseInt(profile.metadata().getOrDefault(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record ProfessionProgress(ProfessionType type, int level, long experience, long experienceToNextLevel) {
    }

    public record ProfessionMastery(
        ProfessionType type,
        String displayName,
        String description,
        Map<VortexAttribute, Double> permanentBonuses
    ) {
    }

    private record GatherReward(ProfessionType profession, long experience, String reason, boolean requiresMaturityCheck) {
    }

    private record RecipeIngredient(Material material, int amount, String displayName, String customItemId, ItemSource source) {
    }

    private record ProfessionUnlock(int level, String title, String description) {
    }

    private record ProfessionRecipe(
        String id,
        ProfessionType profession,
        String displayName,
        String description,
        Material icon,
        int requiredLevel,
        int experienceReward,
        List<RecipeIngredient> ingredients,
        String outputItemId,
        int outputAmount
    ) {
        private ProfessionRecipe {
            Objects.requireNonNull(profession, "profession");
            Objects.requireNonNull(ingredients, "ingredients");
        }
    }

    private enum ItemSource {
        ANY,
        PROFESSION,
        FRACTURE
    }

    public interface ProfessionActivityListener {

        void onGather(Player player, ProfessionType profession, long amount);

        void onCraft(Player player, ProfessionType profession, String itemId, int crafts);
    }
}
