package io.vortexcore.cosmetic;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.player.UnifiedPlayer;
import io.vortexcore.rpg.PlayerProfileService;
import io.vortexcore.rpg.SpellbookService;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.util.ArrayList;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class PrestigeCosmeticService implements SpellbookService.SpellCastListener, AutoCloseable {

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final PlayerProfileService playerProfileService;
    private final long trailPeriodTicks;
    private VortexTask trailTask;

    public PrestigeCosmeticService(
        Plugin plugin,
        FoliaExecutionFacade scheduler,
        PlayerProfileService playerProfileService,
        long trailPeriodTicks
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.playerProfileService = playerProfileService;
        this.trailPeriodTicks = Math.max(5L, trailPeriodTicks);
    }

    public void start() {
        if (trailTask != null) {
            trailTask.cancel();
        }
        trailTask = scheduler.runGlobalTimer("prestige-trails", trailPeriodTicks, trailPeriodTicks, task -> tickTrails());
    }

    @Override
    public void close() {
        if (trailTask != null) {
            trailTask.cancel();
            trailTask = null;
        }
    }

    @Override
    public void onSpellCast(Player player, String spellId) {
        UnifiedPlayer profile = playerProfileService.profile(player);
        playerProfileService.activeAura(profile).ifPresent(auraId ->
            scheduler.runEntity(player, "cosmetic-aura-" + player.getUniqueId(), () -> playAura(player, auraId))
        );
    }

    public String cosmeticSummary(UnifiedPlayer profile) {
        return "Cosmetics | Title: " + playerProfileService.activeTitle(profile).map(NameFormat::friendly).orElse("None")
            + " | Trail: " + playerProfileService.activeTrail(profile).map(NameFormat::friendly).orElse("None")
            + " | Aura: " + playerProfileService.activeAura(profile).map(NameFormat::friendly).orElse("None")
            + " | Banner: " + playerProfileService.activeBanner(profile).map(NameFormat::friendly).orElse("None");
    }

    public ItemStack buildBannerItem(String bannerId) {
        Material material = switch (bannerId.toLowerCase(Locale.ROOT)) {
            case "frontier_standard" -> Material.BLUE_BANNER;
            case "breach_standard" -> Material.RED_BANNER;
            default -> Material.WHITE_BANNER;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(NameFormat.friendly(bannerId)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Prestige breach banner cosmetic."));
        lore.add(Component.text("Season reward that can be displayed in player housing or camps."));
        meta.lore(lore);
        if (meta instanceof BannerMeta bannerMeta) {
            item.setItemMeta(bannerMeta);
        } else {
            item.setItemMeta(meta);
        }
        return item;
    }

    private void tickTrails() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UnifiedPlayer profile = playerProfileService.profile(player);
            playerProfileService.activeTrail(profile).ifPresent(trailId ->
                scheduler.runEntity(player, "cosmetic-trail-" + player.getUniqueId(), () -> playTrail(player, trailId), () -> {
                })
            );
        }
    }

    private void playTrail(Player player, String trailId) {
        Location location = player.getLocation().add(0.0D, 0.15D, 0.0D);
        switch (trailId.toLowerCase(Locale.ROOT)) {
            case "starlit_stride" -> {
                player.getWorld().spawnParticle(Particle.END_ROD, location, 4, 0.22D, 0.05D, 0.22D, 0.01D);
                player.getWorld().spawnParticle(Particle.ENCHANT, location, 8, 0.28D, 0.08D, 0.28D, 0.01D);
            }
            case "verdant_wake" -> {
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 5, 0.25D, 0.07D, 0.25D, 0.02D);
                player.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, location, 2, 0.15D, 0.02D, 0.15D, 0.0D);
            }
            default -> {
            }
        }
    }

    private void playAura(Player player, String auraId) {
        Location location = player.getLocation().add(0.0D, 1.1D, 0.0D);
        switch (auraId.toLowerCase(Locale.ROOT)) {
            case "emberpulse" -> {
                player.getWorld().spawnParticle(Particle.FLAME, location, 18, 0.35D, 0.4D, 0.35D, 0.02D);
                player.getWorld().playSound(location, Sound.ENTITY_BLAZE_SHOOT, 0.7F, 1.1F);
            }
            case "astral_flare" -> {
                player.getWorld().spawnParticle(Particle.DUST, location, 16, 0.3D, 0.45D, 0.3D, 0.0D, new Particle.DustOptions(Color.fromRGB(133, 173, 255), 1.2F));
                player.getWorld().spawnParticle(Particle.ENCHANT, location, 12, 0.35D, 0.55D, 0.35D, 0.02D);
                player.getWorld().playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.35F);
            }
            default -> {
            }
        }
    }

}
