package io.vortexcore.resourcepack;

import io.vortexcore.scheduling.FoliaExecutionFacade;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class VortexResourcePackService implements Listener {

    private final Logger logger;
    private final FoliaExecutionFacade scheduler;
    private final boolean enabled;
    private final boolean sendOnJoin;
    private final long sendDelayTicks;
    private final String url;
    private final byte[] sha1;
    private final boolean required;
    private final Component prompt;
    private final UUID packId;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> lastStatusByPlayer;
    private final Map<PlayerResourcePackStatusEvent.Status, AtomicInteger> statusCounts;

    public VortexResourcePackService(
        Logger logger,
        FoliaExecutionFacade scheduler,
        boolean enabled,
        boolean sendOnJoin,
        long sendDelayTicks,
        String url,
        String sha1Hex,
        boolean required,
        String prompt,
        String packId
    ) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.sendOnJoin = sendOnJoin;
        this.sendDelayTicks = Math.max(1L, sendDelayTicks);
        this.url = url == null ? "" : url.trim();
        this.sha1 = parseSha1(sha1Hex);
        this.required = required;
        this.prompt = prompt == null || prompt.isBlank() ? null : Component.text(prompt.trim());
        this.packId = resolvePackId(packId, this.url);
        this.lastStatusByPlayer = new ConcurrentHashMap<>();
        this.statusCounts = new ConcurrentHashMap<>();
        for (PlayerResourcePackStatusEvent.Status status : PlayerResourcePackStatusEvent.Status.values()) {
            this.statusCounts.put(status, new AtomicInteger());
        }
    }

    public StatusSnapshot statusSnapshot() {
        EnumMap<PlayerResourcePackStatusEvent.Status, Integer> counters = new EnumMap<>(PlayerResourcePackStatusEvent.Status.class);
        for (PlayerResourcePackStatusEvent.Status status : PlayerResourcePackStatusEvent.Status.values()) {
            AtomicInteger counter = statusCounts.get(status);
            counters.put(status, counter == null ? 0 : counter.get());
        }
        return new StatusSnapshot(
            enabled,
            isConfigured(),
            sendOnJoin,
            required,
            sha1 != null,
            sendDelayTicks,
            packId,
            url,
            prompt != null,
            Map.copyOf(counters),
            lastStatusByPlayer.size()
        );
    }

    public boolean isConfigured() {
        return !url.isBlank();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled || !sendOnJoin || !isConfigured()) {
            return;
        }

        Player player = event.getPlayer();
        scheduler.runEntity(
            player,
            "resource-pack-offer-" + player.getUniqueId(),
            sendDelayTicks,
            () -> offerPack(player),
            () -> lastStatusByPlayer.remove(player.getUniqueId())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastStatusByPlayer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled || !isConfigured() || !packId.equals(event.getID())) {
            return;
        }

        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        lastStatusByPlayer.put(player.getUniqueId(), status);
        AtomicInteger counter = statusCounts.get(status);
        if (counter != null) {
            counter.incrementAndGet();
        }

        switch (status) {
            case DECLINED -> logger.warning("Player " + player.getName() + " declined the VortexRPG resource pack.");
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> logger.warning(
                "Player " + player.getName() + " failed VortexRPG resource pack delivery with status " + status + "."
            );
            case SUCCESSFULLY_LOADED -> logger.info("Player " + player.getName() + " loaded the VortexRPG resource pack.");
            default -> {
            }
        }
    }

    private void offerPack(Player player) {
        if (!enabled || !isConfigured() || !player.isOnline()) {
            return;
        }
        player.setResourcePack(packId, url, sha1, prompt, required);
    }

    private UUID resolvePackId(String configuredPackId, String packUrl) {
        if (configuredPackId != null && !configuredPackId.isBlank()) {
            try {
                return UUID.fromString(configuredPackId.trim());
            } catch (IllegalArgumentException exception) {
                logger.warning("resource-pack.pack-id is invalid. Falling back to URL-derived pack id.");
            }
        }
        return UUID.nameUUIDFromBytes(packUrl.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] parseSha1(String sha1Hex) {
        if (sha1Hex == null || sha1Hex.isBlank()) {
            return null;
        }

        String clean = sha1Hex.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[0-9a-f]{40}")) {
            logger.warning("resource-pack.sha1 is invalid. Expected a 40-character SHA-1 hex string.");
            return null;
        }

        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) {
            int start = index * 2;
            bytes[index] = (byte) Integer.parseInt(clean.substring(start, start + 2), 16);
        }
        return bytes;
    }

    public record StatusSnapshot(
        boolean enabled,
        boolean configured,
        boolean sendOnJoin,
        boolean required,
        boolean hasHash,
        long sendDelayTicks,
        UUID packId,
        String url,
        boolean hasPrompt,
        Map<PlayerResourcePackStatusEvent.Status, Integer> statusCounts,
        int trackedPlayers
    ) {

        public String describeLine() {
            if (!enabled) {
                return "Resource Pack -> disabled";
            }
            if (!configured) {
                return "Resource Pack -> enabled but waiting for a configured URL";
            }
            return "Resource Pack -> auto-send: "
                + (sendOnJoin ? "on" : "off")
                + ", required: "
                + (required ? "yes" : "no")
                + ", hash: "
                + (hasHash ? "set" : "missing")
                + ", prompt: "
                + (hasPrompt ? "set" : "none")
                + ", delay: "
                + sendDelayTicks
                + " ticks"
                + ", pack-id: "
                + packId
                + ", url: "
                + summarizeUrl(url);
        }

        private static String summarizeUrl(String url) {
            if (url == null || url.isBlank()) {
                return "not set";
            }
            return url.length() > 72 ? url.substring(0, 69) + "..." : url;
        }
    }
}
