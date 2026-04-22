package io.vortexcore.nexus;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

public record NexusAddress(Kind kind, UUID worldId, int chunkX, int chunkZ) {

    public enum Kind {
        ASYNC,
        GLOBAL,
        REGION
    }

    public static NexusAddress async() {
        return new NexusAddress(Kind.ASYNC, null, 0, 0);
    }

    public static NexusAddress global() {
        return new NexusAddress(Kind.GLOBAL, null, 0, 0);
    }

    public static NexusAddress region(World world, int chunkX, int chunkZ) {
        return new NexusAddress(Kind.REGION, world.getUID(), chunkX, chunkZ);
    }

    public static NexusAddress region(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return region(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
