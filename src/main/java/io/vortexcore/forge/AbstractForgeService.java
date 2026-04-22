package io.vortexcore.forge;


import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Base class for all forge services. Provides:
 * <ul>
 *   <li>Shared {@code pendingInputs} map for chat-based field editing</li>
 *   <li>Unified {@code onAsyncChat} handler (thread-safe, race-free via ForgeInputGuard)</li>
 *   <li>Shared {@code promptForChat} entry point</li>
 *   <li>Shared {@code icon()} helper for building ItemStack buttons</li>
 * </ul>
 *
 * @param <T> the editable entity type (EditableSpell, EditableEffect, EditableItem)
 * @param <F> the ChatField enum type specific to each forge
 */
public abstract class AbstractForgeService<T, F extends Enum<F>> implements Listener {

    protected final String forgeId;
    protected final FoliaExecutionFacade scheduler;
    protected final VortexMenuBuilder menuBuilder;
    protected final MessageService messageService;
    protected final ForgeInputGuard forgeInputGuard;
    protected final ConcurrentMap<UUID, GenericPendingInput<T, F>> pendingInputs;

    protected AbstractForgeService(
        String forgeId,
        FoliaExecutionFacade scheduler,
        VortexMenuBuilder menuBuilder,
        MessageService messageService,
        ForgeInputGuard forgeInputGuard
    ) {
        this.forgeId = forgeId;
        this.scheduler = scheduler;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
        this.forgeInputGuard = forgeInputGuard;
        this.pendingInputs = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!forgeInputGuard.owns(player.getUniqueId(), forgeId)) {
            return;
        }
        GenericPendingInput<T, F> pending = pendingInputs.remove(player.getUniqueId());
        forgeInputGuard.release(player.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage().trim();
        scheduler.runEntity(player, forgeId + "-chat-" + player.getUniqueId(), () -> handleChatInput(player, pending, input));
    }

    protected void promptForChat(Player player, T entity, F field, String prompt) {
        forgeInputGuard.claim(player.getUniqueId(), forgeId);
        pendingInputs.put(player.getUniqueId(), new GenericPendingInput<>(entity, field));
        messageService.send(player, prompt);
    }

    protected abstract void handleChatInput(Player player, GenericPendingInput<T, F> pending, String input);

    protected static ItemStack icon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }
}
