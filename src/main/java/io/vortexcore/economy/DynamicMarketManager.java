package io.vortexcore.economy;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class DynamicMarketManager {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final Map<String, MarketItemState> items;
    private final long defaultBaselineStock;
    private final double floorMultiplier;
    private final double capMultiplier;
    private final double demandElasticity;
    private final double stockElasticity;
    private final double stockDecayRate;
    private final Duration stockDecayPeriod;

    private VortexTask decayTask;

    public DynamicMarketManager(
        Logger logger,
        FoliaExecutionFacade scheduler,
        long defaultBaselineStock,
        double floorMultiplier,
        double capMultiplier,
        double demandElasticity,
        double stockElasticity,
        double stockDecayRate,
        Duration stockDecayPeriod
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.items = new ConcurrentHashMap<>();
        this.defaultBaselineStock = defaultBaselineStock;
        this.floorMultiplier = floorMultiplier;
        this.capMultiplier = capMultiplier;
        this.demandElasticity = demandElasticity;
        this.stockElasticity = stockElasticity;
        this.stockDecayRate = stockDecayRate;
        this.stockDecayPeriod = stockDecayPeriod;
    }

    public void start() {
        if (decayTask != null) {
            return;
        }

        long periodTicks = Math.max(20L, stockDecayPeriod.toMillis() / 50L);
        this.decayTask = scheduler.runGlobalTimer("market-stock-decay", periodTicks, periodTicks, task -> tickStockDecay());
    }

    public void stop() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    public void registerItem(String itemId, double basePrice) {
        registerItem(itemId, basePrice, defaultBaselineStock);
    }

    public void registerItem(String itemId, double basePrice, long targetStock) {
        items.computeIfAbsent(itemId, ignored -> new MarketItemState(itemId, basePrice, targetStock));
    }

    public void recordPurchase(String itemId, long quantity) {
        items.computeIfAbsent(itemId, ignored -> new MarketItemState(itemId, 1.0D, defaultBaselineStock)).recordPurchase(quantity);
    }

    public void recordSale(String itemId, long quantity) {
        items.computeIfAbsent(itemId, ignored -> new MarketItemState(itemId, 1.0D, defaultBaselineStock)).recordSale(quantity);
    }

    public double quotePrice(String itemId) {
        return items.computeIfAbsent(itemId, ignored -> new MarketItemState(itemId, 1.0D, defaultBaselineStock))
            .livePrice(floorMultiplier, capMultiplier, demandElasticity, stockElasticity);
    }

    public MarketSnapshot snapshot(String itemId) {
        return items.computeIfAbsent(itemId, ignored -> new MarketItemState(itemId, 1.0D, defaultBaselineStock))
            .snapshot(floorMultiplier, capMultiplier, demandElasticity, stockElasticity);
    }

    private void tickStockDecay() {
        items.values().forEach(item -> item.decayStock(stockDecayRate));
        logger.fine("Dynamic market stock decay tick completed for " + items.size() + " tracked items.");
    }
}
