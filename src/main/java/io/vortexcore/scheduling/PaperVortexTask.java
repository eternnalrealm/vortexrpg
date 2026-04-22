package io.vortexcore.scheduling;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/** VortexTask backed by a Folia {@link ScheduledTask}. Only instantiated on Paper/Folia. */
final class PaperVortexTask implements VortexTask {

    private final ScheduledTask delegate;

    PaperVortexTask(ScheduledTask delegate) {
        this.delegate = delegate;
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }
}
