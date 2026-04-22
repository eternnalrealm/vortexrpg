package io.vortexcore.economy;

public record MarketSnapshot(
    String itemId,
    double basePrice,
    double livePrice,
    long targetStock,
    long currentStock,
    long buyVolume,
    long sellVolume
) {
}
