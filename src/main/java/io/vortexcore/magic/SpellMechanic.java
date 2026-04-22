package io.vortexcore.magic;

import java.util.concurrent.CompletionStage;

public interface SpellMechanic {

    MechanicType type();

    CompletionStage<Void> apply(SpellContext context, SpellTarget target);
}
