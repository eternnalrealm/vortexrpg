package io.vortexcore.magic;

import io.vortexcore.nexus.NexusBus;
import io.vortexcore.player.UnifiedPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;

public final class SpellContext {

    private final UUID executionId;
    private final UnifiedPlayer caster;
    private final Location origin;
    private final NexusBus nexusBus;
    private final ConcurrentHashMap<String, Object> state;
    private final Object triggerSource;

    public SpellContext(UUID executionId, UnifiedPlayer caster, Location origin, NexusBus nexusBus, Object triggerSource) {
        this.executionId = executionId;
        this.caster = caster;
        this.origin = origin.clone();
        this.nexusBus = nexusBus;
        this.state = new ConcurrentHashMap<>();
        this.triggerSource = triggerSource;
    }

    public UUID executionId() {
        return executionId;
    }

    public UnifiedPlayer caster() {
        return caster;
    }

    public Location origin() {
        return origin.clone();
    }

    public NexusBus nexusBus() {
        return nexusBus;
    }

    public Object triggerSource() {
        return triggerSource;
    }

    public void putState(String key, Object value) {
        state.put(key, value);
    }

    public Object state(String key) {
        return state.get(key);
    }

    public Map<String, Object> stateSnapshot() {
        return Map.copyOf(state);
    }
}
