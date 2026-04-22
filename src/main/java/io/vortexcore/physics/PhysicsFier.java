package io.vortexcore.physics;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;

public final class PhysicsFier {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final Duration simulationPeriod;
    private final Map<UUID, PhysicsBody> activeBodies;

    private VortexTask simulationTask;

    public PhysicsFier(Logger logger, FoliaExecutionFacade scheduler, Duration simulationPeriod) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.simulationPeriod = simulationPeriod;
        this.activeBodies = new ConcurrentHashMap<>();
    }

    public void start() {
        if (simulationTask != null) {
            return;
        }
        long periodTicks = Math.max(1L, simulationPeriod.toMillis() / 50L);
        simulationTask = scheduler.runGlobalTimer("physicsfier-sim", 1L, periodTicks, task -> tickBodies());
    }

    public void stop() {
        if (simulationTask != null) {
            simulationTask.cancel();
            simulationTask = null;
        }
        activeBodies.clear();
    }

    public CompletableFuture<UUID> ignite(Block block, Vector impulse, PhysicsProfile profile) {
        CompletableFuture<UUID> future = new CompletableFuture<>();
        Location origin = block.getLocation();

        scheduler.runRegion(origin, "physicsfier-ignite", () -> {
            BlockData blockData = block.getBlockData().clone();
            block.setType(org.bukkit.Material.AIR, false);

            FallingBlock fallingBlock = origin.getWorld().spawn(origin.clone().add(0.5D, 0.0D, 0.5D), FallingBlock.class, spawned -> {
                spawned.setBlockData(blockData);
                spawned.setDropItem(false);
                spawned.setHurtEntities(false);
                spawned.setGravity(true);
            });
            fallingBlock.setDropItem(false);
            fallingBlock.setHurtEntities(false);
            fallingBlock.setVelocity(impulse.clone().multiply(1.0D / Math.max(0.1D, profile.mass())));

            activeBodies.put(
                fallingBlock.getUniqueId(),
                new PhysicsBody(fallingBlock.getUniqueId(), profile, System.currentTimeMillis() + profile.lifetime().toMillis())
            );
            future.complete(fallingBlock.getUniqueId());
        });

        return future;
    }

    private void tickBodies() {
        activeBodies.forEach((entityId, body) -> {
            Entity entity = Bukkit.getEntity(entityId);
            if (!(entity instanceof FallingBlock fallingBlock) || !entity.isValid()) {
                activeBodies.remove(entityId);
                return;
            }

            scheduler.runEntity(entity, "physicsfier-body-tick", () -> {
                if (System.currentTimeMillis() >= body.expireAt()) {
                    entity.remove();
                    activeBodies.remove(entityId);
                    return;
                }

                Vector velocity = entity.getVelocity();
                velocity.setX(velocity.getX() * Math.max(0.0D, 1.0D - body.profile().friction()));
                velocity.setZ(velocity.getZ() * Math.max(0.0D, 1.0D - body.profile().friction()));

                if (isTouchingGround(entity) && velocity.getY() < -0.01D) {
                    velocity.setY(Math.abs(velocity.getY()) * body.profile().restitution());
                } else {
                    velocity.setY(velocity.getY() - (0.04D * body.profile().gravityMultiplier()));
                }

                fallingBlock.setVelocity(velocity);
            }, () -> activeBodies.remove(entityId));
        });

        if (logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("PhysicsFier tick processed " + activeBodies.size() + " active bodies.");
        }
    }

    private boolean isTouchingGround(Entity entity) {
        return entity.wouldCollideUsing(entity.getBoundingBox().shift(0.0D, -0.05D, 0.0D));
    }

    private record PhysicsBody(UUID entityId, PhysicsProfile profile, long expireAt) {
    }
}
