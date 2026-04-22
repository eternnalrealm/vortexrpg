package io.vortexcore.quest;

import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.nexus.NexusBus;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.quest.message.QuestCompletedMessage;
import io.vortexcore.quest.message.QuestObjectiveProgressedMessage;
import io.vortexcore.quest.message.QuestStartedMessage;
import io.vortexcore.quest.message.QuestAbandonedMessage;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class QuestService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final NexusBus nexusBus;
    private final QuestRegistry questRegistry;
    private final QuestRepository questRepository;
    private final PlayerProfileService playerProfileService;
    private final VaultEconomyBridge vaultEconomyBridge;
    private final MessageService messageService;
    private final int maxActiveQuests;

    public QuestService(
        Logger logger,
        FoliaExecutionFacade scheduler,
        NexusBus nexusBus,
        QuestRegistry questRegistry,
        QuestRepository questRepository,
        PlayerProfileService playerProfileService,
        VaultEconomyBridge vaultEconomyBridge,
        MessageService messageService,
        int maxActiveQuests
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.nexusBus = nexusBus;
        this.questRegistry = questRegistry;
        this.questRepository = questRepository;
        this.playerProfileService = playerProfileService;
        this.vaultEconomyBridge = vaultEconomyBridge;
        this.messageService = messageService;
        this.maxActiveQuests = maxActiveQuests;
    }

    public CompletableFuture<QuestStartResult> startQuest(Player player, String questId) {
        Optional<QuestDefinition> optDef = questRegistry.find(questId);
        if (optDef.isEmpty()) {
            return CompletableFuture.completedFuture(QuestStartResult.failure("Unknown quest: " + questId));
        }
        QuestDefinition definition = optDef.get();

        return questRepository.load(player.getUniqueId()).thenCompose(data -> {
            UnifiedPlayer profile = playerProfileService.profile(player);

            if (definition.hasLevelRequirement() && profile.level() < definition.requiredLevel()) {
                return CompletableFuture.completedFuture(
                    QuestStartResult.failure("You need to be level " + definition.requiredLevel() + " to start this quest.")
                );
            }

            Set<String> completedIds = completedQuestIds(data);
            if (definition.hasPrerequisite() && !completedIds.contains(definition.requiredQuest().toLowerCase(Locale.ROOT))) {
                return CompletableFuture.completedFuture(
                    QuestStartResult.failure("You must complete the prerequisite quest first.")
                );
            }

            if (definition.hasCooldown()) {
                Instant cooldownExpiry = data.cooldowns().get(definition.id());
                if (cooldownExpiry != null && Instant.now().isBefore(cooldownExpiry)) {
                    long secondsLeft = Duration.between(Instant.now(), cooldownExpiry).getSeconds();
                    return CompletableFuture.completedFuture(
                        QuestStartResult.failure("This quest is on cooldown. " + formatDuration(secondsLeft) + " remaining.")
                    );
                }
            }

            QuestProgress existing = data.quests().get(definition.id());
            if (existing != null && existing.state() == QuestState.ACTIVE) {
                return CompletableFuture.completedFuture(
                    QuestStartResult.failure("You already have this quest active.")
                );
            }

            if (!definition.repeatable() && completedIds.contains(definition.id())) {
                return CompletableFuture.completedFuture(
                    QuestStartResult.failure("You have already completed this quest.")
                );
            }

            long activeCount = data.quests().values().stream()
                .filter(q -> q.state() == QuestState.ACTIVE)
                .count();
            if (activeCount >= maxActiveQuests) {
                return CompletableFuture.completedFuture(
                    QuestStartResult.failure("You can only have " + maxActiveQuests + " active quests at a time.")
                );
            }

            QuestProgress progress = QuestProgress.start(definition);
            Map<String, QuestProgress> updatedQuests = new LinkedHashMap<>(data.quests());
            updatedQuests.put(definition.id(), progress);

            PlayerQuestData updated = new PlayerQuestData(
                data.playerId(), data.revision(), updatedQuests, data.cooldowns(), data.completedQuestLog(), data.updatedAt()
            );

            return questRepository.saveAtomically(updated).thenApply(saved -> {
                nexusBus.publish(new QuestStartedMessage(player.getUniqueId(), definition.id()));

                scheduler.runEntity(player, "quest-start-fx-" + player.getUniqueId(), () -> {
                    messageService.send(player,
                        "<gold>\u2726 Quest Accepted:</gold> <yellow>" + definition.name() + "</yellow>"
                    );
                    messageService.send(player, "<gray>" + definition.description() + "</gray>");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                    player.showTitle(Title.title(
                        MINI.deserialize("<gold>New Quest</gold>"),
                        MINI.deserialize("<yellow>" + definition.name() + "</yellow>"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
                    ));
                });

                return QuestStartResult.success(progress);
            });
        });
    }

    public CompletableFuture<Boolean> progressObjective(Player player, String questId, String objectiveId, int amount) {
        return questRepository.load(player.getUniqueId()).thenCompose(data -> {
            QuestProgress progress = data.quests().get(questId.toLowerCase(Locale.ROOT));
            if (progress == null || progress.state() != QuestState.ACTIVE) {
                return CompletableFuture.completedFuture(false);
            }

            Optional<QuestDefinition> optDef = questRegistry.find(questId);
            if (optDef.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            List<QuestObjectiveProgress> updatedObjectives = new ArrayList<>(progress.objectives());
            boolean changed = false;

            for (int i = 0; i < updatedObjectives.size(); i++) {
                QuestObjectiveProgress obj = updatedObjectives.get(i);
                if (obj.objectiveId().equals(objectiveId) && !obj.completed()) {
                    updatedObjectives.set(i, obj.increment(amount));
                    changed = true;
                    break;
                }
            }

            if (!changed) {
                return CompletableFuture.completedFuture(false);
            }

            QuestProgress updatedProgress = progress.withObjectives(List.copyOf(updatedObjectives));
            Map<String, QuestProgress> updatedQuests = new LinkedHashMap<>(data.quests());
            updatedQuests.put(questId.toLowerCase(Locale.ROOT), updatedProgress);

            PlayerQuestData updated = new PlayerQuestData(
                data.playerId(), data.revision(), updatedQuests, data.cooldowns(), data.completedQuestLog(), data.updatedAt()
            );

            return questRepository.saveAtomically(updated).thenApply(saved -> {
                QuestObjectiveProgress progressedObj = updatedObjectives.stream()
                    .filter(o -> o.objectiveId().equals(objectiveId))
                    .findFirst().orElse(null);

                if (progressedObj != null) {
                    nexusBus.publish(new QuestObjectiveProgressedMessage(
                        player.getUniqueId(), questId, objectiveId, progressedObj.current(), progressedObj.target()
                    ));

                    scheduler.runEntity(player, "quest-progress-fx-" + player.getUniqueId(), () -> {
                        QuestDefinition def = optDef.get();
                        QuestObjectiveDefinition objDef = def.objectives().stream()
                            .filter(o -> o.id().equals(objectiveId))
                            .findFirst().orElse(null);
                        String objName = objDef != null ? objDef.description() : objectiveId;
                        player.sendActionBar(MINI.deserialize(
                            "<green>✓</green> <gray>" + objName + "</gray> <white>"
                                + progressedObj.current() + "/" + progressedObj.target() + "</white>"
                        ));
                        if (progressedObj.completed()) {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
                        }
                    });
                }

                if (updatedProgress.allObjectivesComplete()) {
                    completeQuest(player, questId.toLowerCase(Locale.ROOT));
                }

                return true;
            });
        });
    }

    public void progressObjectiveByType(Player player, QuestObjectiveType type, String target, int amount) {
        questRepository.load(player.getUniqueId()).thenAccept(data -> {
            for (Map.Entry<String, QuestProgress> entry : data.quests().entrySet()) {
                QuestProgress progress = entry.getValue();
                if (progress.state() != QuestState.ACTIVE) continue;

                Optional<QuestDefinition> optDef = questRegistry.find(entry.getKey());
                if (optDef.isEmpty()) continue;
                QuestDefinition definition = optDef.get();

                for (QuestObjectiveDefinition objDef : definition.objectives()) {
                    if (objDef.type() == type && objDef.target().equalsIgnoreCase(target)) {
                        QuestObjectiveProgress objProgress = progress.objectives().stream()
                            .filter(o -> o.objectiveId().equals(objDef.id()))
                            .findFirst().orElse(null);
                        if (objProgress != null && !objProgress.completed()) {
                            progressObjective(player, entry.getKey(), objDef.id(), amount);
                        }
                    }
                }
            }
        });
    }

    public CompletableFuture<Boolean> completeQuest(Player player, String questId) {
        return questRepository.load(player.getUniqueId()).thenCompose(data -> {
            QuestProgress progress = data.quests().get(questId);
            if (progress == null || progress.state() != QuestState.ACTIVE) {
                return CompletableFuture.completedFuture(false);
            }

            Optional<QuestDefinition> optDef = questRegistry.find(questId);
            if (optDef.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            QuestDefinition definition = optDef.get();

            QuestProgress completed = progress.complete();
            Map<String, QuestProgress> updatedQuests = new LinkedHashMap<>(data.quests());
            updatedQuests.put(questId, completed);

            Map<String, Instant> updatedCooldowns = new HashMap<>(data.cooldowns());
            if (definition.hasCooldown()) {
                updatedCooldowns.put(questId, Instant.now().plusSeconds(definition.cooldownSeconds()));
            }

            List<String> updatedLog = new ArrayList<>(data.completedQuestLog());
            if (!updatedLog.contains(questId)) {
                updatedLog.add(questId);
            }

            PlayerQuestData updated = new PlayerQuestData(
                data.playerId(), data.revision(), updatedQuests, updatedCooldowns, updatedLog, data.updatedAt()
            );

            return questRepository.saveAtomically(updated).thenApply(saved -> {
                grantRewards(player, definition);
                nexusBus.publish(new QuestCompletedMessage(player.getUniqueId(), definition.id()));

                scheduler.runEntity(player, "quest-complete-fx-" + player.getUniqueId(), () -> {
                    messageService.send(player,
                        "<green>\u2726 Quest Complete!</green> <yellow>" + definition.name() + "</yellow>"
                    );
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
                    player.showTitle(Title.title(
                        MINI.deserialize("<green>Quest Complete!</green>"),
                        MINI.deserialize("<yellow>" + definition.name() + "</yellow>"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(800))
                    ));
                });

                if (definition.hasChainNext()) {
                    scheduler.runAsync("quest-chain-" + player.getUniqueId(), () -> {
                        scheduler.runEntity(player, "quest-chain-start-" + player.getUniqueId(), () -> {
                            messageService.send(player,
                                "<gold>✦</gold> <gray>A new chapter awaits...</gray>"
                            );
                        });
                        startQuest(player, definition.chainNext());
                    });
                }

                return true;
            });
        });
    }

    public CompletableFuture<Boolean> abandonQuest(Player player, String questId) {
        return questRepository.load(player.getUniqueId()).thenCompose(data -> {
            QuestProgress progress = data.quests().get(questId.toLowerCase(Locale.ROOT));
            if (progress == null || progress.state() != QuestState.ACTIVE) {
                return CompletableFuture.completedFuture(false);
            }

            Optional<QuestDefinition> optDef = questRegistry.find(questId);
            String questName = optDef.map(QuestDefinition::name).orElse(questId);

            QuestProgress abandoned = progress.abandon();
            Map<String, QuestProgress> updatedQuests = new LinkedHashMap<>(data.quests());
            updatedQuests.put(questId.toLowerCase(Locale.ROOT), abandoned);

            PlayerQuestData updated = new PlayerQuestData(
                data.playerId(), data.revision(), updatedQuests, data.cooldowns(), data.completedQuestLog(), data.updatedAt()
            );

            return questRepository.saveAtomically(updated).thenApply(saved -> {
                nexusBus.publish(new QuestAbandonedMessage(player.getUniqueId(), questId));

                scheduler.runEntity(player, "quest-abandon-fx-" + player.getUniqueId(), () -> {
                    messageService.send(player,
                        "<red>\u2717 Quest Abandoned:</red> <gray>" + questName + "</gray>"
                    );
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 0.8f);
                });

                return true;
            });
        });
    }

    public CompletableFuture<PlayerQuestData> questData(UUID playerId) {
        return questRepository.load(playerId);
    }

    public QuestRegistry registry() {
        return questRegistry;
    }

    public int maxActiveQuests() {
        return maxActiveQuests;
    }

    private void grantRewards(Player player, QuestDefinition definition) {
        for (QuestRewardDefinition reward : definition.rewards()) {
            switch (reward.type()) {
                case EXPERIENCE -> {
                    long xpAmount = (long) reward.amount();
                    playerProfileService.grantExperience(player, xpAmount, "quest reward");
                    scheduler.runEntity(player, "quest-reward-xp-" + player.getUniqueId(), () ->
                        messageService.send(player,
                            "<aqua>  + " + xpAmount + " XP</aqua>"
                        )
                    );
                }
                case CURRENCY -> {
                    double currencyAmount = reward.amount();
                    scheduler.runGlobal("quest-reward-currency-" + player.getUniqueId(), () -> {
                        if (player.isOnline()) {
                            messageService.send(player,
                                "<gold>  + " + vaultEconomyBridge.formatAmount(currencyAmount) + "</gold>"
                            );
                        }
                    });
                }
                case COMMAND -> {
                    String command = reward.value().replace("{player}", player.getName());
                    scheduler.runGlobal("quest-reward-cmd-" + player.getUniqueId(), () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    );
                }
                default -> logger.info("Unhandled quest reward type: " + reward.type());
            }
        }
    }

    private Set<String> completedQuestIds(PlayerQuestData data) {
        Set<String> ids = new HashSet<>(data.completedQuestLog());
        for (Map.Entry<String, QuestProgress> entry : data.quests().entrySet()) {
            if (entry.getValue().state() == QuestState.COMPLETED) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }
}
