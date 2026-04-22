package io.vortexcore.boss;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.util.Comparator;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public final class BossAbilityExecutor {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;

    public BossAbilityExecutor(Logger logger, FoliaExecutionFacade scheduler) {
        this.logger = logger;
        this.scheduler = scheduler;
    }

    public void execute(ActiveBoss activeBoss, String abilityId) {
        LivingEntity bossEntity = resolveEntity(activeBoss.entityId());
        if (bossEntity == null || bossEntity.isDead()) {
            return;
        }

        scheduler.runEntity(bossEntity, "boss-ability-" + abilityId + "-" + activeBoss.entityId(), () -> {
            switch (abilityId) {
                case "fireball_barrage" -> fireballBarrage(bossEntity, activeBoss);
                case "flame_aura" -> flameAura(bossEntity, activeBoss);
                case "enrage" -> enrage(bossEntity, activeBoss);
                default -> logger.fine("Unknown boss ability: " + abilityId);
            }
        });
    }

    private void fireballBarrage(LivingEntity bossEntity, ActiveBoss activeBoss) {
        Player nearest = findNearestParticipant(bossEntity, activeBoss);
        if (nearest == null) {
            return;
        }

        Vector direction = nearest.getLocation().toVector()
            .subtract(bossEntity.getEyeLocation().toVector())
            .normalize();

        for (int i = 0; i < 3; i++) {
            Vector spread = direction.clone().add(new Vector(
                (Math.random() - 0.5) * 0.3,
                (Math.random() - 0.5) * 0.15,
                (Math.random() - 0.5) * 0.3
            )).normalize();

            SmallFireball fireball = bossEntity.getWorld().spawn(
                bossEntity.getEyeLocation(),
                SmallFireball.class
            );
            fireball.setShooter(bossEntity);
            fireball.setVelocity(spread.multiply(1.5));
            fireball.setIsIncendiary(false);
        }
    }

    private void flameAura(LivingEntity bossEntity, ActiveBoss activeBoss) {
        double radius = 5.0;
        for (java.util.UUID participantId : activeBoss.participants()) {
            Player participant = Bukkit.getPlayer(participantId);
            if (participant != null
                && participant.getWorld().equals(bossEntity.getWorld())
                && participant.getLocation().distanceSquared(bossEntity.getLocation()) <= radius * radius) {
                participant.damage(4.0, bossEntity);
                participant.setFireTicks(40);
            }
        }
    }

    private void enrage(LivingEntity bossEntity, ActiveBoss activeBoss) {
        bossEntity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1, false, true, true));
        bossEntity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1, false, true, true));

        AttributeInstance attack = bossEntity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            BossDefinition.BossPhase phase = activeBoss.currentPhase();
            attack.setBaseValue(attack.getDefaultValue() * phase.damageMultiplier() * 1.5);
        }
    }

    private Player findNearestParticipant(LivingEntity bossEntity, ActiveBoss activeBoss) {
        return activeBoss.participants().stream()
            .map(Bukkit::getPlayer)
            .filter(player -> player != null
                && player.getWorld().equals(bossEntity.getWorld())
                && !player.isDead())
            .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(bossEntity.getLocation())))
            .orElse(null);
    }

    private LivingEntity resolveEntity(java.util.UUID entityId) {
        org.bukkit.entity.Entity entity = Bukkit.getServer().getEntity(entityId);
        return entity instanceof LivingEntity living ? living : null;
    }
}
