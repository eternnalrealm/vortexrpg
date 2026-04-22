package io.vortexcore.command;

import io.vortexcore.integration.protection.ProtectionAction;
import io.vortexcore.integration.protection.ProtectionCheckResult;
import io.vortexcore.integration.protection.ProtectionHookService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.world.FractureGatewayService;
import io.vortexcore.world.FractureNavigatorService;
import io.vortexcore.world.FractureProfileService;
import io.vortexcore.world.FractureRegionSnapshot;
import io.vortexcore.world.ThreatSource;
import io.vortexcore.world.WorldThreatManager;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class FractureCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_STATUS = "vortex.admin.fracture.status";
    private static final String PERM_MAP = "vortex.player.fracture.map";
    private static final String PERM_SEED = "vortex.admin.fracture.seed";
    private static final String PERM_SUPPRESS = "vortex.admin.fracture.suppress";
    private static final String PERM_BREACH = "vortex.admin.fracture.breach";
    private static final String PERM_TP = "vortex.admin.fracture.tp";
    private static final String PERM_GATEWAY = "vortex.player.fracture.gateway";
    private static final String PERM_GATEWAY_ADMIN = "vortex.admin.fracture.gateway";

    private final FoliaExecutionFacade scheduler;
    private final WorldThreatManager worldThreatManager;
    private final ProtectionHookService protectionHookService;
    private final FractureNavigatorService fractureNavigatorService;
    private final FractureProfileService fractureProfileService;
    private final FractureGatewayService fractureGatewayService;
    private final MessageService messageService;

    public FractureCommand(
        FoliaExecutionFacade scheduler,
        WorldThreatManager worldThreatManager,
        ProtectionHookService protectionHookService,
        FractureNavigatorService fractureNavigatorService,
        FractureProfileService fractureProfileService,
        FractureGatewayService fractureGatewayService,
        MessageService messageService
    ) {
        this.scheduler = scheduler;
        this.worldThreatManager = worldThreatManager;
        this.protectionHookService = protectionHookService;
        this.fractureNavigatorService = fractureNavigatorService;
        this.fractureProfileService = fractureProfileService;
        this.fractureGatewayService = fractureGatewayService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can manage fractures from in game.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (deny(player, PERM_STATUS)) {
                return true;
            }
            worldThreatManager.fractureAt(player.getLocation()).ifPresentOrElse(
                snapshot -> messageService.send(player, describe(snapshot, fractureNavigatorService.nameFor(snapshot), player.getWorld().getName())),
                () -> messageService.send(player, "No active fracture exists at your location.")
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (deny(player, PERM_MAP)) {
                return true;
            }
            List<FractureNavigatorService.NamedFracture> fractures = fractureNavigatorService.activeNamedFractures();
            if (fractures.isEmpty()) {
                messageService.send(player, "No active fractures exist right now.");
                return true;
            }
            messageService.send(player, "Active fractures across loaded worlds:");
            fractures.stream().limit(16).forEach(fracture -> messageService.send(player,
                "- " + fracture.name()
                    + " | " + fracture.snapshot().state()
                    + " | profile=" + fracture.snapshot().profile().id()
                    + " | world=" + worldName(fracture)
                    + " | chunk=" + fracture.snapshot().key().chunkX() + "," + fracture.snapshot().key().chunkZ()
                    + " | score=" + String.format(Locale.US, "%.1f", fracture.snapshot().threatScore())
            ));
            return true;
        }

        if (args[0].equalsIgnoreCase("profiles")) {
            if (deny(player, PERM_SEED)) {
                return true;
            }
            messageService.send(player, "Available fracture profiles:");
            fractureProfileService.describeLines().forEach(line -> messageService.send(player, "- " + line));
            return true;
        }

        if (args[0].equalsIgnoreCase("map")) {
            if (deny(player, PERM_MAP)) {
                return true;
            }

            FractureNavigatorService.NamedFracture fracture = args.length >= 2
                ? fractureNavigatorService.findByName(args[1]).orElse(null)
                : fractureNavigatorService.nearestFor(player).orElse(null);
            if (fracture == null) {
                messageService.send(player, args.length >= 2
                    ? "No active fracture matches that name."
                    : "No active fracture is available in your current world.");
                return true;
            }

            fractureNavigatorService.createMap(player, fracture)
                .thenAccept(mapItem -> scheduler.runEntity(player, "fracture-map-give-" + player.getUniqueId(), () -> {
                    java.util.Map<Integer, org.bukkit.inventory.ItemStack> overflow = player.getInventory().addItem(mapItem);
                    overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    reply(player, "Issued fracture map for " + fracture.name() + ".");
                }))
                .exceptionally(error -> {
                    reply(player, "Unable to create fracture map: " + friendlyError(error));
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("gateway")) {
            if (args.length == 1 || args[1].equalsIgnoreCase("open")) {
                if (deny(player, PERM_GATEWAY)) {
                    return true;
                }
                fractureGatewayService.openGatewayMenu(player);
                return true;
            }

            if (args[1].equalsIgnoreCase("return")) {
                if (deny(player, PERM_GATEWAY)) {
                    return true;
                }
                fractureGatewayService.returnPlayer(player)
                    .exceptionally(error -> {
                        reply(player, "Unable to return through the gateway: " + friendlyError(error));
                        return null;
                    });
                return true;
            }

            if (args[1].equalsIgnoreCase("list")) {
                if (deny(player, PERM_GATEWAY_ADMIN)) {
                    return true;
                }
                fractureGatewayService.gatewayLines().forEach(line -> messageService.send(player, line));
                return true;
            }

            if (args[1].equalsIgnoreCase("create")) {
                if (deny(player, PERM_GATEWAY_ADMIN)) {
                    return true;
                }
                if (args.length < 3) {
                    messageService.send(player, "Usage: /" + label + " gateway create <entry|return> [id]");
                    return true;
                }
                FractureGatewayService.GatewayRole role = FractureGatewayService.GatewayRole.parse(args[2]);
                if (role == null) {
                    messageService.send(player, "Choose entry or return.");
                    return true;
                }
                String gatewayId = args.length >= 4
                    ? args[3]
                    : role == FractureGatewayService.GatewayRole.ENTRY ? "manual_entry" : "manual_return";
                fractureGatewayService.createGateway(gatewayId, role, player.getLocation(), false)
                    .thenAccept(gateway -> reply(player, "Created gateway " + gateway.id() + " in " + gateway.worldName() + "."))
                    .exceptionally(error -> {
                        reply(player, "Unable to create gateway: " + friendlyError(error));
                        return null;
                    });
                return true;
            }

            if (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove")) {
                if (deny(player, PERM_GATEWAY_ADMIN)) {
                    return true;
                }
                if (args.length < 3) {
                    messageService.send(player, "\u00a7cSpecify the gateway to remove. \u00a77Use \u00a7f/" + label + " gateway list \u00a77to see all ids.");
                    return true;
                }
                fractureGatewayService.deleteGateway(args[2])
                    .thenAccept(removed -> reply(player, "\u00a7aGateway \u00a7f" + removed.id() + " \u00a7a(" + removed.role().name().toLowerCase(Locale.ROOT) + ") removed from \u00a7f" + removed.worldName() + "\u00a7a."))
                    .exceptionally(error -> {
                        reply(player, "\u00a7cUnable to delete gateway: " + friendlyError(error));
                        return null;
                    });
                return true;
            }

            if (args[1].equalsIgnoreCase("generate")) {
                if (deny(player, PERM_GATEWAY_ADMIN)) {
                    return true;
                }
                if (args.length < 3) {
                    messageService.send(player, "\u00a7cSpecify how many gateway pairs to generate. \u00a77Usage: \u00a7f/" + label + " gateway generate <count>");
                    return true;
                }
                int count;
                try {
                    count = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    messageService.send(player, "\u00a7c'" + args[2] + "' is not a valid number.");
                    return true;
                }
                if (count < 1 || count > 50) {
                    messageService.send(player, "\u00a7cCount must be between \u00a7f1 \u00a7cand \u00a7f50\u00a7c.");
                    return true;
                }
                messageService.send(player, "\u00a7eGenerating \u00a7f" + count + " \u00a7egateway pair(s) across both worlds...");
                fractureGatewayService.generateGateways(count)
                    .thenAccept(created -> reply(player, "\u00a7a\u00a7lGeneration complete \u00a78\u2014 \u00a7f" + created.size() + " \u00a7agateway(s) placed (\u00a7f" + count + " \u00a7aentry + \u00a7f" + count + " \u00a7areturn)."))
                    .exceptionally(error -> {
                        reply(player, "\u00a7cGeneration failed: " + friendlyError(error));
                        return null;
                    });
                return true;
            }

            messageService.send(player, "Usage: /" + label + " gateway [open|return|list|create|delete|generate]");
            return true;
        }

        if (args[0].equalsIgnoreCase("seed")) {
            if (deny(player, PERM_SEED)) {
                return true;
            }
            if (denyProtected(player)) {
                return true;
            }
            ThreatSource source = parseSource(args.length >= 2 ? args[1] : "admin_forced");
            double intensity = parseDouble(args.length >= 3 ? args[2] : "35.0", 35.0D);
            String profileId = args.length >= 4 ? args[3] : null;
            String fractureName = args.length >= 5 ? args[4] : null;
            worldThreatManager.seedFracture(player.getLocation(), source, intensity, profileId, fractureName)
                .thenAccept(snapshot -> reply(player, "Seeded fracture: " + describe(snapshot, fractureNavigatorService.nameFor(snapshot), player.getWorld().getName())))
                .exceptionally(error -> {
                    reply(player, "Unable to seed fracture: " + friendlyError(error));
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("suppress")) {
            if (deny(player, PERM_SUPPRESS)) {
                return true;
            }
            if (denyProtected(player)) {
                return true;
            }
            double effort = parseDouble(args.length >= 2 ? args[1] : "20.0", 20.0D);
            worldThreatManager.contributeSuppression(player.getLocation(), effort)
                .thenAccept(snapshot -> reply(player, "Suppression applied: " + describe(snapshot, fractureNavigatorService.nameFor(snapshot), player.getWorld().getName())))
                .exceptionally(error -> {
                    reply(player, "Unable to suppress fracture: " + friendlyError(error));
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("breach")) {
            if (deny(player, PERM_BREACH)) {
                return true;
            }
            if (denyProtected(player)) {
                return true;
            }
            worldThreatManager.openBossBreach(player.getLocation(), UUID.randomUUID(), List.of(player))
                .thenAccept(instance -> reply(player, "Boss breach opened in instance world " + instance.partyInstance().worldName()))
                .exceptionally(error -> {
                    reply(player, "Unable to open boss breach: " + friendlyError(error));
                    return null;
                });
            return true;
        }

        if (args[0].equalsIgnoreCase("tp")) {
            if (deny(player, PERM_TP)) {
                return true;
            }
            if (args.length < 2) {
                messageService.send(player, "Usage: /" + label + " tp <fracture-name>");
                return true;
            }
            FractureNavigatorService.NamedFracture fracture = fractureNavigatorService.findByName(args[1]).orElse(null);
            if (fracture == null) {
                messageService.send(player, "No active fracture matches that name.");
                return true;
            }
            fractureNavigatorService.teleportAnchor(fracture)
                .thenAccept(location -> scheduler.runEntity(player, "fracture-admin-teleport-" + player.getUniqueId(), () ->
                    player.teleportAsync(location).thenAccept(success -> {
                        if (success) {
                            reply(player, "Teleported to " + fracture.name() + ".");
                        } else {
                            reply(player, "Teleport failed for " + fracture.name() + ".");
                        }
                    })
                ))
                .exceptionally(error -> {
                    reply(player, "Unable to teleport to fracture: " + friendlyError(error));
                    return null;
                });
            return true;
        }

        sendFractureHelp(player, label);
        return true;
    }

    private void sendFractureHelp(Player player, String label) {
        messageService.send(player, "");
        messageService.send(player, "\u00a7d\u2550\u2550\u2550 \u00a75\u00a7lFracture Control \u00a7d\u00b7 \u00a77Rift Management \u00a7d\u2550\u2550\u2550");
        messageService.send(player, "");
        if (player.hasPermission(PERM_STATUS) || player.hasPermission(PERM_MAP)) {
            messageService.send(player, "\u00a7d\u25b8 \u00a75Reconnaissance");
            if (player.hasPermission(PERM_STATUS)) {
                messageService.send(player, "  \u00a77/" + label + " status \u00a78\u2014 \u00a7fInspect the fracture at your location");
            }
            if (player.hasPermission(PERM_MAP)) {
                messageService.send(player, "  \u00a77/" + label + " list \u00a78\u2014 \u00a7fList all active fractures across worlds");
                messageService.send(player, "  \u00a77/" + label + " map [name] \u00a78\u2014 \u00a7fForge a live tracking map");
            }
        }
        if (player.hasPermission(PERM_GATEWAY)) {
            messageService.send(player, "\u00a7d\u25b8 \u00a75Gateway Network");
            messageService.send(player, "  \u00a77/" + label + " gateway open \u00a78\u2014 \u00a7fOpen the gateway travel menu");
            messageService.send(player, "  \u00a77/" + label + " gateway return \u00a78\u2014 \u00a7fReturn to your last safe anchor");
            if (player.hasPermission(PERM_GATEWAY_ADMIN)) {
                messageService.send(player, "  \u00a77/" + label + " gateway list \u00a78\u2014 \u00a7fList all registered gateways");
                messageService.send(player, "  \u00a77/" + label + " gateway create <entry|return> [id] \u00a78\u2014 \u00a7fPlace a new gateway");
                messageService.send(player, "  \u00a77/" + label + " gateway delete <id> \u00a78\u2014 \u00a7fPermanently remove a gateway");
                messageService.send(player, "  \u00a77/" + label + " gateway generate <count> \u00a78\u2014 \u00a7fAuto-place paired gateways in both worlds");
            }
        }
        if (player.hasPermission(PERM_SEED) || player.hasPermission(PERM_SUPPRESS) || player.hasPermission(PERM_BREACH)) {
            messageService.send(player, "\u00a7d\u25b8 \u00a75Administration");
            if (player.hasPermission(PERM_SEED)) {
                messageService.send(player, "  \u00a77/" + label + " profiles \u00a78\u2014 \u00a7fView available fracture profiles");
                messageService.send(player, "  \u00a77/" + label + " seed <source> <intensity> [profile] [name] \u00a78\u2014 \u00a7fForce-seed a fracture");
            }
            if (player.hasPermission(PERM_SUPPRESS)) {
                messageService.send(player, "  \u00a77/" + label + " suppress [effort] \u00a78\u2014 \u00a7fApply suppression energy");
            }
            if (player.hasPermission(PERM_BREACH)) {
                messageService.send(player, "  \u00a77/" + label + " breach \u00a78\u2014 \u00a7fForce-open a boss breach instance");
            }
            if (player.hasPermission(PERM_TP)) {
                messageService.send(player, "  \u00a77/" + label + " tp <name> \u00a78\u2014 \u00a7fTeleport to a named fracture");
            }
        }
        messageService.send(player, "");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            java.util.ArrayList<String> suggestions = new java.util.ArrayList<>();
            if (sender.hasPermission(PERM_STATUS)) {
                suggestions.add("status");
            }
            if (sender.hasPermission(PERM_MAP)) {
                suggestions.add("map");
                suggestions.add("list");
            }
            if (sender.hasPermission(PERM_GATEWAY)) {
                suggestions.add("gateway");
            }
            if (sender.hasPermission(PERM_SEED)) {
                suggestions.add("seed");
                suggestions.add("profiles");
            }
            if (sender.hasPermission(PERM_SUPPRESS)) {
                suggestions.add("suppress");
            }
            if (sender.hasPermission(PERM_BREACH)) {
                suggestions.add("breach");
            }
            if (sender.hasPermission(PERM_TP)) {
                suggestions.add("tp");
            }
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("seed") && sender.hasPermission(PERM_SEED)) {
            return Arrays.stream(ThreatSource.values()).map(Enum::name).map(name -> name.toLowerCase(Locale.ROOT)).toList();
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("seed") && sender.hasPermission(PERM_SEED)) {
            return fractureProfileService.profileIds();
        }

        if (args.length == 2
            && (args[0].equalsIgnoreCase("map") || args[0].equalsIgnoreCase("tp"))
            && (sender.hasPermission(PERM_MAP) || sender.hasPermission(PERM_TP))) {
            return fractureNavigatorService.activeNamedFractures().stream().map(FractureNavigatorService.NamedFracture::name).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("gateway") && sender.hasPermission(PERM_GATEWAY)) {
            ArrayList<String> suggestions = new ArrayList<>();
            suggestions.add("open");
            suggestions.add("return");
            if (sender.hasPermission(PERM_GATEWAY_ADMIN)) {
                suggestions.add("list");
                suggestions.add("create");
                suggestions.add("delete");
                suggestions.add("generate");
            }
            return suggestions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("gateway") && args[1].equalsIgnoreCase("create") && sender.hasPermission(PERM_GATEWAY_ADMIN)) {
            return List.of("entry", "return");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("gateway") && args[1].equalsIgnoreCase("generate") && sender.hasPermission(PERM_GATEWAY_ADMIN)) {
            return List.of("1", "2", "3", "5", "10");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("gateway")
            && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove"))
            && sender.hasPermission(PERM_GATEWAY_ADMIN)) {
            return fractureGatewayService.gatewayIds();
        }

        return List.of();
    }

    private boolean deny(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        messageService.send(player, "\u00a7c\u2716 Access denied \u00a78\u2014 \u00a77requires \u00a7f" + permission);
        return true;
    }

    private ThreatSource parseSource(String input) {
        return Arrays.stream(ThreatSource.values())
            .filter(source -> source.name().equalsIgnoreCase(input))
            .findFirst()
            .orElse(ThreatSource.ADMIN_FORCED);
    }

    private double parseDouble(String input, double fallback) {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String describe(FractureRegionSnapshot snapshot, String fractureName, String worldName) {
        return fractureName
            + " | state=" + snapshot.state()
            + " | profile=" + snapshot.profile().id()
            + " | score=" + String.format(Locale.US, "%.1f", snapshot.threatScore())
            + " | modifiers=" + snapshot.modifiers()
            + " | world=" + worldName
            + " | chunk=" + snapshot.key().chunkX() + "," + snapshot.key().chunkZ();
    }

    private String friendlyError(Throwable error) {
        Throwable resolved = error;
        while (resolved.getCause() != null) {
            resolved = resolved.getCause();
        }
        return resolved.getMessage() == null || resolved.getMessage().isBlank()
            ? resolved.getClass().getSimpleName()
            : resolved.getMessage();
    }

    private boolean denyProtected(Player player) {
        ProtectionCheckResult result = protectionHookService.check(player, player.getLocation(), ProtectionAction.FRACTURE_ADMIN);
        if (result.allowed()) {
            return false;
        }
        reply(player, ProtectionHookService.denyMessage(result, ProtectionAction.FRACTURE_ADMIN));
        return true;
    }

    private void reply(Player player, String message) {
        scheduler.runEntity(player, "fracture-command-reply-" + player.getUniqueId(), () -> messageService.send(player, message));
    }

    private String worldName(FractureNavigatorService.NamedFracture fracture) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(fracture.snapshot().key().worldId());
        return world == null ? fracture.snapshot().key().worldId().toString() : world.getName();
    }
}
