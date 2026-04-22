package io.vortexcore.integration.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vortexcore.world.ExpeditionCompletion;
import io.vortexcore.world.ExpeditionScoreTier;
import org.junit.jupiter.api.Test;

class VaultEconomyBridgeTest {

    @Test
    void expeditionRewardIncludesTierBonusAndScoreScaling() {
        VaultEconomyBridge.RewardPolicy policy = new VaultEconomyBridge.RewardPolicy(120.0D, 0.06D, 220.0D, 2.5D, 0.08D);
        ExpeditionCompletion completion = new ExpeditionCompletion(
            java.util.UUID.randomUUID(),
            "Scout the unstable frontier",
            "2026-W13",
            ExpeditionScoreTier.A,
            840.0D,
            420L,
            180
        );

        assertEquals(230.4D, policy.expeditionReward(completion));
    }

    @Test
    void breachCacheRewardScalesWithTokensAndExperience() {
        VaultEconomyBridge.RewardPolicy policy = new VaultEconomyBridge.RewardPolicy(120.0D, 0.06D, 220.0D, 2.5D, 0.08D);

        assertEquals(268.0D, policy.breachCacheReward(100, 16L));
        assertEquals(220.0D, policy.breachCacheReward(-10, -5L));
    }
}
