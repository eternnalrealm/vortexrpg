package io.vortexcore.magic;

import java.util.concurrent.CompletionStage;

public interface SpellTrigger {

    TriggerType type();

    CompletionStage<Void> bind(CompiledSpell spell, SpellContext context);
}
