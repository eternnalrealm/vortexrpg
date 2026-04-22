package io.vortexcore.talent;

import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.nexus.message.PlayerLevelUpMessage;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.ClassSkillService;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.VortexClass;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.talent.message.TalentNodeAllocatedMessage;
import io.vortexcore.talent.message.TalentTreeResetMessage;
import io.vortexcore.ui.MessageService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class TalentService implements AutoCloseable {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final NexusBus nexusBus;
    private final TalentRegistry registry;
    private final TalentRepository repository;
    private final PlayerProfileService playerProfileService;
    private final ClassSkillService classSkillService;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final MessageService messageService;
    private final int talentPointsPerLevels;
    private final double resetCost;
    private final AutoCloseable levelUpSubscription;

    public TalentService(
        Logger logger,
        FoliaExecutionFacade scheduler,
        NexusBus nexusBus,
        TalentRegistry registry,
        TalentRepository repository,
        PlayerProfileService playerProfileService,
        ClassSkillService classSkillService,
        VaultEconomyBridge vaultEconomyBridge,
        MessageService messageService,
        int talentPointsPerLevels,
        double resetCost
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.nexusBus = nexusBus;
        this.registry = registry;
        this.repository = repository;
        this.playerProfileService = playerProfileService;
        this.classSkillService = classSkillService;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.messageService = messageService;
        this.talentPointsPerLevels = Math.max(1, talentPointsPerLevels);
        this.resetCost = resetCost;

        this.levelUpSubscription = nexusBus.subscribe(PlayerLevelUpMessage.class, msg -> {
            if (msg.newLevel() % this.talentPointsPerLevels == 0) {
                Player player = Bukkit.getPlayer(msg.playerId());
                if (player != null && player.isOnline()) {
                    grantTalentPoints(player, 1);
                }
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletableFuture<TalentAllocateResult> allocateNode(Player player, String nodeId) {
        return repository.load(player.getUniqueId()).thenCompose(data -> {
            UnifiedPlayer profile = playerProfileService.profile(player);
            Optional<VortexClass> selectedOpt = classSkillService.selectedClass(profile);
            if (selectedOpt.isEmpty()) {
                return CompletableFuture.completedFuture(TalentAllocateResult.failure("Choose a class first."));
            }
            VortexClass vortexClass = selectedOpt.get();
            String classId = vortexClass.commandId();

            Optional<TalentTreeDefinition> treeOpt = registry.find(vortexClass);
            if (treeOpt.isEmpty()) {
                return CompletableFuture.completedFuture(TalentAllocateResult.failure("No talent tree found for your class."));
            }
            TalentTreeDefinition tree = treeOpt.get();

            Optional<TalentNodeDefinition> nodeOpt = tree.find(nodeId);
            if (nodeOpt.isEmpty()) {
                return CompletableFuture.completedFuture(TalentAllocateResult.failure("Unknown talent node: " + nodeId));
            }
            TalentNodeDefinition node = nodeOpt.get();

            Set<String> allocated = data.allocatedNodes().getOrDefault(classId, Set.of());
            if (allocated.contains(nodeId)) {
                return CompletableFuture.completedFuture(TalentAllocateResult.failure("Already unlocked."));
            }

            for (String prereqId : node.prerequisites()) {
                if (!allocated.contains(prereqId)) {
                    return CompletableFuture.completedFuture(TalentAllocateResult.failure("Prerequisite not met: " + prereqId));
                }
            }

            if (data.talentPoints() < node.cost()) {
                return CompletableFuture.completedFuture(TalentAllocateResult.failure(
                    "Not enough talent points (need " + node.cost() + ", have " + data.talentPoints() + ")."
                ));
            }

            Map<String, Set<String>> updatedNodes = new LinkedHashMap<>(data.allocatedNodes());
            Set<String> updatedSet = new LinkedHashSet<>(allocated);
            updatedSet.add(nodeId);
            updatedNodes.put(classId, Set.copyOf(updatedSet));

            PlayerTalentData updated = new PlayerTalentData(
                data.playerId(),
                data.revision(),
                Map.copyOf(updatedNodes),
                data.talentPoints() - node.cost(),
                data.updatedAt()
            );

            return repository.saveAtomically(updated).thenCompose(saved -> {
                node.statBonuses().forEach((attr, bonus) -> {
                    double current = profile.stat(attr).snapshot().baseValue();
                    profile.stat(attr).setBase(current + bonus);
                });

                CompletableFuture<Void> uiFuture = new CompletableFuture<>();
                scheduler.runEntity(player, "talent-allocate-" + player.getUniqueId(), () -> {
                    classSkillService.applyLiveAttributes(player, profile);
                    messageService.send(player,
                        "<green>Unlocked <bold>" + node.displayName() + "</bold>!</green>"
                    );
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                    uiFuture.complete(null);
                });

                nexusBus.publish(new TalentNodeAllocatedMessage(player.getUniqueId(), nodeId, classId));
                return uiFuture.thenApply(ignored -> TalentAllocateResult.success("Unlocked " + node.displayName() + "."));
            });
        });
    }

    public CompletableFuture<Boolean> resetTree(Player player) {
        return repository.load(player.getUniqueId()).thenCompose(data -> {
            UnifiedPlayer profile = playerProfileService.profile(player);
            Optional<VortexClass> selectedOpt = classSkillService.selectedClass(profile);
            if (selectedOpt.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            VortexClass vortexClass = selectedOpt.get();
            String classId = vortexClass.commandId();

            Set<String> allocated = data.allocatedNodes().getOrDefault(classId, Set.of());
            if (allocated.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            Optional<TalentTreeDefinition> treeOpt = registry.find(vortexClass);
            if (treeOpt.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            TalentTreeDefinition tree = treeOpt.get();

            CompletableFuture<Boolean> costFuture = new CompletableFuture<>();
            if (resetCost > 0.0 && vaultEconomyBridge.isAvailable()) {
                scheduler.runGlobal("talent-reset-cost-" + player.getUniqueId(), () -> {
                    double balance = vaultEconomyBridge.balance(player);
                    if (balance < resetCost) {
                        scheduler.runEntity(player, "talent-reset-insufficient-" + player.getUniqueId(), () ->
                            messageService.send(player,
                                "<red>You need " + String.format("%.0f", resetCost) + " coins to reset your talent tree (have " + String.format("%.0f", balance) + ").</red>"
                            )
                        );
                        costFuture.complete(false);
                        return;
                    }
                    net.milkbowl.vault.economy.EconomyResponse response =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class)
                            .getProvider().withdrawPlayer(player, resetCost);
                    costFuture.complete(response.transactionSuccess());
                });
            } else {
                costFuture.complete(true);
            }

            return costFuture.thenCompose(paid -> {
                if (!paid) {
                    return CompletableFuture.completedFuture(false);
                }

                int refundedPoints = 0;
                for (String nodeId : allocated) {
                    TalentNodeDefinition node = tree.nodes().get(nodeId);
                    if (node != null) {
                        refundedPoints += node.cost();
                        node.statBonuses().forEach((attr, bonus) -> {
                            double current = profile.stat(attr).snapshot().baseValue();
                            profile.stat(attr).setBase(current - bonus);
                        });
                    }
                }

                Map<String, Set<String>> updatedNodes = new LinkedHashMap<>(data.allocatedNodes());
                updatedNodes.remove(classId);

                PlayerTalentData updated = new PlayerTalentData(
                    data.playerId(),
                    data.revision(),
                    Map.copyOf(updatedNodes),
                    data.talentPoints() + refundedPoints,
                    data.updatedAt()
                );

                final int totalRefunded = refundedPoints;
                return repository.saveAtomically(updated).thenCompose(saved -> {
                    CompletableFuture<Boolean> done = new CompletableFuture<>();
                    scheduler.runEntity(player, "talent-reset-apply-" + player.getUniqueId(), () -> {
                        classSkillService.applyLiveAttributes(player, profile);
                        messageService.send(player,
                            "<gold>Talent tree reset! Refunded <bold>" + totalRefunded + "</bold> talent point(s).</gold>"
                        );
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 0.8f);
                        done.complete(true);
                    });
                    nexusBus.publish(new TalentTreeResetMessage(player.getUniqueId(), classId));
                    return done;
                });
            });
        });
    }

    public CompletableFuture<PlayerTalentData> talentData(UUID playerId) {
        return repository.load(playerId);
    }

    public void grantTalentPoints(Player player, int amount) {
        repository.load(player.getUniqueId()).thenCompose(data -> {
            PlayerTalentData updated = new PlayerTalentData(
                data.playerId(),
                data.revision(),
                data.allocatedNodes(),
                data.talentPoints() + amount,
                data.updatedAt()
            );
            return repository.saveAtomically(updated);
        }).thenAccept(saved ->
            scheduler.runEntity(player, "talent-point-grant-" + player.getUniqueId(), () ->
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gold>+<bold>" + amount + "</bold> Talent Point(s) available!</gold>"
                ))
            )
        ).exceptionally(error -> {
            logger.warning("Failed to grant talent points to " + player.getName() + ": " + error.getMessage());
            return null;
        });
    }

    public int talentPointsAvailable(PlayerTalentData data) {
        return data.talentPoints();
    }

    public TalentRegistry registry() {
        return registry;
    }

    public double resetCost() {
        return resetCost;
    }

    @Override
    public void close() {
        try {
            levelUpSubscription.close();
        } catch (Exception exception) {
            logger.warning("Failed to close talent level-up subscription: " + exception.getMessage());
        }
    }
}
