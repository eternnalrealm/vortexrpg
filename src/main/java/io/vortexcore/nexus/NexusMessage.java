package io.vortexcore.nexus;

public interface NexusMessage {

    default NexusAddress address() {
        return NexusAddress.async();
    }
}
