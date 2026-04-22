package io.vortexcore.magic;

public interface SpellCondition {

    ConditionType type();

    boolean test(SpellContext context, SpellTarget target);
}
