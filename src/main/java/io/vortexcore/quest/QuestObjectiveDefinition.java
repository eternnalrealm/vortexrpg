package io.vortexcore.quest;

public record QuestObjectiveDefinition(
    String id,
    QuestObjectiveType type,
    String target,
    int amount,
    String description
) {
}
