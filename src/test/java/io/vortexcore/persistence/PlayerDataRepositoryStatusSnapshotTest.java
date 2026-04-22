package io.vortexcore.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerDataRepositoryStatusSnapshotTest {

    @Test
    void describeLinesIncludesModeCountersAndFailureContext() {
        PlayerDataRepository.PersistenceStatusSnapshot snapshot = new PlayerDataRepository.PersistenceStatusSnapshot(
            "FILE",
            24L,
            12L,
            0L,
            0L,
            5L,
            8L,
            7L,
            1L,
            0L,
            1L,
            Instant.parse("2026-03-29T09:00:00Z"),
            Instant.parse("2026-03-29T09:05:00Z"),
            Instant.parse("2026-03-29T09:06:00Z"),
            "File save failed"
        );

        List<String> lines = snapshot.describeLines();

        assertTrue(lines.get(0).contains("FILE"));
        assertTrue(lines.get(1).contains("file reads: 5"));
        assertTrue(lines.get(2).contains("conflicts: 1"));
        assertTrue(lines.get(3).contains("2026-03-29T09:00:00Z"));
        assertTrue(lines.get(4).contains("2026-03-29T09:05:00Z"));
        assertTrue(lines.get(5).contains("File save failed"));
    }
}
