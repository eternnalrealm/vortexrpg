package io.vortexcore.quest;

import java.util.List;

public record QuestDefinition(
    String id,
    String name,
    String description,
    int requiredLevel,
    String requiredQuest,
    String npcId,
    long cooldownSeconds,
    boolean repeatable,
    List<QuestObjectiveDefinition> objectives,
    List<QuestRewardDefinition> rewards,
    String chainNext
) {

    public boolean hasLevelRequirement() {
        return requiredLevel > 0;
    }

    public boolean hasPrerequisite() {
        return requiredQuest != null && !requiredQuest.isBlank();
    }

    public boolean hasCooldown() {
        return cooldownSeconds > 0;
    }

    public boolean hasChainNext() {
        return chainNext != null && !chainNext.isBlank();
    }
}
