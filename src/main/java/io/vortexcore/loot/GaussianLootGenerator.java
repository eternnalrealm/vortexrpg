package io.vortexcore.loot;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class GaussianLootGenerator {

    public LootRoll roll(String itemId, int itemLevel, Collection<LootProfile> profiles) {
        Map<String, Double> rolledStats = new HashMap<>();
        double totalStandardScore = 0.0D;

        for (LootProfile profile : profiles) {
            double mean = profile.baseValue() + (itemLevel * profile.scalePerLevel());
            double gaussian = ThreadLocalRandom.current().nextGaussian();
            double deviation = clamp(gaussian * profile.spread(), -profile.maxSpread(), profile.maxSpread());
            double rolledValue = Math.max(0.0D, mean + deviation);
            rolledStats.put(profile.statKey(), round(rolledValue));
            totalStandardScore += profile.spread() == 0.0D ? 0.0D : deviation / profile.spread();
        }

        double qualityScore = profiles.isEmpty() ? 0.0D : totalStandardScore / profiles.size();
        return new LootRoll(itemId, itemLevel, Map.copyOf(rolledStats), qualityScore, resolveRarity(qualityScore));
    }

    private LootRoll.Rarity resolveRarity(double qualityScore) {
        if (qualityScore >= 2.85D) {
            return LootRoll.Rarity.GOD_TIER;
        }
        if (qualityScore >= 2.0D) {
            return LootRoll.Rarity.LEGENDARY;
        }
        if (qualityScore >= 1.2D) {
            return LootRoll.Rarity.EPIC;
        }
        if (qualityScore >= 0.45D) {
            return LootRoll.Rarity.RARE;
        }
        if (qualityScore >= -0.1D) {
            return LootRoll.Rarity.UNCOMMON;
        }
        return LootRoll.Rarity.COMMON;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
