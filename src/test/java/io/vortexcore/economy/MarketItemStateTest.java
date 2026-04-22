package io.vortexcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarketItemStateTest {

    @Test
    void livePriceStaysAtBaseWhenMarketIsBalanced() {
        MarketItemState state = new MarketItemState("arcane_shard", 100.0D, 100L);

        assertEquals(100.0D, state.livePrice(0.5D, 3.0D, 1.0D, 1.0D));
    }

    @Test
    void heavyDemandPushesPriceTowardConfiguredCap() {
        MarketItemState state = new MarketItemState("arcane_shard", 100.0D, 100L);
        state.recordPurchase(90L);

        MarketSnapshot snapshot = state.snapshot(0.5D, 3.0D, 1.0D, 1.0D);
        assertEquals(10L, snapshot.currentStock());
        assertEquals(90L, snapshot.buyVolume());
        assertEquals(300.0D, snapshot.livePrice());
    }

    @Test
    void stockDecayPullsOversuppliedItemsBackTowardTarget() {
        MarketItemState state = new MarketItemState("ration_kit", 80.0D, 100L);
        state.recordSale(100L);

        state.decayStock(0.25D);
        MarketSnapshot snapshot = state.snapshot(0.5D, 3.0D, 0.65D, 0.45D);

        assertEquals(175L, snapshot.currentStock());
        assertEquals(92L, snapshot.sellVolume());
        assertTrue(snapshot.livePrice() < snapshot.basePrice());
        assertTrue(snapshot.livePrice() >= snapshot.basePrice() * 0.5D);
    }
}
