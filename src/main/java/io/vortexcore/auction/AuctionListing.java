package io.vortexcore.auction;

import java.util.UUID;

public record AuctionListing(
    UUID listingId,
    UUID sellerUuid,
    String sellerName,
    String itemId,
    String displayName,
    String material,
    int quantity,
    double price,
    long listedAtMillis,
    long expiresAtMillis,
    boolean sold,
    UUID buyerUuid
) {

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMillis && !sold;
    }

    public boolean isActive() {
        return !sold && !isExpired();
    }

    public AuctionListing markSold(UUID buyer) {
        return new AuctionListing(
            listingId, sellerUuid, sellerName, itemId, displayName, material,
            quantity, price, listedAtMillis, expiresAtMillis, true, buyer
        );
    }
}
