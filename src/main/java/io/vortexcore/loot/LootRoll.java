package io.vortexcore.loot;

import java.util.Map;

public record LootRoll(String itemId, int itemLevel, Map<String, Double> rolledStats, double qualityScore, Rarity rarity) {

    public enum Rarity {
        COMMON,
        UNCOMMON,
        RARE,
        EPIC,
        LEGENDARY,
        GOD_TIER
    }
}
