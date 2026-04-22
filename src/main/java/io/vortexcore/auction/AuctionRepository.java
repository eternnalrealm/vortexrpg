package io.vortexcore.auction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public final class AuctionRepository {

    private final Logger logger;
    private final Path storageFile;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock;
    private List<AuctionListing> listings;

    public AuctionRepository(Logger logger, Path dataFolder) {
        this.logger = logger;
        this.storageFile = dataFolder.resolve("auctions.json");
        this.objectMapper = new ObjectMapper();
        this.lock = new ReentrantReadWriteLock();
        this.listings = new ArrayList<>();
    }

    public void load() {
        lock.writeLock().lock();
        try {
            if (Files.exists(storageFile)) {
                listings = objectMapper.readValue(storageFile.toFile(), new TypeReference<List<AuctionListing>>() {});
                logger.info("Loaded " + listings.size() + " auction listing(s) from disk.");
            } else {
                listings = new ArrayList<>();
                logger.info("No auction data found — starting fresh.");
            }
        } catch (IOException exception) {
            logger.warning("Failed to load auctions: " + exception.getMessage());
            listings = new ArrayList<>();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void save() {
        lock.readLock().lock();
        try {
            Files.createDirectories(storageFile.getParent());
            Path tempFile = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), listings);
            Files.move(tempFile, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.warning("Failed to save auctions: " + exception.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addListing(AuctionListing listing) {
        lock.writeLock().lock();
        try {
            listings.add(listing);
        } finally {
            lock.writeLock().unlock();
        }
        save();
    }

    public Optional<AuctionListing> findById(UUID listingId) {
        lock.readLock().lock();
        try {
            return listings.stream().filter(l -> l.listingId().equals(listingId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean updateListing(AuctionListing updated) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < listings.size(); i++) {
                if (listings.get(i).listingId().equals(updated.listingId())) {
                    listings.set(i, updated);
                    save();
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeListing(UUID listingId) {
        lock.writeLock().lock();
        try {
            boolean removed = listings.removeIf(l -> l.listingId().equals(listingId));
            if (removed) save();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AuctionListing> activeListings() {
        lock.readLock().lock();
        try {
            return listings.stream().filter(AuctionListing::isActive).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AuctionListing> listingsBySeller(UUID sellerUuid) {
        lock.readLock().lock();
        try {
            return listings.stream().filter(l -> l.sellerUuid().equals(sellerUuid)).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AuctionListing> searchByName(String query) {
        String lower = query.toLowerCase();
        lock.readLock().lock();
        try {
            return listings.stream()
                .filter(AuctionListing::isActive)
                .filter(l -> l.displayName().toLowerCase().contains(lower) || l.itemId().toLowerCase().contains(lower))
                .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int purgeExpired() {
        lock.writeLock().lock();
        try {
            int before = listings.size();
            listings.removeIf(l -> l.isExpired() && !l.sold());
            int removed = before - listings.size();
            if (removed > 0) save();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() {
        save();
    }
}
