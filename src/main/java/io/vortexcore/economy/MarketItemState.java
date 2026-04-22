package io.vortexcore.economy;

import java.util.concurrent.atomic.AtomicLong;

public final class MarketItemState {

    private final String itemId;
    private final double basePrice;
    private final long targetStock;
    private final AtomicLong currentStock;
    private final AtomicLong buyVolume;
    private final AtomicLong sellVolume;

    public MarketItemState(String itemId, double basePrice, long targetStock) {
        this.itemId = itemId;
        this.basePrice = basePrice;
        this.targetStock = targetStock;
        this.currentStock = new AtomicLong(targetStock);
        this.buyVolume = new AtomicLong();
        this.sellVolume = new AtomicLong();
    }

    public void recordPurchase(long quantity) {
        buyVolume.addAndGet(quantity);
        currentStock.updateAndGet(stock -> Math.max(0L, stock - quantity));
    }

    public void recordSale(long quantity) {
        sellVolume.addAndGet(quantity);
        currentStock.addAndGet(quantity);
    }

    public double livePrice(double floorMultiplier, double capMultiplier, double demandElasticity, double stockElasticity) {
        double demandRatio = (buyVolume.get() + 1.0D) / (sellVolume.get() + 1.0D);
        double scarcityRatio = targetStock <= 0L ? 1.0D : (double) targetStock / Math.max(1L, currentStock.get());
        double rawPrice = basePrice
            * Math.pow(demandRatio, demandElasticity)
            * Math.pow(Math.max(0.25D, scarcityRatio), stockElasticity);

        return clamp(rawPrice, basePrice * floorMultiplier, basePrice * capMultiplier);
    }

    public void decayStock(double decayRate) {
        currentStock.updateAndGet(stock -> {
            if (stock <= targetStock) {
                return stock;
            }
            long excess = stock - targetStock;
            long decay = Math.max(1L, Math.round(excess * decayRate));
            return stock - Math.min(excess, decay);
        });
        buyVolume.updateAndGet(volume -> Math.max(0L, Math.round(volume * 0.92D)));
        sellVolume.updateAndGet(volume -> Math.max(0L, Math.round(volume * 0.92D)));
    }

    public MarketSnapshot snapshot(double floorMultiplier, double capMultiplier, double demandElasticity, double stockElasticity) {
        return new MarketSnapshot(
            itemId,
            basePrice,
            livePrice(floorMultiplier, capMultiplier, demandElasticity, stockElasticity),
            targetStock,
            currentStock.get(),
            buyVolume.get(),
            sellVolume.get()
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
