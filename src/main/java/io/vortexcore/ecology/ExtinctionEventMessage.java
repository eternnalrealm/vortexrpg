package io.vortexcore.ecology;

import io.vortexcore.nexus.NexusAddress;
import io.vortexcore.nexus.NexusMessage;
import java.util.UUID;

public record ExtinctionEventMessage(
    UUID worldId,
    int chunkX,
    int chunkZ,
    ChunkKarmaSnapshot.SnapshotView snapshot
) implements NexusMessage {

    @Override
    public NexusAddress address() {
        return new NexusAddress(NexusAddress.Kind.REGION, worldId, chunkX, chunkZ);
    }
}
