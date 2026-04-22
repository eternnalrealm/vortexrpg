package io.vortexcore.world;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.item.CustomItemService;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class FractureGatewayService implements Listener {

    private static final String RETURN_WORLD_KEY = "fracture_gateway.return_world";
    private static final String RETURN_X_KEY = "fracture_gateway.return_x";
    private static final String RETURN_Y_KEY = "fracture_gateway.return_y";
    private static final String RETURN_Z_KEY = "fracture_gateway.return_z";
    private static final String RETURN_YAW_KEY = "fracture_gateway.return_yaw";
    private static final String RETURN_PITCH_KEY = "fracture_gateway.return_pitch";
    private static final int MONUMENT_RADIUS = 3;
    private static final int MONUMENT_MIN_Y_OFFSET = -1;
    private static final int MONUMENT_MAX_Y_OFFSET = 5;

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final VortexMenuBuilder menuBuilder;
    private final CustomItemService customItemService;
    private final PlayerProfileService playerProfileService;
    private final FractureNavigatorService fractureNavigatorService;
    private final FractureRealmService fractureRealmService;
    private final ProtectionHookService protectionHookService;
    private final MessageService messageService;
    private final File gatewaysFile;
    private final boolean enabled;
    private final boolean autoCreatePrimary;
    private final boolean autoCreateReturn;
    private final boolean buildStructures;
    private final boolean ambientParticlesEnabled;
    private final long monumentPulseTicks;
    private final String primaryWorldName;
    private final int primaryOffsetX;
    private final int primaryOffsetZ;
    private final String primaryGatewayId;
    private final String returnGatewayId;
    private final Map<String, GatewayPoint> gateways;
    private VortexTask pulseTask;

    public FractureGatewayService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        CustomItemService customItemService,
        PlayerProfileService playerProfileService,
        FractureNavigatorService fractureNavigatorService,
        FractureRealmService fractureRealmService,
        ProtectionHookService protectionHookService,
        MessageService messageService,
        boolean enabled,
        boolean autoCreatePrimary,
        boolean autoCreateReturn,
        boolean buildStructures,
        boolean ambientParticlesEnabled,
        long monumentPulseTicks,
        String primaryWorldName,
        int primaryOffsetX,
        int primaryOffsetZ,
        String primaryGatewayId,
        String returnGatewayId
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.customItemService = customItemService;
        this.playerProfileService = playerProfileService;
        this.fractureNavigatorService = fractureNavigatorService;
        this.fractureRealmService = fractureRealmService;
        this.protectionHookService = protectionHookService;
        this.messageService = messageService;
        this.gatewaysFile = new File(plugin.getDataFolder(), "gateways.yml");
        this.enabled = enabled;
        this.autoCreatePrimary = autoCreatePrimary;
        this.autoCreateReturn = autoCreateReturn;
        this.buildStructures = buildStructures;
        this.ambientParticlesEnabled = ambientParticlesEnabled;
        this.monumentPulseTicks = Math.max(20L, monumentPulseTicks);
        this.primaryWorldName = primaryWorldName == null ? "" : primaryWorldName.trim();
        this.primaryOffsetX = primaryOffsetX;
        this.primaryOffsetZ = primaryOffsetZ;
        this.primaryGatewayId = normalizeId(primaryGatewayId == null ? "worldspawn" : primaryGatewayId);
        this.returnGatewayId = normalizeId(returnGatewayId == null ? "realm_return" : returnGatewayId);
        this.gateways = new ConcurrentHashMap<>();
    }

    public void bootstrap() {
        if (!enabled) {
            return;
        }
        reload();
        ensureDefaultGateways();
        startAmbientPulses();
    }

    public void stop() {
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
    }

    public synchronized void reload() {
        gateways.clear();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(gatewaysFile);
        ConfigurationSection section = configuration.getConfigurationSection("gateways");
        if (section == null) {
            return;
        }

        for (String rawId : section.getKeys(false)) {
            ConfigurationSection gatewaySection = section.getConfigurationSection(rawId);
            if (gatewaySection == null) {
                continue;
            }
            GatewayRole role = GatewayRole.parse(gatewaySection.getString("role", "entry"));
            if (role == null) {
                role = GatewayRole.ENTRY;
            }
            GatewayPoint gateway = new GatewayPoint(
                normalizeId(rawId),
                gatewaySection.getString("display-name", friendlyName(rawId)),
                role,
                gatewaySection.getString("world", ""),
                gatewaySection.getInt("x"),
                gatewaySection.getInt("y"),
                gatewaySection.getInt("z"),
                (float) gatewaySection.getDouble("yaw", 0.0D),
                (float) gatewaySection.getDouble("pitch", 0.0D),
                gatewaySection.getBoolean("generated", false)
            );
            gateways.put(gateway.id(), gateway);
        }
    }

    public StatusSnapshot statusSnapshot() {
        long entryCount = gateways.values().stream().filter(gateway -> gateway.role() == GatewayRole.ENTRY).count();
        long returnCount = gateways.values().stream().filter(gateway -> gateway.role() == GatewayRole.RETURN).count();
        return new StatusSnapshot(enabled, gateways.size(), (int) entryCount, (int) returnCount);
    }

    public List<String> gatewayLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(statusSnapshot().describeLine());
        gateways.values().stream()
            .sorted(Comparator.comparing(GatewayPoint::role).thenComparing(GatewayPoint::id))
            .forEach(gateway -> lines.add(
                gateway.id()
                    + " -> " + gateway.role().name().toLowerCase(Locale.ROOT)
                    + " | world=" + gateway.worldName()
                    + " | " + gateway.x() + ", " + gateway.y() + ", " + gateway.z()
                    + " | generated=" + gateway.generated()
            ));
        return List.copyOf(lines);
    }

    public void openGatewayMenu(Player player) {
        openGatewayMenu(player, findGatewayAtBlock(player.getLocation().getBlock()).orElse(null));
    }

    public CompletableFuture<GatewayPoint> createGateway(String rawId, GatewayRole role, Location location, boolean overwrite) {
        CompletableFuture<GatewayPoint> future = new CompletableFuture<>();
        if (!enabled) {
            future.completeExceptionally(new IllegalStateException("Fracture gateways are disabled."));
            return future;
        }
        World world = location.getWorld();
        if (world == null) {
            future.completeExceptionally(new IllegalArgumentException("Gateway location must belong to a world."));
            return future;
        }

        String id = normalizeId(rawId);
        if (id.isBlank()) {
            future.completeExceptionally(new IllegalArgumentException("Gateway id cannot be blank."));
            return future;
        }
        if (!overwrite && gateways.containsKey(id)) {
            future.completeExceptionally(new IllegalStateException("A gateway with that id already exists."));
            return future;
        }

        scheduler.runRegion(location, "fracture-gateway-create-" + id, () -> {
            Location anchor = sanitizeAnchor(location);
            if (buildStructures) {
                buildStructure(anchor, role);
            } else {
                anchor.getBlock().setType(Material.LODESTONE, false);
            }

            GatewayPoint gateway = new GatewayPoint(
                id,
                role == GatewayRole.ENTRY ? "Fracture Gateway" : "Return Shrine",
                role,
                world.getName(),
                anchor.getBlockX(),
                anchor.getBlockY(),
                anchor.getBlockZ(),
                anchor.getYaw(),
                anchor.getPitch(),
                true
            );
            gateways.put(id, gateway);
            save();
            future.complete(gateway);
        });
        return future;
    }

    public CompletableFuture<Boolean> returnPlayer(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        return teleportPlayer(player, resolveReturnLocation(profile), "Fracture gateway return");
    }

    public CompletableFuture<GatewayPoint> deleteGateway(String rawId) {
        CompletableFuture<GatewayPoint> future = new CompletableFuture<>();
        if (!enabled) {
            future.completeExceptionally(new IllegalStateException("Fracture gateways are disabled."));
            return future;
        }
        String id = normalizeId(rawId);
        if (id.isBlank()) {
            future.completeExceptionally(new IllegalArgumentException("Gateway id cannot be blank."));
            return future;
        }
        GatewayPoint removed = gateways.remove(id);
        if (removed == null) {
            future.completeExceptionally(new IllegalStateException("No gateway exists with id '" + id + "'."));
            return future;
        }
        save();
        logger.info("Gateway deleted: " + id + " (" + removed.role().name().toLowerCase(Locale.ROOT) + " in " + removed.worldName() + ")");
        future.complete(removed);
        return future;
    }

    public List<String> gatewayIds() {
        return gateways.keySet().stream().sorted().toList();
    }

    public CompletableFuture<List<GatewayPoint>> generateGateways(int count) {
        CompletableFuture<List<GatewayPoint>> future = new CompletableFuture<>();
        if (!enabled) {
            future.completeExceptionally(new IllegalStateException("Fracture gateways are disabled."));
            return future;
        }
        if (count < 1 || count > 50) {
            future.completeExceptionally(new IllegalArgumentException("Count must be between 1 and 50."));
            return future;
        }

        World primaryWorld = resolvePrimaryWorld();
        if (primaryWorld == null) {
            future.completeExceptionally(new IllegalStateException("No primary overworld is available."));
            return future;
        }

        fractureRealmService.ensureRealm().whenComplete((realmWorld, realmError) -> {
            if (realmError != null || realmWorld == null) {
                future.completeExceptionally(new IllegalStateException("Fracture realm is not available: " + friendlyError(realmError)));
                return;
            }

            List<GatewayPoint> created = new ArrayList<>();
            List<CompletableFuture<GatewayPoint>> pending = new ArrayList<>();

            Location primarySpawn = primaryWorld.getSpawnLocation();
            Location realmSpawn = realmWorld.getSpawnLocation();

            for (int i = 1; i <= count; i++) {
                double angle = (2.0D * Math.PI * i) / count;
                int radius = 40 + (i * 25);

                int entryX = primarySpawn.getBlockX() + (int) (Math.cos(angle) * radius);
                int entryZ = primarySpawn.getBlockZ() + (int) (Math.sin(angle) * radius);
                int entryY = Math.max(primaryWorld.getMinHeight() + 1, primaryWorld.getHighestBlockYAt(entryX, entryZ) + 1);
                String entryId = "gen_entry_" + i;
                pending.add(createGateway(entryId, GatewayRole.ENTRY, new Location(primaryWorld, entryX + 0.5D, entryY, entryZ + 0.5D), true));

                int returnX = realmSpawn.getBlockX() + (int) (Math.cos(angle) * radius);
                int returnZ = realmSpawn.getBlockZ() + (int) (Math.sin(angle) * radius);
                int returnY = Math.max(realmWorld.getMinHeight() + 1, realmWorld.getHighestBlockYAt(returnX, returnZ) + 1);
                String returnId = "gen_return_" + i;
                pending.add(createGateway(returnId, GatewayRole.RETURN, new Location(realmWorld, returnX + 0.5D, returnY, returnZ + 0.5D), true));
            }

            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ignored, batchError) -> {
                for (CompletableFuture<GatewayPoint> task : pending) {
                    if (!task.isCompletedExceptionally()) {
                        task.thenAccept(created::add);
                    }
                }
                if (created.isEmpty() && batchError != null) {
                    future.completeExceptionally(batchError);
                } else {
                    logger.info("Generated " + created.size() + " gateways (" + count + " entry + " + count + " return).");
                    future.complete(List.copyOf(created));
                }
            });
        });
        return future;
    }

    public Optional<GatewayPoint> gateway(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(gateways.get(normalizeId(id)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        Optional<GatewayPoint> gateway = findGatewayAtBlock(clickedBlock);
        if (gateway.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        ProtectionCheckResult protection = protectionHookService.check(player, clickedBlock.getLocation(), ProtectionAction.GATEWAY_USE);
        if (!protection.allowed()) {
            event.setCancelled(true);
            messageService.send(player, ProtectionHookService.denyMessage(protection, ProtectionAction.GATEWAY_USE));
            return;
        }

        event.setCancelled(true);
        openGatewayMenu(player, gateway.get());
    }

    private Optional<GatewayPoint> findGatewayAtBlock(Block block) {
        Location location = block.getLocation();
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return gateways.values().stream()
            .filter(gateway -> gateway.worldName().equalsIgnoreCase(location.getWorld().getName()))
            .filter(gateway -> withinGatewayMonument(location, gateway))
            .min(Comparator.comparingInt(gateway -> monumentDistance(location, gateway)));
    }

    private void openGatewayMenu(Player player, GatewayPoint sourceGateway) {
        boolean inRealm = fractureRealmService.realmWorld()
            .map(world -> world.getUID().equals(player.getWorld().getUID()))
            .orElse(false);

        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Fracture Gateway"), 3);

        List<String> travelLore = new ArrayList<>();
        travelLore.add(inRealm
            ? "Return to your last safe location outside the fracture realm."
            : "Traverse into the dedicated fracture realm."
        );
        if (sourceGateway != null) {
            travelLore.add("Anchor: " + sourceGateway.displayName());
        }
        builder.button(11, customItemService.createIcon("gui_mining_compass", inRealm ? "Return Passage" : "Traverse Gateway", travelLore), context -> {
            CompletableFuture<Void> menuFuture = new CompletableFuture<>();
            CompletableFuture<Boolean> travel = inRealm ? returnPlayer(player) : sendToRealm(player);
            travel.whenComplete((success, error) -> {
                if (error != null) {
                    scheduleMessage(player, "Gateway travel failed: " + friendlyError(error));
                }
                menuFuture.complete(null);
            });
            return menuFuture;
        }, true);

        List<String> intelLore = List.of(
            "Get a live map to the nearest active fracture.",
            inRealm ? "Use it to navigate the realm." : "Use it to plan your next run."
        );
        builder.button(13, customItemService.createIcon("gui_adventure_scroll", "Fracture Intel", intelLore), context -> {
            CompletableFuture<Void> menuFuture = new CompletableFuture<>();
            fractureNavigatorService.nearestFor(player).ifPresentOrElse(fracture -> fractureNavigatorService.createMap(player, fracture)
                .whenComplete((mapItem, error) -> {
                    if (error != null) {
                        scheduleMessage(player, "Unable to forge fracture map: " + friendlyError(error));
                        menuFuture.complete(null);
                        return;
                    }
                    scheduler.runEntity(player, "fracture-gateway-map-give-" + player.getUniqueId(), () -> {
                        Map<Integer, ItemStack> overflow = player.getInventory().addItem(mapItem);
                        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                        messageService.send(player, "Fracture map issued.");
                        menuFuture.complete(null);
                    });
                }), () -> {
                    scheduleMessage(player, "No active fracture is available right now.");
                    menuFuture.complete(null);
                });
            return menuFuture;
        });

        List<String> relocateLore = new ArrayList<>();
        relocateLore.add("Teleport to the nearest safe gateway anchor.");
        relocateLore.add(inRealm ? "Use this to regroup at the realm shrine." : "Use this to find the primary gateway.");
        builder.button(15, icon(Material.ENDER_PEARL, inRealm ? "Realm Shrine" : "Primary Gateway", relocateLore), context -> {
            CompletableFuture<Void> menuFuture = new CompletableFuture<>();
            Optional<GatewayPoint> gateway = inRealm ? firstGateway(GatewayRole.RETURN) : firstGateway(GatewayRole.ENTRY);
            CompletableFuture<Boolean> relocate = gateway.isPresent()
                ? teleportPlayer(player, resolveGatewayLocation(gateway.get()), "Gateway relocation")
                : failedFuture(new IllegalStateException("No gateway anchor is available."));
            relocate.whenComplete((success, error) -> {
                if (error != null) {
                    scheduleMessage(player, "Unable to relocate: " + friendlyError(error));
                }
                menuFuture.complete(null);
            });
            return menuFuture;
        }, true);

        menuBuilder.open(player, builder.build());
    }

    private CompletableFuture<Boolean> sendToRealm(Player player) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        storeReturnLocation(profile, player.getLocation());
        return fractureRealmService.ensureRealm().thenCompose(world -> {
            if (world == null) {
                return failedFuture(new IllegalStateException("Fracture realm is not enabled."));
            }
            return teleportPlayer(player, resolveRealmEntryLocation(world), "Fracture gateway traversal");
        });
    }

    private CompletableFuture<Boolean> teleportPlayer(Player player, CompletableFuture<Location> destinationFuture, String reason) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        destinationFuture.whenComplete((destination, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (destination == null || destination.getWorld() == null) {
                result.completeExceptionally(new IllegalStateException("Gateway destination is unavailable."));
                return;
            }

            scheduler.runEntity(player, "fracture-gateway-teleport-" + player.getUniqueId(), () ->
                player.teleportAsync(destination).whenComplete((success, teleportError) -> {
                    if (teleportError != null) {
                        result.completeExceptionally(teleportError);
                        return;
                    }
                    if (!Boolean.TRUE.equals(success)) {
                        result.completeExceptionally(new IllegalStateException("Teleport was cancelled."));
                        return;
                    }
                    playGatewayEffects(destination);
                    messageService.send(player, reason + " complete.");
                    result.complete(true);
                })
            );
        });
        return result;
    }

    private CompletableFuture<Location> resolveRealmEntryLocation(World realmWorld) {
        Optional<GatewayPoint> returnGateway = firstGateway(GatewayRole.RETURN)
            .filter(gateway -> gateway.worldName().equalsIgnoreCase(realmWorld.getName()));
        if (returnGateway.isPresent()) {
            return resolveGatewayLocation(returnGateway.get());
        }

        CompletableFuture<Location> future = new CompletableFuture<>();
        scheduler.runGlobal("fracture-gateway-realm-spawn", () -> future.complete(realmWorld.getSpawnLocation()));
        return future;
    }

    private CompletableFuture<Location> resolveReturnLocation(UnifiedPlayer profile) {
        String worldName = profile.metadata().getOrDefault(RETURN_WORLD_KEY, "");
        World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        if (world != null) {
            try {
                double x = Double.parseDouble(profile.metadata().getOrDefault(RETURN_X_KEY, "0"));
                double y = Double.parseDouble(profile.metadata().getOrDefault(RETURN_Y_KEY, "0"));
                double z = Double.parseDouble(profile.metadata().getOrDefault(RETURN_Z_KEY, "0"));
                float yaw = Float.parseFloat(profile.metadata().getOrDefault(RETURN_YAW_KEY, "0"));
                float pitch = Float.parseFloat(profile.metadata().getOrDefault(RETURN_PITCH_KEY, "0"));
                return CompletableFuture.completedFuture(new Location(world, x, y, z, yaw, pitch));
            } catch (NumberFormatException ignored) {
                logger.fine("Stored fracture gateway return location was malformed.");
            }
        }

        Optional<GatewayPoint> primaryGateway = firstGateway(GatewayRole.ENTRY);
        if (primaryGateway.isPresent()) {
            return resolveGatewayLocation(primaryGateway.get());
        }

        CompletableFuture<Location> future = new CompletableFuture<>();
        scheduler.runGlobal("fracture-gateway-return-fallback", () -> {
            World fallbackWorld = resolvePrimaryWorld();
            if (fallbackWorld == null) {
                future.completeExceptionally(new IllegalStateException("No valid return world is available."));
                return;
            }
            future.complete(fallbackWorld.getSpawnLocation());
        });
        return future;
    }

    private CompletableFuture<Location> resolveGatewayLocation(GatewayPoint gateway) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        World world = Bukkit.getWorld(gateway.worldName());
        if (world == null) {
            future.completeExceptionally(new IllegalStateException("Gateway world " + gateway.worldName() + " is not loaded."));
            return future;
        }

        scheduler.runRegion(world, gateway.x() >> 4, gateway.z() >> 4, "fracture-gateway-locate-" + gateway.id(), () ->
            future.complete(new Location(world, gateway.x() + 0.5D, gateway.y() + 1.0D, gateway.z() + 0.5D, gateway.yaw(), gateway.pitch()))
        );
        return future;
    }

    private void ensureDefaultGateways() {
        if (autoCreatePrimary && firstGateway(GatewayRole.ENTRY).isEmpty()) {
            World primaryWorld = resolvePrimaryWorld();
            if (primaryWorld != null) {
                Location spawn = primaryWorld.getSpawnLocation();
                int blockX = spawn.getBlockX() + primaryOffsetX;
                int blockZ = spawn.getBlockZ() + primaryOffsetZ;
                int blockY = Math.max(primaryWorld.getMinHeight() + 1, primaryWorld.getHighestBlockYAt(blockX, blockZ) + 1);
                createGateway(primaryGatewayId, GatewayRole.ENTRY, new Location(primaryWorld, blockX + 0.5D, blockY, blockZ + 0.5D), false)
                    .exceptionally(error -> {
                        logger.warning("Unable to create the primary fracture gateway: " + friendlyError(error));
                        return null;
                    });
            }
        }

        if (autoCreateReturn && firstGateway(GatewayRole.RETURN).isEmpty()) {
            fractureRealmService.ensureRealm().whenComplete((world, error) -> {
                if (error != null || world == null) {
                    logger.warning("Unable to create the fracture realm shrine: " + friendlyError(error));
                    return;
                }
                Location spawn = world.getSpawnLocation();
                int blockX = spawn.getBlockX();
                int blockZ = spawn.getBlockZ();
                int blockY = Math.max(world.getMinHeight() + 1, world.getHighestBlockYAt(blockX, blockZ) + 1);
                createGateway(returnGatewayId, GatewayRole.RETURN, new Location(world, blockX + 0.5D, blockY, blockZ + 0.5D), false)
                    .exceptionally(gatewayError -> {
                        logger.warning("Unable to create the fracture return shrine: " + friendlyError(gatewayError));
                        return null;
                    });
            });
        }
    }

    private synchronized void save() {
        if (!gatewaysFile.getParentFile().exists()) {
            gatewaysFile.getParentFile().mkdirs();
        }
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("resource-version", 1);
        gateways.values().stream()
            .sorted(Comparator.comparing(GatewayPoint::role).thenComparing(GatewayPoint::id))
            .forEach(gateway -> {
                String path = "gateways." + gateway.id();
                configuration.set(path + ".display-name", gateway.displayName());
                configuration.set(path + ".role", gateway.role().name().toLowerCase(Locale.ROOT));
                configuration.set(path + ".world", gateway.worldName());
                configuration.set(path + ".x", gateway.x());
                configuration.set(path + ".y", gateway.y());
                configuration.set(path + ".z", gateway.z());
                configuration.set(path + ".yaw", gateway.yaw());
                configuration.set(path + ".pitch", gateway.pitch());
                configuration.set(path + ".generated", gateway.generated());
            });
        try {
            configuration.save(gatewaysFile);
        } catch (IOException exception) {
            logger.warning("Unable to save gateways.yml: " + exception.getMessage());
        }
    }

    private Optional<GatewayPoint> firstGateway(GatewayRole role) {
        return gateways.values().stream()
            .filter(gateway -> gateway.role() == role)
            .sorted(Comparator.comparing(GatewayPoint::id))
            .findFirst();
    }

    private World resolvePrimaryWorld() {
        if (!primaryWorldName.isBlank()) {
            World configured = Bukkit.getWorld(primaryWorldName);
            if (configured != null) {
                return configured;
            }
        }
        return Bukkit.getWorlds().stream()
            .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
            .findFirst()
            .orElse(Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
    }

    private Location sanitizeAnchor(Location source) {
        World world = source.getWorld();
        int chunkX = source.getBlockX() >> 4;
        int chunkZ = source.getBlockZ() >> 4;
        int localX = Math.max(MONUMENT_RADIUS, Math.min(15 - MONUMENT_RADIUS, source.getBlockX() - (chunkX << 4)));
        int localZ = Math.max(MONUMENT_RADIUS, Math.min(15 - MONUMENT_RADIUS, source.getBlockZ() - (chunkZ << 4)));
        int anchorX = (chunkX << 4) + localX;
        int anchorZ = (chunkZ << 4) + localZ;
        return new Location(world, anchorX, source.getBlockY(), anchorZ, source.getYaw(), source.getPitch());
    }

    private void buildStructure(Location anchor, GatewayRole role) {
        World world = anchor.getWorld();
        if (world == null) {
            return;
        }

        int x = anchor.getBlockX();
        int y = anchor.getBlockY();
        int z = anchor.getBlockZ();
        if (role == GatewayRole.ENTRY) {
            buildEntryMonument(world, x, y, z);
        } else {
            buildReturnMonument(world, x, y, z);
        }
    }

    private void playGatewayEffects(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.PORTAL, location.clone().add(0.0D, 1.0D, 0.0D), 40, 0.6D, 0.9D, 0.6D, 0.15D);
        world.playSound(location, Sound.BLOCK_PORTAL_TRAVEL, 0.85F, 1.15F);
    }

    private void storeReturnLocation(UnifiedPlayer profile, Location location) {
        if (location.getWorld() == null) {
            return;
        }
        profile.putMetadata(RETURN_WORLD_KEY, location.getWorld().getName());
        profile.putMetadata(RETURN_X_KEY, Double.toString(location.getX()));
        profile.putMetadata(RETURN_Y_KEY, Double.toString(location.getY()));
        profile.putMetadata(RETURN_Z_KEY, Double.toString(location.getZ()));
        profile.putMetadata(RETURN_YAW_KEY, Float.toString(location.getYaw()));
        profile.putMetadata(RETURN_PITCH_KEY, Float.toString(location.getPitch()));
    }

    private ItemStack icon(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void scheduleMessage(Player player, String message) {
        scheduler.runEntity(player, "fracture-gateway-message-" + player.getUniqueId(), () -> messageService.send(player, message));
    }

    private void startAmbientPulses() {
        if (!ambientParticlesEnabled || !enabled) {
            return;
        }
        if (pulseTask != null) {
            pulseTask.cancel();
        }
        pulseTask = scheduler.runGlobalTimer("fracture-gateway-pulse", monumentPulseTicks, monumentPulseTicks, ignored -> pulseGateways());
    }

    private void pulseGateways() {
        gateways.values().forEach(gateway -> {
            World world = Bukkit.getWorld(gateway.worldName());
            if (world == null) {
                return;
            }
            scheduler.runRegion(world, gateway.x() >> 4, gateway.z() >> 4, "fracture-gateway-pulse-" + gateway.id(), () -> {
                Location center = new Location(world, gateway.x() + 0.5D, gateway.y() + 1.0D, gateway.z() + 0.5D);
                pulseGateway(center, gateway.role());
            });
        });
    }

    private void pulseGateway(Location center, GatewayRole role) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        if (role == GatewayRole.ENTRY) {
            world.spawnParticle(Particle.PORTAL, center, 28, 0.75D, 0.8D, 0.75D, 0.08D);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(0.0D, 0.3D, 0.0D), 8, 0.4D, 0.2D, 0.4D, 0.01D);
            spawnPylonPulse(world, center, Particle.ELECTRIC_SPARK);
        } else {
            world.spawnParticle(Particle.END_ROD, center.clone().add(0.0D, 0.2D, 0.0D), 12, 0.35D, 0.4D, 0.35D, 0.01D);
            world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0D, 0.6D, 0.0D), 18, 0.8D, 0.8D, 0.8D, 0.4D);
            spawnPylonPulse(world, center, Particle.WAX_ON);
        }
    }

    private void spawnPylonPulse(World world, Location center, Particle particle) {
        double[][] offsets = {
            { 2.0D, 0.0D },
            { -2.0D, 0.0D },
            { 0.0D, 2.0D },
            { 0.0D, -2.0D }
        };
        for (double[] offset : offsets) {
            world.spawnParticle(particle, center.clone().add(offset[0], 1.6D, offset[1]), 4, 0.1D, 0.2D, 0.1D, 0.01D);
        }
    }

    private void buildEntryMonument(World world, int x, int y, int z) {
        fillFoundation(world, x, y, z, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHISELED_POLISHED_BLACKSTONE);
        buildCrestPylons(world, x, y, z, Material.CRYING_OBSIDIAN, Material.GILDED_BLACKSTONE, Material.SOUL_LANTERN);

        setBlock(world, x, y, z, Material.LODESTONE);
        setBlock(world, x + 1, y, z, Material.OBSIDIAN);
        setBlock(world, x - 1, y, z, Material.OBSIDIAN);
        setBlock(world, x, y, z + 1, Material.OBSIDIAN);
        setBlock(world, x, y, z - 1, Material.OBSIDIAN);

        setBlock(world, x + 3, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
        setBlock(world, x - 3, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
        setBlock(world, x, y, z + 3, Material.POLISHED_BLACKSTONE_BRICKS);
        setBlock(world, x, y, z - 3, Material.POLISHED_BLACKSTONE_BRICKS);

        setBlock(world, x, y + 3, z, Material.GILDED_BLACKSTONE);
        setBlock(world, x + 1, y + 3, z, Material.CRYING_OBSIDIAN);
        setBlock(world, x - 1, y + 3, z, Material.CRYING_OBSIDIAN);
        setBlock(world, x, y + 3, z + 1, Material.CRYING_OBSIDIAN);
        setBlock(world, x, y + 3, z - 1, Material.CRYING_OBSIDIAN);
        setBlock(world, x, y + 4, z, Material.END_ROD);
    }

    private void buildReturnMonument(World world, int x, int y, int z) {
        fillFoundation(world, x, y, z, Material.DEEPSLATE_TILES, Material.POLISHED_BASALT);
        buildCrestPylons(world, x, y, z, Material.AMETHYST_BLOCK, Material.PURPUR_PILLAR, Material.SEA_LANTERN);

        setBlock(world, x, y, z, Material.LODESTONE);
        setBlock(world, x + 1, y, z, Material.CALCITE);
        setBlock(world, x - 1, y, z, Material.CALCITE);
        setBlock(world, x, y, z + 1, Material.CALCITE);
        setBlock(world, x, y, z - 1, Material.CALCITE);

        setBlock(world, x + 3, y, z, Material.TUFF_BRICKS);
        setBlock(world, x - 3, y, z, Material.TUFF_BRICKS);
        setBlock(world, x, y, z + 3, Material.TUFF_BRICKS);
        setBlock(world, x, y, z - 3, Material.TUFF_BRICKS);

        setBlock(world, x, y + 3, z, Material.SEA_LANTERN);
        setBlock(world, x + 1, y + 3, z, Material.AMETHYST_BLOCK);
        setBlock(world, x - 1, y + 3, z, Material.AMETHYST_BLOCK);
        setBlock(world, x, y + 3, z + 1, Material.AMETHYST_BLOCK);
        setBlock(world, x, y + 3, z - 1, Material.AMETHYST_BLOCK);
        setBlock(world, x, y + 4, z, Material.END_ROD);
    }

    private void fillFoundation(World world, int x, int y, int z, Material innerFloor, Material outerTrim) {
        for (int dx = -MONUMENT_RADIUS; dx <= MONUMENT_RADIUS; dx++) {
            for (int dz = -MONUMENT_RADIUS; dz <= MONUMENT_RADIUS; dz++) {
                boolean rim = Math.abs(dx) == MONUMENT_RADIUS || Math.abs(dz) == MONUMENT_RADIUS;
                setBlock(world, x + dx, y - 1, z + dz, rim ? outerTrim : innerFloor);
                if (rim) {
                    setBlock(world, x + dx, y, z + dz, outerTrim);
                }
            }
        }
    }

    private void buildCrestPylons(World world, int x, int y, int z, Material pillar, Material crown, Material light) {
        int[][] diagonals = {
            { 2, 2 },
            { 2, -2 },
            { -2, 2 },
            { -2, -2 }
        };
        for (int[] diagonal : diagonals) {
            for (int dy = 0; dy <= 2; dy++) {
                setBlock(world, x + diagonal[0], y + dy, z + diagonal[1], pillar);
            }
            setBlock(world, x + diagonal[0], y + 3, z + diagonal[1], light);
        }

        setBlock(world, x + 2, y + 2, z, crown);
        setBlock(world, x - 2, y + 2, z, crown);
        setBlock(world, x, y + 2, z + 2, crown);
        setBlock(world, x, y + 2, z - 2, crown);
    }

    private void setBlock(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private boolean withinGatewayMonument(Location location, GatewayPoint gateway) {
        int dx = Math.abs(location.getBlockX() - gateway.x());
        int dy = location.getBlockY() - gateway.y();
        int dz = Math.abs(location.getBlockZ() - gateway.z());
        return dx <= MONUMENT_RADIUS && dz <= MONUMENT_RADIUS && dy >= MONUMENT_MIN_Y_OFFSET && dy <= MONUMENT_MAX_Y_OFFSET;
    }

    private int monumentDistance(Location location, GatewayPoint gateway) {
        return Math.abs(location.getBlockX() - gateway.x())
            + Math.abs(location.getBlockY() - gateway.y())
            + Math.abs(location.getBlockZ() - gateway.z());
    }

    private String normalizeId(String rawId) {
        return rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
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

    private String friendlyError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable resolved = error;
        while (resolved.getCause() != null) {
            resolved = resolved.getCause();
        }
        return resolved.getMessage() == null || resolved.getMessage().isBlank()
            ? resolved.getClass().getSimpleName()
            : resolved.getMessage();
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    public record StatusSnapshot(boolean enabled, int totalGateways, int entryGateways, int returnGateways) {

        public String describeLine() {
            if (!enabled) {
                return "Fracture Gateway -> disabled";
            }
            return "Fracture Gateway -> total: "
                + totalGateways
                + " | entry: "
                + entryGateways
                + " | return: "
                + returnGateways;
        }
    }

    public record GatewayPoint(
        String id,
        String displayName,
        GatewayRole role,
        String worldName,
        int x,
        int y,
        int z,
        float yaw,
        float pitch,
        boolean generated
    ) {
    }

    public enum GatewayRole {
        ENTRY,
        RETURN;

        public static GatewayRole parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (GatewayRole value : values()) {
                if (value.name().equalsIgnoreCase(raw)) {
                    return value;
                }
            }
            return null;
        }
    }
}
