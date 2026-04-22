package io.vortexcore.ui;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class VortexMenuBuilder implements Listener {

    private final Plugin plugin;
    private final FoliaExecutionFacade scheduler;
    private final Duration clickCooldown;
    private final ConcurrentMap<UUID, MenuSession> sessions;

    public VortexMenuBuilder(Plugin plugin, FoliaExecutionFacade scheduler, Duration clickCooldown) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.clickCooldown = clickCooldown;
        this.sessions = new ConcurrentHashMap<>();
    }

    public MenuDefinition.Builder menu(Component title, int rows) {
        return new MenuDefinition.Builder(title, rows);
    }

    public void open(Player player, MenuDefinition definition) {
        scheduler.runEntity(player, "menu-open-" + player.getUniqueId(), () -> {
            sessions.entrySet().removeIf(entry -> entry.getValue().viewerId().equals(player.getUniqueId()));

            UUID sessionId = UUID.randomUUID();
            MenuHolder holder = new MenuHolder(sessionId);
            Inventory inventory = Bukkit.createInventory(holder, definition.size(), definition.title());
            holder.bind(inventory);
            render(inventory, definition);

            MenuSession session = new MenuSession(
                sessionId,
                player.getUniqueId(),
                inventory,
                definition,
                new AtomicBoolean(),
                new AtomicLong()
            );

            sessions.put(sessionId, session);
            player.openInventory(inventory);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        MenuSession session = findSession(event.getView().getTopInventory());
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        if (!session.viewerId().equals(player.getUniqueId())) {
            return;
        }

        MenuButton button = session.definition().buttons().get(event.getRawSlot());
        if (button == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - session.lastClickAt().get()) < clickCooldown.toMillis()) {
            return;
        }

        if (!session.busy().compareAndSet(false, true)) {
            return;
        }

        session.lastClickAt().set(now);

        scheduler.runAsync("menu-click-" + player.getUniqueId(), () -> executeButton(player, session, button, event.getRawSlot()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (findSession(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        MenuSession session = findSession(event.getInventory());
        if (session != null && session.viewerId().equals(event.getPlayer().getUniqueId())) {
            sessions.remove(session.sessionId(), session);
        }
    }

    private void executeButton(Player player, MenuSession session, MenuButton button, int slot) {
        try {
            MenuClickContext context = new MenuClickContext(plugin, scheduler, this, player, session, slot);
            CompletionStage<Void> stage = button.action().execute(context);
            stage.whenComplete((ignored, error) -> {
                session.busy().set(false);

                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Unhandled menu action failure", error);
                    return;
                }

                if (button.closeAfterClick()) {
                    scheduler.runEntity(player, "menu-close-" + player.getUniqueId(), player::closeInventory);
                }
            });
        } catch (Throwable throwable) {
            session.busy().set(false);
            plugin.getLogger().log(Level.SEVERE, "Unhandled menu click failure", throwable);
        }
    }

    private void render(Inventory inventory, MenuDefinition definition) {
        inventory.clear();
        definition.buttons().forEach((slot, button) -> inventory.setItem(slot, button.icon().clone()));
    }

    private MenuSession findSession(Inventory inventory) {
        if (!(inventory.getHolder() instanceof MenuHolder holder)) {
            return null;
        }
        return sessions.get(holder.sessionId());
    }

    private void redraw(Player player, MenuSession session) {
        scheduler.runEntity(player, "menu-redraw-" + player.getUniqueId(), () -> render(session.inventory(), session.definition()));
    }

    public record MenuDefinition(Component title, int size, Map<Integer, MenuButton> buttons) {

        public static final class Builder {

            private final Component title;
            private final int size;
            private final Map<Integer, MenuButton> buttons;

            private Builder(Component title, int rows) {
                this.title = title;
                this.size = Math.max(9, Math.min(54, rows * 9));
                this.buttons = new LinkedHashMap<>();
            }

            public Builder button(int slot, ItemStack icon, MenuAction action) {
                return button(slot, icon, action, false);
            }

            public Builder button(int slot, ItemStack icon, MenuAction action, boolean closeAfterClick) {
                buttons.put(slot, new MenuButton(icon.clone(), action, closeAfterClick));
                return this;
            }

            public MenuDefinition build() {
                return new MenuDefinition(title, size, Map.copyOf(buttons));
            }
        }
    }

    public record MenuButton(ItemStack icon, MenuAction action, boolean closeAfterClick) {
    }

    @FunctionalInterface
    public interface MenuAction {

        CompletionStage<Void> execute(MenuClickContext context);
    }

    public static final class MenuClickContext {

        private final Plugin plugin;
        private final FoliaExecutionFacade scheduler;
        private final VortexMenuBuilder menuService;
        private final Player player;
        private final MenuSession session;
        private final int slot;

        private MenuClickContext(
            Plugin plugin,
            FoliaExecutionFacade scheduler,
            VortexMenuBuilder menuService,
            Player player,
            MenuSession session,
            int slot
        ) {
            this.plugin = plugin;
            this.scheduler = scheduler;
            this.menuService = menuService;
            this.player = player;
            this.session = session;
            this.slot = slot;
        }

        public Plugin plugin() {
            return plugin;
        }

        public Player player() {
            return player;
        }

        public int slot() {
            return slot;
        }

        public void redraw() {
            menuService.redraw(player, session);
        }

        public void open(MenuDefinition definition) {
            menuService.open(player, definition);
        }

        public CompletableFuture<Void> close() {
            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.runEntity(player, "menu-context-close-" + player.getUniqueId(), () -> {
                player.closeInventory();
                future.complete(null);
            });
            return future;
        }
    }

    private record MenuSession(
        UUID sessionId,
        UUID viewerId,
        Inventory inventory,
        MenuDefinition definition,
        AtomicBoolean busy,
        AtomicLong lastClickAt
    ) {
    }

    private static final class MenuHolder implements InventoryHolder {

        private final UUID sessionId;
        private Inventory inventory;

        private MenuHolder(UUID sessionId) {
            this.sessionId = sessionId;
        }

        private UUID sessionId() {
            return sessionId;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
