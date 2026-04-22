package io.vortexcore.world;

public enum ExpeditionScoreTier {
    S,
    A,
    B,
    C;

    public static ExpeditionScoreTier resolve(double score, ExpeditionContractTier contractTier) {
        double sThreshold;
        double aThreshold;
        double bThreshold;
        switch (contractTier) {
            case PATROL -> {
                sThreshold = 260.0D;
                aThreshold = 180.0D;
                bThreshold = 120.0D;
            }
            case PURGE -> {
                sThreshold = 420.0D;
                aThreshold = 310.0D;
                bThreshold = 220.0D;
            }
            case CATACLYSM -> {
                sThreshold = 620.0D;
                aThreshold = 470.0D;
                bThreshold = 340.0D;
            }
            case BREACH -> {
                sThreshold = 860.0D;
                aThreshold = 680.0D;
                bThreshold = 500.0D;
            }
            default -> {
                sThreshold = 260.0D;
                aThreshold = 180.0D;
                bThreshold = 120.0D;
            }
        }

        if (score >= sThreshold) {
            return S;
        }
        if (score >= aThreshold) {
            return A;
        }
        if (score >= bThreshold) {
            return B;
        }
        return C;
    }
}
