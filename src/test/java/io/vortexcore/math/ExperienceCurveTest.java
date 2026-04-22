package io.vortexcore.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExperienceCurveTest {

    @Test
    void cubicCurveCalculatesPerLevelExperience() {
        ExperienceCurve curve = ExperienceCurve.cubic(45.0D, 120.0D);

        assertEquals(0L, curve.experienceForLevel(0));
        assertEquals(165L, curve.experienceForLevel(1));
        assertEquals(480L, curve.experienceForLevel(2));
        assertEquals(1335L, curve.experienceForLevel(3));
    }

    @Test
    void curveCalculatesTotalExperienceThresholds() {
        ExperienceCurve curve = ExperienceCurve.cubic(45.0D, 120.0D);

        assertEquals(0L, curve.totalExperienceToReach(1));
        assertEquals(165L, curve.totalExperienceToReach(2));
        assertEquals(645L, curve.totalExperienceToReach(3));
        assertEquals(1980L, curve.totalExperienceToReach(4));
    }

    @Test
    void solveLevelResolvesThresholdBoundaries() {
        ExperienceCurve curve = ExperienceCurve.cubic(45.0D, 120.0D);

        assertEquals(1, curve.solveLevel(0L));
        assertEquals(1, curve.solveLevel(164L));
        assertEquals(2, curve.solveLevel(165L));
        assertEquals(3, curve.solveLevel(1979L));
        assertEquals(4, curve.solveLevel(1980L));
    }
}
