package io.vortexcore.magic;

import java.util.Collection;

public interface SpellTargeter {

    TargeterType type();

    Collection<SpellTarget> acquireTargets(SpellContext context);
}
