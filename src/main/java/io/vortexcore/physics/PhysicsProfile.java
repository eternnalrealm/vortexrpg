package io.vortexcore.physics;

import java.time.Duration;

public record PhysicsProfile(double gravityMultiplier, double mass, double restitution, double friction, Duration lifetime) {
}
