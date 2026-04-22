package io.vortexcore.player;

import io.vortexcore.math.ExperienceCurve;
import io.vortexcore.persistence.model.PlayerSnapshot;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.progression.VortexStat;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class UnifiedPlayer {

    private final UUID uniqueId;
    private final AtomicReference<String> lastKnownName;
    private final ConcurrentMap<VortexAttribute, VortexStat> stats;
    private final ConcurrentMap<String, String> metadata;
    private final AtomicInteger level;
    private final AtomicLong experience;
    private final AtomicLong snapshotRevision;
    private final Object progressionMutex;

    public UnifiedPlayer(UUID uniqueId, String lastKnownName) {
        this.uniqueId = uniqueId;
        this.lastKnownName = new AtomicReference<>(lastKnownName);
        this.stats = new ConcurrentHashMap<>();
        this.metadata = new ConcurrentHashMap<>();
        this.level = new AtomicInteger(1);
        this.experience = new AtomicLong(0L);
        this.snapshotRevision = new AtomicLong(0L);
        this.progressionMutex = new Object();

        Arrays.stream(VortexAttribute.values()).forEach(attribute -> stats.put(attribute, new VortexStat()));
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String lastKnownName() {
        return lastKnownName.get();
    }

    public void updateLastKnownName(String newName) {
        lastKnownName.set(newName);
    }

    public VortexStat stat(VortexAttribute attribute) {
        return stats.computeIfAbsent(attribute, ignored -> new VortexStat());
    }

    public int level() {
        return level.get();
    }

    public long experience() {
        return experience.get();
    }

    public long snapshotRevision() {
        return snapshotRevision.get();
    }

    public ProgressionResult grantExperience(long amount, ExperienceCurve curve) {
        synchronized (progressionMutex) {
            long totalExperience = experience.addAndGet(amount);
            int resolvedLevel = curve.solveLevel(totalExperience);
            int previousLevel = level.getAndSet(resolvedLevel);
            return new ProgressionResult(previousLevel, resolvedLevel, totalExperience);
        }
    }

    public ProgressionResult setExperience(long totalExperience, ExperienceCurve curve) {
        synchronized (progressionMutex) {
            long clampedExperience = Math.max(0L, totalExperience);
            experience.set(clampedExperience);
            int resolvedLevel = curve.solveLevel(clampedExperience);
            int previousLevel = level.getAndSet(resolvedLevel);
            return new ProgressionResult(previousLevel, resolvedLevel, clampedExperience);
        }
    }

    public Map<VortexAttribute, VortexStat.StatSnapshot> snapshotStats() {
        Map<VortexAttribute, VortexStat.StatSnapshot> snapshot = new EnumMap<>(VortexAttribute.class);
        stats.forEach((attribute, stat) -> snapshot.put(attribute, stat.snapshot()));
        return snapshot;
    }

    public Map<String, String> metadata() {
        return Map.copyOf(metadata);
    }

    public void putMetadata(String key, String value) {
        metadata.put(key, value);
    }

    public PlayerSnapshot toSnapshot() {
        Map<String, Double> serializedStats = new HashMap<>();
        stats.forEach((attribute, stat) -> serializedStats.put(attribute.name(), stat.snapshot().baseValue()));
        return new PlayerSnapshot(
            uniqueId,
            snapshotRevision.get(),
            level.get(),
            experience.get(),
            Map.copyOf(serializedStats),
            Map.copyOf(metadata),
            Instant.now()
        );
    }

    public void applySnapshot(PlayerSnapshot snapshot) {
        snapshotRevision.set(snapshot.revision());
        level.set(snapshot.level());
        experience.set(snapshot.experience());
        snapshot.stats().forEach((key, value) -> stat(VortexAttribute.valueOf(key)).setBase(value));
        metadata.clear();
        metadata.putAll(snapshot.metadata());
    }

    public record ProgressionResult(int previousLevel, int newLevel, long totalExperience) {

        public int levelsGained() {
            return Math.max(0, newLevel - previousLevel);
        }
    }
}
