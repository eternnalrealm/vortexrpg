package io.vortexcore.quest;

public record QuestRewardDefinition(
    QuestRewardType type,
    String value,
    double amount
) {
}
