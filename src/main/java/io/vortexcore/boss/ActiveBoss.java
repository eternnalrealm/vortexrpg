package io.vortexcore.boss;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class ActiveBoss {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final double ENTITY_HEALTH_CAP = 1024.0;

    private final UUID entityId;
    private final BossDefinition definition;
    private final BossBar bossBar;
    private final Set<UUID> participants;
    private final Instant spawnedAt;
    private final double realMaxHealth;
    private int currentPhaseIndex;
    private long lastAbilityAtMillis;

    public ActiveBoss(UUID entityId, BossDefinition definition) {
        this.entityId = entityId;
        this.definition = definition;
        this.realMaxHealth = definition.maxHealth();
        this.bossBar = BossBar.bossBar(
            MINI.deserialize(definition.displayName()),
            1.0F,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS
        );
        this.participants = ConcurrentHashMap.newKeySet();
        this.spawnedAt = Instant.now();
        this.currentPhaseIndex = 0;
        this.lastAbilityAtMillis = System.currentTimeMillis();
    }

    public UUID entityId() {
        return entityId;
    }

    public BossDefinition definition() {
        return definition;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public Set<UUID> participants() {
        return participants;
    }

    public double realMaxHealth() {
        return realMaxHealth;
    }

    public double entityHealthCap() {
        return Math.min(realMaxHealth, ENTITY_HEALTH_CAP);
    }

    public double damageScale() {
        return realMaxHealth > ENTITY_HEALTH_CAP ? entityHealthCap() / realMaxHealth : 1.0;
    }

    public Instant spawnedAt() {
        return spawnedAt;
    }

    public int currentPhaseIndex() {
        return currentPhaseIndex;
    }

    public BossDefinition.BossPhase currentPhase() {
        return definition.phases().get(currentPhaseIndex);
    }

    public long lastAbilityAtMillis() {
        return lastAbilityAtMillis;
    }

    public void setLastAbilityAtMillis(long millis) {
        this.lastAbilityAtMillis = millis;
    }

    public void addParticipant(Player player) {
        participants.add(player.getUniqueId());
        bossBar.addViewer(player);
    }

    public boolean tick() {
        LivingEntity entity = resolveEntity();
        if (entity == null || entity.isDead()) {
            return false;
        }

        double healthRatio = entity.getHealth() / maxHealthOf(entity);
        bossBar.progress(Math.max(0.0F, Math.min(1.0F, (float) healthRatio)));

        int newPhaseIndex = currentPhaseIndex;
        for (int i = currentPhaseIndex; i < definition.phases().size(); i++) {
            if (healthRatio <= definition.phases().get(i).threshold()) {
                newPhaseIndex = i;
            }
        }

        if (newPhaseIndex != currentPhaseIndex) {
            currentPhaseIndex = newPhaseIndex;
            applyPhaseBuffs(entity);
        }

        if (secondsAlive() >= definition.despawnAfterSeconds()) {
            despawn();
            return false;
        }

        return true;
    }

    public void applyPhaseBuffs(LivingEntity entity) {
        BossDefinition.BossPhase phase = currentPhase();
        AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getDefaultValue() * phase.speedMultiplier());
        }
        AttributeInstance attack = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getDefaultValue() * phase.damageMultiplier());
        }
    }

    public void defeat() {
        participants.forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                bossBar.removeViewer(player);
            }
        });
    }

    public void despawn() {
        LivingEntity entity = resolveEntity();
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
        participants.forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                bossBar.removeViewer(player);
            }
        });
    }

    public long secondsAlive() {
        return java.time.Duration.between(spawnedAt, Instant.now()).toSeconds();
    }

    private LivingEntity resolveEntity() {
        org.bukkit.entity.Entity entity = Bukkit.getServer().getEntity(entityId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private double maxHealthOf(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : entity.getHealth();
    }
}
