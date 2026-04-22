package io.vortexcore.world;

public enum FractureState {
    UNSTABLE(1, false),
    CORRUPTED(2, false),
    OVERRUN(3, false),
    BOSS_BREACH(4, true),
    PURIFIED(0, false);

    private final int severity;
    private final boolean bossPhase;

    FractureState(int severity, boolean bossPhase) {
        this.severity = severity;
        this.bossPhase = bossPhase;
    }

    public int severity() {
        return severity;
    }

    public boolean bossPhase() {
        return bossPhase;
    }
}
