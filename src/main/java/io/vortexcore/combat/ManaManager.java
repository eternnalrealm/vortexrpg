package io.vortexcore.combat;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.progression.VortexAttribute;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Tracks and regenerates mana for every online player.
 *
 * <p>Formulae:
 * <ul>
 *   <li>Max mana  = {@code 20 + (WISDOM + INTELLIGENCE) × 4}
 *   <li>Regen/sec = {@code 1.0 + WISDOM × 0.15} (fires every 40 ticks / 2 s)
 * </ul>
 */
public final class ManaManager implements Listener {

    private static final double MANA_BASE        = 20.0;
    private static final double MANA_PER_POINT   = 4.0;
    private static final double BASE_REGEN       = 1.0;
    private static final double WIS_REGEN_FACTOR = 0.15;
    private static final long   REGEN_TICKS      = 40L;


    private final FoliaExecutionFacade scheduler;
    private final PlayerProfileService playerProfileService;
    private final ConcurrentMap<UUID, Double> manaMap = new ConcurrentHashMap<>();
    private VortexTask regenTask;

    public ManaManager(FoliaExecutionFacade scheduler, PlayerProfileService playerProfileService) {
        this.scheduler               = scheduler;
        this.playerProfileService = playerProfileService;
    }

    public void start() {
        if (regenTask != null) return;
        regenTask = scheduler.runGlobalTimer("mana-regen", REGEN_TICKS, REGEN_TICKS, ignored -> tickRegen());
    }

    public void stop() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        manaMap.clear();
    }



    private void tickRegen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id      = player.getUniqueId();
            var  profile = playerProfileService.profile(player);
            double wisdom = profile.stat(VortexAttribute.WISDOM).current();
            double intel  = profile.stat(VortexAttribute.INTELLIGENCE).current();
            double max    = computeMaxMana(wisdom, intel);
            double regen  = BASE_REGEN + wisdom * WIS_REGEN_FACTOR;
            manaMap.merge(id, regen, (cur, r) -> Math.min(max, cur + r));
        }
    }



    public double getMaxMana(Player player) {
        var profile = playerProfileService.profile(player);
        return computeMaxMana(
            profile.stat(VortexAttribute.WISDOM).current(),
            profile.stat(VortexAttribute.INTELLIGENCE).current()
        );
    }

    public double getMana(UUID uuid) {
        return manaMap.getOrDefault(uuid, 0.0);
    }

    public boolean hasMana(UUID uuid, double cost) {
        return getMana(uuid) >= cost;
    }

    /**
     * Deducts {@code cost} mana.
     *
     * @return {@code true} if mana was consumed; {@code false} if insufficient.
     */
    public boolean consumeMana(UUID uuid, double cost) {
        if (!hasMana(uuid, cost)) return false;
        manaMap.compute(uuid, (id, cur) -> cur == null ? 0.0 : Math.max(0.0, cur - cost));
        return true;
    }

    public void setMana(UUID uuid, double value) {
        Player player = Bukkit.getPlayer(uuid);
        double max = player != null ? getMaxMana(player) : MANA_BASE;
        manaMap.put(uuid, Math.max(0.0, Math.min(max, value)));
    }

    public void refillFull(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        manaMap.put(uuid, player != null ? getMaxMana(player) : MANA_BASE);
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refillFull(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manaMap.remove(event.getPlayer().getUniqueId());
    }



    private static double computeMaxMana(double wisdom, double intelligence) {
        return MANA_BASE + (wisdom + intelligence) * MANA_PER_POINT;
    }
}
