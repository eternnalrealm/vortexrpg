package io.vortexcore.ecology;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class ChunkKarmaSnapshot {

    private final LongAdder treesCut;
    private final LongAdder mobsKilled;
    private final LongAdder oresMined;
    private final AtomicBoolean extinctionTriggered;

    public ChunkKarmaSnapshot() {
        this.treesCut = new LongAdder();
        this.mobsKilled = new LongAdder();
        this.oresMined = new LongAdder();
        this.extinctionTriggered = new AtomicBoolean();
    }

    public void recordTreeCut() {
        treesCut.increment();
    }

    public void recordMobKill() {
        mobsKilled.increment();
    }

    public void recordOreMined() {
        oresMined.increment();
    }

    public long treesCut() {
        return treesCut.sum();
    }

    public long mobsKilled() {
        return mobsKilled.sum();
    }

    public long oresMined() {
        return oresMined.sum();
    }

    public boolean markExtinct() {
        return extinctionTriggered.compareAndSet(false, true);
    }

    public boolean extinct() {
        return extinctionTriggered.get();
    }

    public SnapshotView snapshotView() {
        return new SnapshotView(treesCut(), mobsKilled(), oresMined(), extinct());
    }

    public record SnapshotView(long treesCut, long mobsKilled, long oresMined, boolean extinct) {
    }
}
