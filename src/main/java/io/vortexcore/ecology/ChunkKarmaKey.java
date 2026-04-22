package io.vortexcore.ecology;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

public record ChunkKarmaKey(UUID worldId, int chunkX, int chunkZ) {

    public static ChunkKarmaKey from(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world is required");
        }
        return new ChunkKarmaKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
