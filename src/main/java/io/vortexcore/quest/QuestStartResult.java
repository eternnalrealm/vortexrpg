package io.vortexcore.quest;

public record QuestStartResult(
    boolean success,
    String message,
    QuestProgress progress
) {

    public static QuestStartResult success(QuestProgress progress) {
        return new QuestStartResult(true, "Quest accepted.", progress);
    }

    public static QuestStartResult failure(String message) {
        return new QuestStartResult(false, message, null);
    }
}
