package io.vortexcore.auction;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.integration.vault.VaultEconomyBridge;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class AuctionService {

    private static final long DEFAULT_LISTING_DURATION_MILLIS = Duration.ofHours(48).toMillis();
    private static final int MAX_ACTIVE_LISTINGS_PER_PLAYER = 10;
    private static final double LISTING_TAX_RATE = 0.05;

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final MessageService messageService;
    private final AuctionRepository repository;
    private final VaultEconomyBridge vaultBridge;
    private VortexTask purgeTask;

    public AuctionService(
        Logger logger,
        FoliaExecutionFacade scheduler,
        MessageService messageService,
        AuctionRepository repository,
        VaultEconomyBridge vaultBridge
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.messageService = messageService;
        this.repository = repository;
        this.vaultBridge = vaultBridge;
    }

    public void start() {
        repository.load();
        if (purgeTask != null) return;
        long periodTicks = 20L * 60 * 5;
        purgeTask = scheduler.runGlobalTimer("auction-purge", periodTicks, periodTicks, ignored -> {
            int purged = repository.purgeExpired();
            if (purged > 0) {
                logger.info("Purged " + purged + " expired auction listing(s).");
            }
        });
    }

    public void stop() {
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        repository.close();
    }

    public AuctionRepository repository() {
        return repository;
    }

    public void createListing(Player seller, String itemId, String displayName, String material, int quantity, double price) {
        long activeCount = repository.listingsBySeller(seller.getUniqueId()).stream()
            .filter(AuctionListing::isActive).count();
        if (activeCount >= MAX_ACTIVE_LISTINGS_PER_PLAYER) {
            messageService.send(seller, "<red>You already have " + MAX_ACTIVE_LISTINGS_PER_PLAYER + " active listings.</red>");
            return;
        }

        if (price <= 0) {
            messageService.send(seller, "<red>Price must be positive.</red>");
            return;
        }

        double tax = price * LISTING_TAX_RATE;
        scheduler.runGlobal("auction-tax-" + seller.getUniqueId(), () -> {
            Economy eco = getEconomy();
            if (eco == null) {
                messageService.send(seller, "<red>Economy not available.</red>");
                return;
            }
            double balance = eco.getBalance(seller);
            if (balance < tax) {
                messageService.send(seller, "<red>Listing tax: " + vaultBridge.formatAmount(tax) + ". You don't have enough.</red>");
                return;
            }

            EconomyResponse taxResponse = eco.withdrawPlayer(seller, tax);
            if (!taxResponse.transactionSuccess()) {
                messageService.send(seller, "<red>Failed to collect listing tax.</red>");
                return;
            }

            long now = System.currentTimeMillis();
            AuctionListing listing = new AuctionListing(
                UUID.randomUUID(), seller.getUniqueId(), seller.getName(),
                itemId, displayName, material, quantity, price,
                now, now + DEFAULT_LISTING_DURATION_MILLIS, false, null
            );

            scheduler.runAsync("auction-save-" + listing.listingId(), () -> {
                repository.addListing(listing);
                messageService.send(seller, "<green>Listed " + displayName + " x" + quantity + " for " + vaultBridge.formatAmount(price) + ". Tax: " + vaultBridge.formatAmount(tax) + ".</green>");
                logger.info("Auction listing created: " + listing.listingId() + " by " + seller.getName() + " — " + itemId + " x" + quantity + " @ " + price);
            });
        });
    }

    public void buyListing(Player buyer, UUID listingId) {
        AuctionListing listing = repository.findById(listingId).orElse(null);
        if (listing == null || !listing.isActive()) {
            messageService.send(buyer, "<red>This listing is no longer available.</red>");
            return;
        }

        if (listing.sellerUuid().equals(buyer.getUniqueId())) {
            messageService.send(buyer, "<red>You can't buy your own listing.</red>");
            return;
        }

        scheduler.runGlobal("auction-buy-" + listingId, () -> {
            Economy eco = getEconomy();
            if (eco == null) {
                messageService.send(buyer, "<red>Economy not available.</red>");
                return;
            }

            double balance = eco.getBalance(buyer);
            if (balance < listing.price()) {
                messageService.send(buyer, "<red>You need " + vaultBridge.formatAmount(listing.price()) + " to buy this. Balance: " + vaultBridge.formatAmount(balance) + ".</red>");
                return;
            }

            EconomyResponse withdrawal = eco.withdrawPlayer(buyer, listing.price());
            if (!withdrawal.transactionSuccess()) {
                messageService.send(buyer, "<red>Payment failed.</red>");
                return;
            }

            AuctionListing soldListing = listing.markSold(buyer.getUniqueId());
            scheduler.runAsync("auction-finalize-" + listingId, () -> {
                boolean updated = repository.updateListing(soldListing);
                if (!updated) {
                    scheduler.runGlobal("auction-refund-" + buyer.getUniqueId(), () -> eco.depositPlayer(buyer, listing.price()));
                    messageService.send(buyer, "<red>This listing was already sold. You've been refunded.</red>");
                    return;
                }

                scheduler.runGlobal("auction-pay-seller-" + listing.sellerUuid(), () -> {
                    Player seller = Bukkit.getPlayer(listing.sellerUuid());
                    eco.depositPlayer(Bukkit.getOfflinePlayer(listing.sellerUuid()), listing.price());
                    if (seller != null && seller.isOnline()) {
                        messageService.send(seller, "<green>Your auction for " + listing.displayName() + " sold for " + vaultBridge.formatAmount(listing.price()) + "!</green>");
                    }
                });

                messageService.send(buyer, "<green>Purchased " + listing.displayName() + " x" + listing.quantity() + " for " + vaultBridge.formatAmount(listing.price()) + ".</green>");
                logger.info("Auction sale: " + listingId + " bought by " + buyer.getName() + " from " + listing.sellerName() + " for " + listing.price());
            });
        });
    }

    public void cancelListing(Player seller, UUID listingId) {
        AuctionListing listing = repository.findById(listingId).orElse(null);
        if (listing == null) {
            messageService.send(seller, "<red>Listing not found.</red>");
            return;
        }

        if (!listing.sellerUuid().equals(seller.getUniqueId())) {
            messageService.send(seller, "<red>You don't own this listing.</red>");
            return;
        }

        if (listing.sold()) {
            messageService.send(seller, "<red>This item has already been sold.</red>");
            return;
        }

        scheduler.runAsync("auction-cancel-" + listingId, () -> {
            repository.removeListing(listingId);
            messageService.send(seller, "<yellow>Listing for " + listing.displayName() + " cancelled. No tax refund.</yellow>");
        });
    }

    public List<AuctionListing> browse() {
        return repository.activeListings();
    }

    public List<AuctionListing> search(String query) {
        return repository.searchByName(query);
    }

    public List<AuctionListing> myListings(UUID sellerUuid) {
        return repository.listingsBySeller(sellerUuid);
    }

    private Economy getEconomy() {
        if (!vaultBridge.isAvailable()) return null;
        try {
            RegisteredServiceProvider<Economy> reg = Bukkit.getServicesManager().getRegistration(Economy.class);
            return reg != null ? reg.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
