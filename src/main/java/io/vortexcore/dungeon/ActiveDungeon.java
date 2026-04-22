package io.vortexcore.dungeon;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ActiveDungeon {

    public enum State { WAITING, WAVE_ACTIVE, BOSS_PHASE, COMPLETED, FAILED }

    private final UUID instanceId;
    private final DungeonDefinition definition;
    private final String worldName;
    private final Set<UUID> participants;
    private final Instant startedAt;
    private final BossBar timerBar;
    private volatile State state;
    private volatile int currentWaveIndex;
    private volatile int remainingMobs;

    public ActiveDungeon(UUID instanceId, DungeonDefinition definition, String worldName) {
        this.instanceId = instanceId;
        this.definition = definition;
        this.worldName = worldName;
        this.participants = ConcurrentHashMap.newKeySet();
        this.startedAt = Instant.now();
        this.timerBar = BossBar.bossBar(
            Component.text(definition.displayName()),
            1.0F,
            BossBar.Color.PURPLE,
            BossBar.Overlay.PROGRESS
        );
        this.state = State.WAITING;
        this.currentWaveIndex = 0;
        this.remainingMobs = 0;
    }

    public UUID instanceId() { return instanceId; }
    public DungeonDefinition definition() { return definition; }
    public String worldName() { return worldName; }
    public Set<UUID> participants() { return participants; }
    public Instant startedAt() { return startedAt; }
    public BossBar timerBar() { return timerBar; }
    public State state() { return state; }
    public int currentWaveIndex() { return currentWaveIndex; }
    public int remainingMobs() { return remainingMobs; }

    public void setState(State state) { this.state = state; }
    public void setCurrentWaveIndex(int index) { this.currentWaveIndex = index; }
    public void setRemainingMobs(int count) { this.remainingMobs = count; }

    public void decrementMobs() {
        this.remainingMobs = Math.max(0, this.remainingMobs - 1);
    }

    public long secondsElapsed() {
        return java.time.Duration.between(startedAt, Instant.now()).toSeconds();
    }

    public long secondsRemaining() {
        return Math.max(0, definition.timeLimitSeconds() - secondsElapsed());
    }

    public void addParticipant(Player player) {
        participants.add(player.getUniqueId());
        timerBar.addViewer(player);
    }

    public void removeParticipant(Player player) {
        participants.remove(player.getUniqueId());
        timerBar.removeViewer(player);
    }

    public void updateTimerBar() {
        long remaining = secondsRemaining();
        long total = definition.timeLimitSeconds();
        float progress = total > 0 ? Math.max(0F, Math.min(1F, (float) remaining / total)) : 1F;
        timerBar.progress(progress);

        String waveLabel;
        if (state == State.BOSS_PHASE) {
            waveLabel = "BOSS";
        } else if (state == State.COMPLETED) {
            waveLabel = "COMPLETE";
        } else {
            waveLabel = "Wave " + (currentWaveIndex + 1) + "/" + definition.waves().size();
        }

        long minutes = remaining / 60;
        long seconds = remaining % 60;
        timerBar.name(Component.text(
            definition.displayName() + " — " + waveLabel + " — " + minutes + ":" + String.format("%02d", seconds)
        ));

        if (progress < 0.25F) {
            timerBar.color(BossBar.Color.RED);
        } else if (progress < 0.5F) {
            timerBar.color(BossBar.Color.YELLOW);
        }
    }

    public void cleanup() {
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                timerBar.removeViewer(player);
            }
        }
    }
}
