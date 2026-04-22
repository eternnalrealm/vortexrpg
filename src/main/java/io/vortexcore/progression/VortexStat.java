package io.vortexcore.progression;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class VortexStat {

    private final AtomicReference<Double> baseValue;
    private final AtomicReference<Double> flatBonus;
    private final AtomicReference<Double> multiplier;
    private final AtomicLong version;

    public VortexStat() {
        this(0.0D);
    }

    public VortexStat(double baseValue) {
        this.baseValue = new AtomicReference<>(baseValue);
        this.flatBonus = new AtomicReference<>(0.0D);
        this.multiplier = new AtomicReference<>(1.0D);
        this.version = new AtomicLong();
    }

    public void setBase(double value) {
        baseValue.set(value);
        version.incrementAndGet();
    }

    public void addFlat(double delta) {
        flatBonus.updateAndGet(current -> current + delta);
        version.incrementAndGet();
    }

    public void addMultiplier(double delta) {
        multiplier.updateAndGet(current -> Math.max(0.0D, current + delta));
        version.incrementAndGet();
    }

    public void resetTransientBonuses() {
        flatBonus.set(0.0D);
        multiplier.set(1.0D);
        version.incrementAndGet();
    }

    public double current() {
        return (baseValue.get() + flatBonus.get()) * multiplier.get();
    }

    public StatSnapshot snapshot() {
        return new StatSnapshot(baseValue.get(), flatBonus.get(), multiplier.get(), version.get());
    }

    public record StatSnapshot(double baseValue, double flatBonus, double multiplier, long version) {

        public double total() {
            return (baseValue + flatBonus) * multiplier;
        }
    }
}
