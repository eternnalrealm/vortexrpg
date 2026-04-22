package io.vortexcore.auction;

import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PERM_BROWSE = "vortex.player.auction.browse";
    private static final String PERM_SELL = "vortex.player.auction.sell";
    private static final String PERM_MY = "vortex.player.auction.my";
    private static final String PERM_SEARCH = "vortex.player.auction.search";

    private final AuctionService auctionService;
    private final VortexMenuBuilder menuBuilder;
    private final MessageService messageService;

    public AuctionCommand(AuctionService auctionService, VortexMenuBuilder menuBuilder, MessageService messageService) {
        this.auctionService = auctionService;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "browse";

        switch (sub) {
            case "browse", "open" -> {
                if (!player.hasPermission(PERM_BROWSE)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                openBrowseMenu(player, auctionService.browse(), "Auction House", 0);
            }
            case "sell" -> {
                if (!player.hasPermission(PERM_SELL)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /auction sell <price></red>");
                    messageService.send(player, "<gray>Hold the item you want to sell in your main hand.</gray>");
                    return true;
                }
                handleSell(player, args[1]);
            }
            case "my" -> {
                if (!player.hasPermission(PERM_MY)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                openMyListings(player);
            }
            case "search" -> {
                if (!player.hasPermission(PERM_SEARCH)) {
                    messageService.send(player, "<red>No permission.</red>");
                    return true;
                }
                if (args.length < 2) {
                    messageService.send(player, "<red>Usage: /auction search <query></red>");
                    return true;
                }
                String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                List<AuctionListing> results = auctionService.search(query);
                if (results.isEmpty()) {
                    messageService.send(player, "<gray>No results for '" + query + "'.</gray>");
                } else {
                    openBrowseMenu(player, results, "Search: " + query, 0);
                }
            }
            default -> messageService.send(player, "<red>Usage: /auction [browse|sell|search|my]</red>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("browse", "sell", "search", "my"), args[0]);
        }
        return List.of();
    }

    private void openBrowseMenu(Player player, List<AuctionListing> listings, String title, int page) {
        int pageSize = 21;
        int totalPages = Math.max(1, (int) Math.ceil((double) listings.size() / pageSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int start = safePage * pageSize;
        int end = Math.min(start + pageSize, listings.size());

        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
            Component.text(title + " (Page " + (safePage + 1) + "/" + totalPages + ")"), 6);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = start; i < end && (i - start) < slots.length; i++) {
            AuctionListing listing = listings.get(i);
            int slot = slots[i - start];

            Material mat;
            try {
                mat = Material.valueOf(listing.material().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                mat = Material.PAPER;
            }

            long hoursLeft = Math.max(0, (listing.expiresAtMillis() - System.currentTimeMillis()) / 3600000L);

            ItemStack icon = buildIcon(mat, listing.displayName(), List.of(
                "<gray>Seller:</gray> <white>" + listing.sellerName() + "</white>",
                "<gray>Quantity:</gray> <yellow>" + listing.quantity() + "</yellow>",
                "<gray>Price:</gray> <green>" + String.format(Locale.US, "%.2f", listing.price()) + "</green>",
                "<gray>Expires in:</gray> <aqua>" + hoursLeft + "h</aqua>",
                "",
                "<yellow>Click to buy!</yellow>"
            ));

            builder.button(slot, icon, context -> {
                auctionService.buyListing(context.player(), listing.listingId());
                return context.close();
            });
        }

        if (safePage > 0) {
            builder.button(45, buildIcon(Material.ARROW, "<yellow>Previous Page</yellow>", List.of()), context -> {
                openBrowseMenu(player, listings, title, safePage - 1);
                return CompletableFuture.completedFuture(null);
            });
        }

        if (safePage < totalPages - 1) {
            builder.button(53, buildIcon(Material.ARROW, "<yellow>Next Page</yellow>", List.of()), context -> {
                openBrowseMenu(player, listings, title, safePage + 1);
                return CompletableFuture.completedFuture(null);
            });
        }

        if (listings.isEmpty()) {
            builder.button(22, buildIcon(Material.STRUCTURE_VOID, "No Listings", List.of("The auction house is empty.")),
                context -> CompletableFuture.completedFuture(null));
        }

        menuBuilder.open(player, builder.build());
    }

    private void openMyListings(Player player) {
        List<AuctionListing> mine = auctionService.myListings(player.getUniqueId());
        if (mine.isEmpty()) {
            messageService.send(player, "<gray>You have no auction listings.</gray>");
            return;
        }

        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("My Listings"), 4);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

        int index = 0;
        for (AuctionListing listing : mine) {
            if (index >= slots.length) break;
            int slot = slots[index++];

            Material mat;
            try {
                mat = Material.valueOf(listing.material().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                mat = Material.PAPER;
            }

            String status = listing.sold() ? "<green>SOLD</green>" : listing.isExpired() ? "<red>EXPIRED</red>" : "<yellow>ACTIVE</yellow>";
            ItemStack icon = buildIcon(mat, listing.displayName(), List.of(
                "Status: " + status,
                "Price: " + String.format(Locale.US, "%.2f", listing.price()),
                "Quantity: " + listing.quantity(),
                listing.isActive() ? "<red>Click to cancel</red>" : ""
            ));

            builder.button(slot, icon, context -> {
                if (listing.isActive()) {
                    auctionService.cancelListing(context.player(), listing.listingId());
                }
                return context.close();
            });
        }

        menuBuilder.open(player, builder.build());
    }

    private void handleSell(Player player, String priceStr) {
        double price;
        try {
            price = Double.parseDouble(priceStr);
        } catch (NumberFormatException ex) {
            messageService.send(player, "<red>Invalid price.</red>");
            return;
        }
        if (price <= 0) {
            messageService.send(player, "<red>Price must be positive.</red>");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || held.getAmount() == 0) {
            messageService.send(player, "<red>Hold an item in your main hand to sell it.</red>");
            return;
        }

        String material = held.getType().name();
        String displayName = held.hasItemMeta() && held.getItemMeta().hasDisplayName()
            ? MINI.serialize(held.getItemMeta().displayName())
            : held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String itemId = held.getType().name().toLowerCase(Locale.ROOT);
        int quantity = held.getAmount();

        player.getInventory().setItemInMainHand(null);
        auctionService.createListing(player, itemId, displayName, material, quantity, price);
    }

    private ItemStack buildIcon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize(name));
        meta.lore(loreLines.stream()
            .filter(line -> !line.isEmpty())
            .map(line -> (Component) MINI.deserialize(line))
            .toList());
        item.setItemMeta(meta);
        return item;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
