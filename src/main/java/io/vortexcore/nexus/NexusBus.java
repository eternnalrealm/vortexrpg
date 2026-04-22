package io.vortexcore.nexus;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class NexusBus {

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Subscription<?>>> subscriptions;

    public NexusBus(Plugin plugin, FoliaExecutionFacade scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.subscriptions = new ConcurrentHashMap<>();
    }

    public <T extends NexusMessage> AutoCloseable subscribe(Class<T> type, NexusMessageHandler<T> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");

        CopyOnWriteArrayList<Subscription<?>> handlers = subscriptions.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
        Subscription<T> subscription = new Subscription<>(handler);
        handlers.add(subscription);
        return () -> handlers.remove(subscription);
    }

    public CompletableFuture<Void> publish(NexusMessage message) {
        Objects.requireNonNull(message, "message");
        CompletableFuture<Void> published = new CompletableFuture<>();

        scheduler.runAsync("nexus-publish-" + message.getClass().getSimpleName(), () -> {
            List<Subscription<?>> handlers = resolveHandlers(message);
            if (handlers.isEmpty()) {
                published.complete(null);
                return;
            }

            CompletableFuture<?>[] deliveries = handlers.stream()
                .map(handler -> deliver(handler, message).toCompletableFuture())
                .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(deliveries).whenComplete((ignored, error) -> {
                if (error != null) {
                    published.completeExceptionally(error);
                    return;
                }
                published.complete(null);
            });
        });

        return published;
    }

    private CompletionStage<Void> deliver(Subscription<?> subscription, NexusMessage message) {
        CompletableFuture<Void> delivery = new CompletableFuture<>();
        Runnable task = () -> invoke(subscription, message).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "NexusBus delivery failure for " + message.getClass().getSimpleName(), error);
                delivery.completeExceptionally(error);
                return;
            }
            delivery.complete(null);
        });

        NexusAddress address = message.address();
        switch (address.kind()) {
            case ASYNC -> scheduler.runAsync("nexus-async-delivery", task);
            case GLOBAL -> scheduler.runGlobal("nexus-global-delivery", task);
            case REGION -> {
                World world = Bukkit.getWorld(address.worldId());
                if (world == null) {
                    delivery.completeExceptionally(new IllegalStateException(
                        "Target world is not loaded for message " + message.getClass().getSimpleName()
                    ));
                    return delivery;
                }
                scheduler.runRegion(world, address.chunkX(), address.chunkZ(), "nexus-region-delivery", task);
            }
        }

        return delivery;
    }

    @SuppressWarnings("unchecked")
    private <T extends NexusMessage> CompletionStage<Void> invoke(Subscription<?> subscription, T message) {
        return ((Subscription<T>) subscription).handler().handle(message);
    }

    private List<Subscription<?>> resolveHandlers(NexusMessage message) {
        List<Subscription<?>> handlers = new ArrayList<>();
        Class<?> messageType = message.getClass();
        subscriptions.forEach((registeredType, registeredHandlers) -> {
            if (registeredType.isAssignableFrom(messageType)) {
                handlers.addAll(registeredHandlers);
            }
        });
        return handlers;
    }

    private record Subscription<T extends NexusMessage>(NexusMessageHandler<T> handler) {
    }
}
