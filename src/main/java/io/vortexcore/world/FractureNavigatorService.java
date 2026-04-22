package io.vortexcore.world;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import io.vortexcore.support.NameFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

public final class FractureNavigatorService {

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final WorldThreatManager worldThreatManager;

    public FractureNavigatorService(Plugin plugin, FoliaExecutionFacade scheduler, WorldThreatManager worldThreatManager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.worldThreatManager = worldThreatManager;
    }

    public List<NamedFracture> activeNamedFractures() {
        return worldThreatManager.activeFractures().stream()
            .map(snapshot -> new NamedFracture(snapshot.name(), snapshot))
            .sorted(Comparator.comparing(NamedFracture::name))
            .toList();
    }

    public Optional<NamedFracture> findByName(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return activeNamedFractures().stream()
            .filter(fracture -> fracture.name().equalsIgnoreCase(normalized))
            .findFirst();
    }

    public Optional<NamedFracture> nearestFor(Player player) {
        World world = player.getWorld();
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        List<FractureRegionSnapshot> sameWorld = worldThreatManager.activeFractures().stream()
            .filter(snapshot -> world.getUID().equals(snapshot.key().worldId()))
            .toList();
        if (!sameWorld.isEmpty()) {
            return sameWorld.stream()
                .min(Comparator
                    .comparingInt((FractureRegionSnapshot snapshot) -> snapshot.state().severity()).reversed()
                    .thenComparingLong(snapshot -> chunkDistanceSquared(playerChunkX, playerChunkZ, snapshot))
                )
                .map(snapshot -> new NamedFracture(snapshot.name(), snapshot));
        }

        return worldThreatManager.activeFractures().stream()
            .min(Comparator
                .comparingInt((FractureRegionSnapshot snapshot) -> snapshot.state().severity()).reversed()
                .thenComparingDouble(FractureRegionSnapshot::threatScore).reversed()
                .thenComparing(FractureRegionSnapshot::name)
            )
            .map(snapshot -> new NamedFracture(snapshot.name(), snapshot));
    }

