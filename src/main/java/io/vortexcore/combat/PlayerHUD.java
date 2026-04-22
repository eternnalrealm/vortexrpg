package io.vortexcore.combat;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.world.ExpeditionPartyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Sends each player an ActionBar HUD showing HP, Mana, and Level every 20 ticks.
 *
 * <p>Players inside a multi-member party are skipped — the PartyService ActionBar
 * already covers them.
 *
 * <p>Format: {@code ❤ 18/20   |   ✦ 45/72   |   ★ Lv.12}
 */
public final class PlayerHUD {

    private static final MiniMessage MINI      = MiniMessage.miniMessage();
    private static final long        HUD_TICKS = 20L;

    private final FoliaExecutionFacade  scheduler;
    private final PlayerProfileService  playerProfileService;
    private final ManaManager           manaManager;
    private final ExpeditionPartyManager partyManager;
    private VortexTask hudTask;

    public PlayerHUD(
            FoliaExecutionFacade scheduler,
            PlayerProfileService playerProfileService,
            ManaManager manaManager,
            ExpeditionPartyManager partyManager) {
        this.scheduler           = scheduler;
        this.playerProfileService = playerProfileService;
        this.manaManager         = manaManager;
        this.partyManager        = partyManager;
    }

    public void start() {
        if (hudTask != null) return;
        hudTask = scheduler.runGlobalTimer("player-hud", HUD_TICKS, HUD_TICKS, ignored -> tick());
    }

    public void stop() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
    }



    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Party HUD handles players who are in a multi-member party
            var partyOpt = partyManager.partyOf(player.getUniqueId());
            if (partyOpt.isPresent() && partyOpt.get().members().size() > 1) continue;

            player.sendActionBar(buildHud(player));
        }
    }

    private Component buildHud(Player player) {
        // HP
        double hp    = player.getHealth();
        AttributeInstance maxHpAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxHpAttr != null ? maxHpAttr.getValue() : 20.0;

        // Mana
        double mana    = manaManager.getMana(player.getUniqueId());
        double maxMana = manaManager.getMaxMana(player);

        // Level
        int level = playerProfileService.profile(player).level();

        String hpStr   = String.format("%.0f/%.0f", hp, maxHp);
        String manaStr = String.format("%.0f/%.0f", mana, maxMana);

        return MINI.deserialize(
            "<red>❤ " + hpStr + "</red>"
            + "   <dark_gray>|</dark_gray>   "
            + "<aqua>✦ " + manaStr + "</aqua>"
            + "   <dark_gray>|</dark_gray>   "
            + "<gold>★ Lv." + level + "</gold>"
        );
    }
}
