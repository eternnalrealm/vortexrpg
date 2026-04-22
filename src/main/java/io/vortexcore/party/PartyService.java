package io.vortexcore.party;

import io.vortexcore.scheduling.VortexTask;
import io.vortexcore.scheduling.FoliaExecutionFacade;
import io.vortexcore.ui.MessageService;
import io.vortexcore.world.ExpeditionPartyManager;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class PartyService implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final long READY_CHECK_TIMEOUT_MS = 30_000L;
    private static final long SUMMON_TIMEOUT_MS = 30_000L;

    private final FoliaExecutionFacade scheduler;
    private final MessageService messageService;
    private final ExpeditionPartyManager partyManager;
    private final ConcurrentHashMap<UUID, ReadyCheckState> readyChecks;
    private final ConcurrentHashMap<UUID, SummonState> pendingSummons;
    private final Set<UUID> partyChatToggle;
    private VortexTask tickTask;

    public PartyService(
        Plugin plugin,
        Logger logger,
        FoliaExecutionFacade scheduler,
        MessageService messageService,
        ExpeditionPartyManager partyManager
    ) {
        this.scheduler = scheduler;
        this.messageService = messageService;
        this.partyManager = partyManager;
        this.readyChecks = new ConcurrentHashMap<>();
        this.pendingSummons = new ConcurrentHashMap<>();
        this.partyChatToggle = ConcurrentHashMap.newKeySet();
    }

    public void start() {
        if (tickTask != null) return;
        tickTask = scheduler.runGlobalTimer("party-tick", 20L, 20L, ignored -> tick());
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        readyChecks.clear();
        pendingSummons.clear();
        partyChatToggle.clear();
    }



    private void tick() {
        tickHud();
        tickReadyChecks();
        tickSummons();
    }



    private void tickHud() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(player.getUniqueId());
            if (partyOpt.isEmpty()) continue;
            ExpeditionPartyManager.PartySnapshot party = partyOpt.get();
            if (party.members().size() <= 1) continue;

            StringBuilder sb = new StringBuilder();
            for (UUID memberId : party.members()) {
                if (memberId.equals(player.getUniqueId())) continue;
                Player member = Bukkit.getPlayer(memberId);
                if (member == null || !member.isOnline()) continue;

                if (!sb.isEmpty()) sb.append(" <dark_gray>|</dark_gray> ");

                double hp = member.getHealth();
                double max = 20.0;
                AttributeInstance maxAttr = member.getAttribute(Attribute.MAX_HEALTH);
                if (maxAttr != null) {
                    max = maxAttr.getValue();
                }
                float ratio = max > 0 ? (float) (hp / max) : 0F;
                String color;
                if (ratio > 0.5F) color = "<green>";
                else if (ratio > 0.25F) color = "<yellow>";
                else color = "<red>";
                String endColor = color.replace("<", "</");

                PartyRole role = party.roles().getOrDefault(memberId, PartyRole.MEMBER);
                String rolePrefix = role.symbol().isEmpty() ? "" : role.symbol() + " ";

                sb.append(rolePrefix)
                    .append(color)
                    .append(member.getName())
                    .append(" ❤ ")
                    .append(String.format("%.0f", hp)).append("/").append(String.format("%.0f", max))
                    .append(endColor);
            }

            if (!sb.isEmpty()) {
                player.sendActionBar(MINI.deserialize(sb.toString()));
            }
        }
    }



    public boolean isPartyChatToggled(UUID playerId) {
        return partyChatToggle.contains(playerId);
    }

    public void togglePartyChat(Player player) {
        if (partyChatToggle.remove(player.getUniqueId())) {
            messageService.send(player, "<gray>Party chat toggled <red>off</red>.</gray>");
        } else {
            partyChatToggle.add(player.getUniqueId());
            messageService.send(player, "<gray>Party chat toggled <green>on</green>. All messages go to party.</gray>");
        }
    }

    public void sendPartyMessage(Player sender, String message) {
        Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(sender.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(sender, "<red>You are not in a party.</red>");
            return;
        }

        ExpeditionPartyManager.PartySnapshot party = partyOpt.get();
        PartyRole role = party.roles().getOrDefault(sender.getUniqueId(), PartyRole.MEMBER);
        String formatted = "<dark_purple>[Party]</dark_purple> " + role.chatPrefix()
            + "<white>" + sender.getName() + "</white><dark_gray>:</dark_gray> <light_purple>" + message + "</light_purple>";

        broadcastToParty(party, formatted);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!partyChatToggle.contains(player.getUniqueId())) return;

        Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            partyChatToggle.remove(player.getUniqueId());
            return;
        }

        event.setCancelled(true);

        String raw = event.getMessage();
        if (raw.isEmpty()) return;

        sendPartyMessage(player, raw);
    }



    public void startReadyCheck(Player leader) {
        Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(leader.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(leader, "<red>You are not in a party.</red>");
            return;
        }

        ExpeditionPartyManager.PartySnapshot party = partyOpt.get();
        if (!party.leaderId().equals(leader.getUniqueId())) {
            messageService.send(leader, "<red>Only the party leader can start a ready check.</red>");
            return;
        }
        if (party.members().size() <= 1) {
            messageService.send(leader, "<red>You need at least 2 party members for a ready check.</red>");
            return;
        }
        if (readyChecks.containsKey(party.partyId())) {
            messageService.send(leader, "<red>A ready check is already in progress.</red>");
            return;
        }

        ConcurrentHashMap<UUID, Boolean> responses = new ConcurrentHashMap<>();
        responses.put(leader.getUniqueId(), true);
        ReadyCheckState state = new ReadyCheckState(
            party.partyId(), leader.getUniqueId(), responses,
            System.currentTimeMillis() + READY_CHECK_TIMEOUT_MS
        );
        readyChecks.put(party.partyId(), state);

        broadcastToParty(party, "<gold>[Ready Check]</gold> <yellow>" + leader.getName()
            + " started a ready check! Use <white>/party ready accept</white> or <white>/party ready decline</white>.</yellow>");
    }

    public void respondReady(Player player, boolean accept) {
        Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(player, "<red>You are not in a party.</red>");
            return;
        }

        ReadyCheckState state = readyChecks.get(partyOpt.get().partyId());
        if (state == null) {
            messageService.send(player, "<red>No active ready check.</red>");
            return;
        }

        if (state.responses().containsKey(player.getUniqueId())) {
            messageService.send(player, "<red>You already responded to this ready check.</red>");
            return;
        }

        state.responses().put(player.getUniqueId(), accept);
        String status = accept ? "<green>✓ Ready</green>" : "<red>✗ Not Ready</red>";
        broadcastToParty(partyOpt.get(), "<gold>[Ready Check]</gold> <white>" + player.getName() + "</white> — " + status);

        checkReadyComplete(partyOpt.get(), state);
    }

    private void tickReadyChecks() {
        long now = System.currentTimeMillis();
        readyChecks.entrySet().removeIf(entry -> {
            ReadyCheckState state = entry.getValue();
            if (now < state.expiresAt()) return false;

            UUID partyId = entry.getKey();
            findPartyById(partyId).ifPresent(party -> broadcastReadyResults(party, state, true));
            return true;
        });
    }

    private void checkReadyComplete(ExpeditionPartyManager.PartySnapshot party, ReadyCheckState state) {
        if (state.responses().size() < party.members().size()) return;
        readyChecks.remove(party.partyId());
        broadcastReadyResults(party, state, false);
    }

    private void broadcastReadyResults(ExpeditionPartyManager.PartySnapshot party, ReadyCheckState state, boolean timedOut) {
        StringBuilder sb = new StringBuilder("<gold>[Ready Check]</gold> ");
        boolean allReady = true;

        for (UUID memberId : party.members()) {
            Boolean response = state.responses().get(memberId);
            String name = resolveName(memberId);
            if (response == null) {
                sb.append("<gray>? ").append(name).append("</gray> ");
                allReady = false;
            } else if (response) {
                sb.append("<green>✓ ").append(name).append("</green> ");
            } else {
                sb.append("<red>✗ ").append(name).append("</red> ");
                allReady = false;
            }
        }

        broadcastToParty(party, sb.toString().trim());
        if (allReady) {
            broadcastToParty(party, "<green><bold>All members are ready!</bold></green>");
        } else if (timedOut) {
            broadcastToParty(party, "<red>Ready check timed out. Not all members responded.</red>");
        }
    }



    public void startSummon(Player leader) {
        Optional<ExpeditionPartyManager.PartySnapshot> partyOpt = partyManager.partyOf(leader.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(leader, "<red>You are not in a party.</red>");
            return;
        }

        ExpeditionPartyManager.PartySnapshot party = partyOpt.get();
        PartyRole role = party.roles().getOrDefault(leader.getUniqueId(), PartyRole.MEMBER);
        if (!role.isAtLeast(PartyRole.OFFICER)) {
            messageService.send(leader, "<red>Only leaders and officers can summon the party.</red>");
            return;
        }

        Location destination = leader.getLocation().clone();
        long expiresAt = System.currentTimeMillis() + SUMMON_TIMEOUT_MS;

        for (UUID memberId : party.members()) {
            if (memberId.equals(leader.getUniqueId())) continue;
            pendingSummons.put(memberId, new SummonState(party.partyId(), leader.getUniqueId(), destination, expiresAt));
        }

        broadcastToParty(party, "<gold>[Summon]</gold> <yellow>" + leader.getName()
            + " is summoning the party! Use <white>/party summon accept</white> to teleport (30s).</yellow>");
    }

    public void acceptSummon(Player player) {
        SummonState summon = pendingSummons.remove(player.getUniqueId());
        if (summon == null) {
            messageService.send(player, "<red>No pending summon.</red>");
            return;
        }

        if (System.currentTimeMillis() > summon.expiresAt()) {
            messageService.send(player, "<red>That summon has expired.</red>");
            return;
        }

        scheduler.runEntity(player, "party-summon-tp-" + player.getUniqueId(), () -> {
            player.teleportAsync(summon.destination());
            messageService.send(player, "<green>Teleported to party.</green>");
        });
    }

    private void tickSummons() {
        long now = System.currentTimeMillis();
        pendingSummons.entrySet().removeIf(entry -> now > entry.getValue().expiresAt());
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        partyChatToggle.remove(playerId);
        pendingSummons.remove(playerId);
    }



    private void broadcastToParty(ExpeditionPartyManager.PartySnapshot party, String message) {
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                messageService.send(member, message);
            }
        }
    }

    private Optional<ExpeditionPartyManager.PartySnapshot> findPartyById(UUID partyId) {
        return partyManager.allParties().stream()
            .filter(p -> p.partyId().equals(partyId))
            .findFirst();
    }

    private String resolveName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offline = Bukkit.getOfflinePlayer(playerId).getName();
        return offline == null ? playerId.toString().substring(0, 8) : offline;
    }



    private record ReadyCheckState(UUID partyId, UUID initiator, ConcurrentHashMap<UUID, Boolean> responses, long expiresAt) {
    }

    private record SummonState(UUID partyId, UUID leaderId, Location destination, long expiresAt) {
    }
}
