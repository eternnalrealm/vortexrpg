package io.vortexcore.world;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

public record FractureRegionKey(UUID worldId, int chunkX, int chunkZ) {

    public static FractureRegionKey from(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return new FractureRegionKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