    public CompletableFuture<ItemStack> createMap(Player player, NamedFracture fracture) {
        CompletableFuture<ItemStack> future = new CompletableFuture<>();
        World world = Bukkit.getWorld(fracture.snapshot().key().worldId());
        if (world == null) {
            future.completeExceptionally(new IllegalStateException("Fracture world is not loaded."));
            return future;
        }

        scheduler.runGlobal("fracture-map-create", () -> {
            MapView mapView = Bukkit.createMap(world);
            mapView.getRenderers().forEach(mapView::removeRenderer);
            mapView.setTrackingPosition(true);
            mapView.setLocked(true);
            mapView.setUnlimitedTracking(false);

            int targetX = blockX(fracture.snapshot());
            int targetZ = blockZ(fracture.snapshot());
            Location playerLocation = player.getLocation();
            boolean sameWorld = playerLocation.getWorld() != null && playerLocation.getWorld().getUID().equals(world.getUID());
            int centerX = sameWorld ? (playerLocation.getBlockX() + targetX) / 2 : targetX;
            int centerZ = sameWorld ? (playerLocation.getBlockZ() + targetZ) / 2 : targetZ;
            MapView.Scale scale = sameWorld
                ? chooseScale(playerLocation.getBlockX(), playerLocation.getBlockZ(), targetX, targetZ)
                : MapView.Scale.NORMAL;
            mapView.setCenterX(centerX);
            mapView.setCenterZ(centerZ);
            mapView.setScale(scale);
            mapView.addRenderer(new FractureMapRenderer(fracture, targetX, targetZ));

            ItemStack item = new ItemStack(Material.FILLED_MAP);
            ItemMeta itemMeta = item.getItemMeta();
            if (!(itemMeta instanceof MapMeta meta)) {
                future.completeExceptionally(new IllegalStateException("Map metadata is unavailable."));
                return;
            }
            meta.setMapView(mapView);
            meta.displayName(Component.text("Fracture Map: " + fracture.name()));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("State: " + NameFormat.friendly(fracture.snapshot().state().name())));
            lore.add(Component.text("World: " + world.getName()));
            lore.add(Component.text("Chunk: " + fracture.snapshot().key().chunkX() + ", " + fracture.snapshot().key().chunkZ()));
            lore.add(Component.text("Blocks: " + targetX + ", " + targetZ));
            lore.add(Component.text(sameWorld
                ? "Follow the red zone to the fracture."
                : "Enter the fracture realm first, then follow the red zone."
            ));
            meta.lore(lore);
            item.setItemMeta(meta);
            future.complete(item);
        });
        return future;
    }

    public CompletableFuture<Location> teleportAnchor(NamedFracture fracture) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        World world = Bukkit.getWorld(fracture.snapshot().key().worldId());
        if (world == null) {
            future.completeExceptionally(new IllegalStateException("Fracture world is not loaded."));
            return future;
        }

        int blockX = blockX(fracture.snapshot());
        int blockZ = blockZ(fracture.snapshot());
        scheduler.runRegion(world, fracture.snapshot().key().chunkX(), fracture.snapshot().key().chunkZ(), "fracture-teleport-anchor", () -> {
            int safeY = Math.max(world.getMinHeight() + 1, world.getHighestBlockYAt(blockX, blockZ) + 1);
            future.complete(new Location(world, blockX + 0.5D, safeY, blockZ + 0.5D));
        });
        return future;
    }

    public String nameFor(FractureRegionSnapshot snapshot) {
        return snapshot.name();
    }

    private long chunkDistanceSquared(int playerChunkX, int playerChunkZ, FractureRegionSnapshot snapshot) {
        long dx = playerChunkX - snapshot.key().chunkX();
        long dz = playerChunkZ - snapshot.key().chunkZ();
        return (dx * dx) + (dz * dz);
    }

    private int blockX(FractureRegionSnapshot snapshot) {
        return (snapshot.key().chunkX() << 4) + 8;
    }

    private int blockZ(FractureRegionSnapshot snapshot) {
        return (snapshot.key().chunkZ() << 4) + 8;
    }

    private MapView.Scale chooseScale(int playerX, int playerZ, int targetX, int targetZ) {
        int maxDistance = Math.max(Math.abs(playerX - targetX), Math.abs(playerZ - targetZ));
        if (maxDistance <= 48) {
            return MapView.Scale.CLOSEST;
        }
        if (maxDistance <= 96) {
            return MapView.Scale.CLOSE;
        }
        if (maxDistance <= 192) {
            return MapView.Scale.NORMAL;
        }
        if (maxDistance <= 384) {
            return MapView.Scale.FAR;
        }
        return MapView.Scale.FARTHEST;
    }

    public record NamedFracture(String name, FractureRegionSnapshot snapshot) {
    }

    private static final class FractureMapRenderer extends MapRenderer {

        private final NamedFracture fracture;
        private final int targetBlockX;
        private final int targetBlockZ;
        private boolean rendered;

        private FractureMapRenderer(NamedFracture fracture, int targetBlockX, int targetBlockZ) {
            super(true);
            this.fracture = fracture;
            this.targetBlockX = targetBlockX;
            this.targetBlockZ = targetBlockZ;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (rendered) {
                return;
            }

            rendered = true;
            int pixelX = toPixel(targetBlockX, map.getCenterX(), blocksPerPixel(map.getScale()));
            int pixelZ = toPixel(targetBlockZ, map.getCenterZ(), blocksPerPixel(map.getScale()));
            int radius = 5 + fracture.snapshot().state().severity();
            for (int x = pixelX - radius; x <= pixelX + radius; x++) {
                for (int z = pixelZ - radius; z <= pixelZ + radius; z++) {
                    if (x < 0 || x > 127 || z < 0 || z > 127) {
                        continue;
                    }
                    int dx = x - pixelX;
                    int dz = z - pixelZ;
                    double distance = Math.sqrt((dx * dx) + (dz * dz));
                    if (distance <= radius) {
                        canvas.setPixelColor(x, z, distance >= radius - 1 ? new Color(120, 0, 0) : new Color(220, 20, 20));
                    }
                }
            }
        }

        private int blocksPerPixel(MapView.Scale scale) {
            return switch (scale) {
                case CLOSEST -> 1;
                case CLOSE -> 2;
                case NORMAL -> 4;
                case FAR -> 8;
                case FARTHEST -> 16;
            };
        }

        private int toPixel(int block, int center, int blocksPerPixel) {
            return 64 + Math.round((block - center) / (float) blocksPerPixel);
        }
    }
}
