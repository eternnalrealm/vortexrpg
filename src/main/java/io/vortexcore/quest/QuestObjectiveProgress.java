package io.vortexcore.quest;

public record QuestObjectiveProgress(
    String objectiveId,
    int current,
    int target,
    boolean completed
) {

    public static QuestObjectiveProgress start(QuestObjectiveDefinition definition) {
        return new QuestObjectiveProgress(definition.id(), 0, definition.amount(), false);
    }

    public QuestObjectiveProgress increment(int amount) {
        int newCurrent = Math.min(current + amount, target);
        return new QuestObjectiveProgress(objectiveId, newCurrent, target, newCurrent >= target);
    }
}
