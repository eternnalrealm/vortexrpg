package io.vortexcore.instances;

import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Location;

public record PartyInstance(UUID partyId, String templateName, String worldName, Path worldDirectory, Location spawnLocation) {

    public PartyInstance {
        spawnLocation = spawnLocation.clone();
    }
}
