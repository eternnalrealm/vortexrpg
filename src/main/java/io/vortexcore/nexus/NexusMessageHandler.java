package io.vortexcore.nexus;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface NexusMessageHandler<T extends NexusMessage> {

    CompletionStage<Void> handle(T message);
}
