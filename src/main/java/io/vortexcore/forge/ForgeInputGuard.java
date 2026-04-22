package io.vortexcore.forge;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared guard that ensures only one forge service owns a player's pending
 * chat input at any given time, preventing AsyncChatEvent race conditions
 * between SpellForgeService, EffectForgeService, and ItemForgeService.
 */
public final class ForgeInputGuard {

    private final ConcurrentMap<UUID, String> activeForge = new ConcurrentHashMap<>();

    public void claim(UUID playerId, String forgeId) {
        activeForge.put(playerId, forgeId);
    }

    public boolean owns(UUID playerId, String forgeId) {
        return forgeId.equals(activeForge.get(playerId));
    }

    public void release(UUID playerId) {
        activeForge.remove(playerId);
    }
}
