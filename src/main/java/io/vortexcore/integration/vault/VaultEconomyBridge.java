package io.vortexcore.integration.vault;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.world.ExpeditionCompletion;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultEconomyBridge {

    private final Logger logger;
    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final boolean enabled;
    private final RewardPolicy rewardPolicy;

    private volatile Economy economy;

    public VaultEconomyBridge(Logger logger, Plugin plugin, FoliaExecutionFacade scheduler, boolean enabled, RewardPolicy rewardPolicy) {
        this.logger = logger;
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.rewardPolicy = rewardPolicy;
    }

    public void refreshHook() {
        if (!enabled) {
            economy = null;
            return;
        }

        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            logger.info("Vault integration is enabled in config but Vault is not installed.");
            return;
        }

        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            economy = null;
            logger.info("Vault was found, but no economy provider is registered yet.");
            return;
        }

        economy = registration.getProvider();
        logger.info("Vault economy hooked into provider: " + providerName() + ".");
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isAvailable() {
        return enabled && economy != null;
    }

    public String providerName() {
        Economy current = economy;
        return current == null ? "Unavailable" : current.getName();
    }

    public String formatAmount(double amount) {
        Economy current = economy;
        if (current != null) {
            return current.format(amount);
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    /**
     * Returns the Vault balance for the given player.
     * <p><strong>Thread safety:</strong> Vault's {@code Economy.getBalance()} is not guaranteed
     * to be thread-safe. Call this method only from the global/main region thread.
     * For async contexts use {@link #depositAsync(Player, double)} instead, or wrap the
     * call in {@code scheduler.runGlobal()}.</p>
     */
    public double balance(OfflinePlayer player) {
        Economy current = economy;
        if (player == null || current == null) {
            return 0.0D;
        }
        return current.getBalance(player);
    }

    public CompletableFuture<OptionalDouble> rewardExpedition(Player player, ExpeditionCompletion completion) {
        if (completion == null) {
            return CompletableFuture.completedFuture(OptionalDouble.empty());
        }
        return depositAsync(player, rewardPolicy.expeditionReward(completion));
    }

    public CompletableFuture<OptionalDouble> rewardBreachCache(Player player, int experienceReward, long tokenReward) {
        return depositAsync(player, rewardPolicy.breachCacheReward(experienceReward, tokenReward));
    }

    public StatusSnapshot statusSnapshot() {
        return new StatusSnapshot(enabled, isAvailable(), providerName(), rewardPolicy);
    }

    private CompletableFuture<OptionalDouble> depositAsync(Player player, double amount) {
        Economy current = economy;
        if (player == null || !player.isOnline() || current == null || amount <= 0.0D) {
            return CompletableFuture.completedFuture(OptionalDouble.empty());
        }

        double normalized = rewardPolicy.normalize(amount);
        CompletableFuture<OptionalDouble> future = new CompletableFuture<>();
        scheduler.runGlobal("vault-deposit-" + player.getUniqueId(), () -> {
            Economy eco = economy;
            if (eco == null || !player.isOnline()) {
                future.complete(OptionalDouble.empty());
                return;
            }
            EconomyResponse response = eco.depositPlayer(player, normalized);
            if (!response.transactionSuccess()) {
                logger.warning("Vault deposit failed for " + player.getName() + ": " + response.errorMessage);
                future.complete(OptionalDouble.empty());
            } else {
                future.complete(OptionalDouble.of(normalized));
            }
        });
        return future;
    }

    public record RewardPolicy(
        double expeditionBaseReward,
        double expeditionScoreMultiplier,
        double breachCacheBaseReward,
        double breachTokenMultiplier,
        double breachExperienceMultiplier
    ) {

        public double expeditionReward(ExpeditionCompletion completion) {
            double tierBonus = switch (completion.scoreTier()) {
                case S -> 90.0D;
                case A -> 60.0D;
                case B -> 35.0D;
                case C -> 15.0D;
            };
            return normalize(expeditionBaseReward + tierBonus + (completion.finalScore() * expeditionScoreMultiplier));
        }

        public double breachCacheReward(int experienceReward, long tokenReward) {
            return normalize(
                breachCacheBaseReward
                    + (Math.max(0L, tokenReward) * breachTokenMultiplier)
                    + (Math.max(0, experienceReward) * breachExperienceMultiplier)
            );
        }

        private double normalize(double amount) {
            return Math.max(0.0D, Math.round(amount * 100.0D) / 100.0D);
        }
    }

    public record StatusSnapshot(
        boolean enabled,
        boolean available,
        String providerName,
        RewardPolicy rewardPolicy
    ) {

        public String describeLine() {
            if (!enabled) {
                return "Vault -> disabled in config";
            }
            if (!available) {
                return "Vault -> enabled, but no economy provider is currently hooked";
            }
            return "Vault -> hooked to " + providerName
                + " | expedition base " + rewardPolicy.expeditionBaseReward()
                + " | breach cache base " + rewardPolicy.breachCacheBaseReward();
        }
    }
}
