package io.vortexcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Centralised plugin message prefix. All player-facing chat messages
 * should go through this service so the prefix is consistent and
 * editable from config.yml.
 */
public final class MessageService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Component prefix;
    private final String rawPrefix;

    public MessageService(String miniMessagePrefix) {
        this.rawPrefix = miniMessagePrefix == null || miniMessagePrefix.isBlank()
            ? "<gradient:#8B5CF6:#3B82F6>VortexRPG</gradient> <dark_gray>»</dark_gray> "
            : miniMessagePrefix;
        this.prefix = MINI.deserialize(this.rawPrefix);
    }

    /** Send a message with the configured prefix to any sender. Supports MiniMessage tags and legacy §-codes. */
    public void send(CommandSender sender, String message) {
        sender.sendMessage(prefix.append(deserialize(message)));
    }

    /** Send a pre-built Component with the configured prefix. */
    public void send(CommandSender sender, Component body) {
        sender.sendMessage(prefix.append(body));
    }

    /** Send a transient action-bar message (no prefix). Action bar overwrites itself, so it never spams. */
    public void actionBar(Player player, String message) {
        if (player == null || message == null) return;
        player.sendActionBar(deserialize(message));
    }

    /** Return the prefix as a Component (for services that build their own messages). */
    public Component prefix() {
        return prefix;
    }

    /** Return the raw MiniMessage prefix string. */
    public String rawPrefix() {
        return rawPrefix;
    }

    private Component deserialize(String message) {
        return message.indexOf('\u00a7') >= 0 ? LEGACY.deserialize(message) : MINI.deserialize(message);
    }
}
