package io.vortexcore.world;

import io.vortexcore.nexus.NexusAddress;
import io.vortexcore.nexus.NexusMessage;

public record FractureStateChangedMessage(
    FractureRegionSnapshot snapshot,
    FractureState previousState,
    FractureState newState
) implements NexusMessage {

    @Override
    public NexusAddress address() {
        FractureRegionKey key = snapshot.key();
        return new NexusAddress(NexusAddress.Kind.REGION, key.worldId(), key.chunkX(), key.chunkZ());
    }
}
