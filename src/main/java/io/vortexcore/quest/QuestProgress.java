package io.vortexcore.quest;

import java.time.Instant;
import java.util.List;

public record QuestProgress(
    String questId,
    QuestState state,
    List<QuestObjectiveProgress> objectives,
    Instant startedAt,
    Instant completedAt
) {

    public static QuestProgress start(QuestDefinition definition) {
        List<QuestObjectiveProgress> objectives = definition.objectives().stream()
            .map(QuestObjectiveProgress::start)
            .toList();
        return new QuestProgress(definition.id(), QuestState.ACTIVE, objectives, Instant.now(), null);
    }

    public boolean allObjectivesComplete() {
        return objectives.stream().allMatch(QuestObjectiveProgress::completed);
    }

    public QuestProgress withObjectives(List<QuestObjectiveProgress> updatedObjectives) {
        return new QuestProgress(questId, state, updatedObjectives, startedAt, completedAt);
    }

    public QuestProgress complete() {
        return new QuestProgress(questId, QuestState.COMPLETED, objectives, startedAt, Instant.now());
    }

    public QuestProgress abandon() {
        return new QuestProgress(questId, QuestState.ABANDONED, objectives, startedAt, Instant.now());
    }
}
