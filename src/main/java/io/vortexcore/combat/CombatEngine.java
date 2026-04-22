package io.vortexcore.combat;

import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.PlayerProfileService;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Adds CRIT and DODGE mechanics on top of Bukkit's vanilla attribute system.
 *
 * <p>Both stats are derived from {@link VortexAttribute#DEXTERITY}:
 * <ul>
 *   <li>CRIT  — chance = DEX × 0.5% (cap 35%), multiplier = 1.5 + STR × 0.01 (cap 2.5×)
 *   <li>DODGE — chance = DEX × 0.3% (cap 25%), fully negates hit
 * </ul>
 *
 * <p>Priority ordering:
 * <ul>
 *   <li>LOW   — DODGE: cancelled early so later handlers do not waste time
 *   <li>HIGH  — CRIT:  damage modified before profession scaling (HIGHEST)
 * </ul>
 */
public final class CombatEngine implements Listener {

    private static final double CRIT_CHANCE_PER_DEX  = 0.005;  // 0.5% per DEX point
    private static final double CRIT_CHANCE_CAP      = 0.35;   // max 35%
    private static final double CRIT_BASE_MULT       = 1.5;
    private static final double CRIT_MULT_PER_STR    = 0.01;   // +1% multiplier per STR point
    private static final double CRIT_MULT_CAP        = 2.5;

    private static final double DODGE_CHANCE_PER_DEX = 0.003;  // 0.3% per DEX point
    private static final double DODGE_CHANCE_CAP     = 0.25;   // max 25%

    private final PlayerProfileService playerProfileService;

    public CombatEngine(PlayerProfileService playerProfileService) {
        this.playerProfileService = playerProfileService;
    }



    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutgoingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;

        var profile = playerProfileService.profile(attacker);
        double dex = profile.stat(VortexAttribute.DEXTERITY).current();
        double str = profile.stat(VortexAttribute.STRENGTH).current();

        double critChance = Math.min(CRIT_CHANCE_CAP, dex * CRIT_CHANCE_PER_DEX);
        if (ThreadLocalRandom.current().nextDouble() < critChance) {
            double critMult = Math.min(CRIT_MULT_CAP, CRIT_BASE_MULT + str * CRIT_MULT_PER_STR);
            event.setDamage(event.getDamage() * critMult);
            attacker.sendActionBar(Component.text("✦ CRITICAL!", NamedTextColor.YELLOW));
        }
    }



    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIncomingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player defender)) return;

        var profile = playerProfileService.profile(defender);
        double dex = profile.stat(VortexAttribute.DEXTERITY).current();

        double dodgeChance = Math.min(DODGE_CHANCE_CAP, dex * DODGE_CHANCE_PER_DEX);
        if (ThreadLocalRandom.current().nextDouble() < dodgeChance) {
            event.setCancelled(true);
            defender.sendActionBar(Component.text("» Evaded!", NamedTextColor.AQUA));
        }
    }



    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof AbstractArrow arrow
                && arrow.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}
