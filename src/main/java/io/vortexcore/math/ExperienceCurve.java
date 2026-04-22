package io.vortexcore.math;

import java.util.Arrays;

public final class ExperienceCurve {

    public static final int MAX_LEVEL = 200;

    private final double a;
    private final double b;
    private final double c;
    // cumulativeXp[i] = total XP required to reach level (i+1), index 0 = level 1 (always 0)
    private final long[] cumulativeXp;

    public ExperienceCurve(double a, double b, double c) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.cumulativeXp = new long[MAX_LEVEL + 1];
        long running = 0L;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            cumulativeXp[level - 1] = running;
            running += computeExperienceForLevel(level);
        }
        cumulativeXp[MAX_LEVEL] = running;
    }

    public static ExperienceCurve cubic(double a, double c) {
        return new ExperienceCurve(a, 3.0D, c);
    }

    public long experienceForLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        return computeExperienceForLevel(level);
    }

    public long totalExperienceToReach(int targetLevel) {
        if (targetLevel <= 1) {
            return 0L;
        }
        int index = Math.min(targetLevel - 1, MAX_LEVEL);
        return cumulativeXp[index];
    }

    public int solveLevel(long totalExperience) {
        if (totalExperience <= 0L) {
            return 1;
        }
        int idx = Arrays.binarySearch(cumulativeXp, totalExperience);
        if (idx >= 0) {
            // Exact match: idx is the 0-based index where cumulativeXp[idx] == totalExperience.
            // That means totalExperience is exactly the threshold to reach level (idx+1).
            return Math.min(idx + 1, MAX_LEVEL);
        }
        // insertion point is -(idx)-1; the level is one before it
        int insertionPoint = -(idx) - 1;
        return Math.max(1, Math.min(insertionPoint, MAX_LEVEL));
    }

    private long computeExperienceForLevel(int level) {
        return (long) Math.ceil(a * Math.pow(level, b) + c);
    }
}
