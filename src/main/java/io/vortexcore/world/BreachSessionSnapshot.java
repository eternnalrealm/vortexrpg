package io.vortexcore.world;

import java.util.UUID;

public record BreachSessionSnapshot(
    UUID sessionId,
    String worldName,
    String bossName,
    int participantCount,
    long elapsedSeconds,
    long timeLimitSeconds,
    int phase,
    String activeAbility,
    int remainingRevives,
    int totalRevives,
    int activeFighters,
    int eliminatedPlayers,
    long reconnectGraceSeconds
) {
}
