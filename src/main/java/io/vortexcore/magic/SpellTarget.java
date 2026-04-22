package io.vortexcore.magic;

import java.util.UUID;
import org.bukkit.Location;

public record SpellTarget(TargetKind kind, UUID entityId, Location location) {

    public enum TargetKind {
        SELF,
        ENTITY,
        BLOCK,
        LOCATION,
        PARTY
    }

    public SpellTarget {
        location = location == null ? null : location.clone();
    }
}
