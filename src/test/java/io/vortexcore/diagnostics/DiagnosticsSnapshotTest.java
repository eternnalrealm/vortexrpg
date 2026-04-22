package io.vortexcore.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticsSnapshotTest {

    @Test
    void describeLinesHighlightsHottestRegionsFirst() {
        DiagnosticsTracker.ServerHealthSnapshot snapshot = new DiagnosticsTracker.ServerHealthSnapshot(
            12.5D,
            28.0D,
            512L * 1024L * 1024L,
            1024L * 1024L * 1024L,
            18L,
            450,
            List.of(
                new DiagnosticsTracker.RegionSnapshot(
                    "world",
                    0,
                    0,
                    120,
                    3,
                    new DiagnosticsTracker.RegionTpsSnapshot(20.0D, 20.0D, 18.4D, 19.0D, 19.5D),
                    1L
                ),
                new DiagnosticsTracker.RegionSnapshot(
                    "world",
                    10,
                    2,
                    240,
                    1,
                    new DiagnosticsTracker.RegionTpsSnapshot(20.0D, 20.0D, 9.5D, 12.0D, 15.0D),
                    2L
                )
            )
        );

        List<String> lines = snapshot.describeLines(2);

        assertTrue(lines.get(0).contains("Tick avg/max"));
        assertTrue(lines.get(1).contains("Memory:"));
        assertTrue(lines.get(2).contains("world [0,0]"));
        assertTrue(lines.get(3).contains("world [10,2]"));
    }
}
