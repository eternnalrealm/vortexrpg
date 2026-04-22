package io.vortexcore.loot;

public record LootProfile(String statKey, double baseValue, double scalePerLevel, double spread, double maxSpread) {
}
