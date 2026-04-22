package io.vortexcore.world;

import io.vortexcore.instances.PartyInstance;
import java.util.UUID;

public record FractureBossInstance(UUID expeditionId, FractureRegionSnapshot fracture, PartyInstance partyInstance) {
}
