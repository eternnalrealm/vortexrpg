package io.vortexcore.magic;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface CompiledSpell {

    String id();

    TriggerType triggerType();

    List<SpellTargeter> targeters();

    List<SpellCondition> conditions();

    List<SpellMechanic> mechanics();

    CompletionStage<Void> execute(SpellContext context);
}
