package io.vortexcore.command;

import io.vortexcore.world.ExpeditionBoardManager;
import io.vortexcore.world.ExpeditionLeaderboardEntry;
import io.vortexcore.world.ExpeditionPartyManager;
import io.vortexcore.world.ExpeditionRunSnapshot;
import io.vortexcore.world.BreachQuartermasterService;
import io.vortexcore.world.MythicBreachManager;
import io.vortexcore.ui.MessageService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ExpeditionsCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_OPEN = "vortex.player.expeditions.open";
    private static final String PERM_STATUS = "vortex.player.expeditions.status";
    private static final String PERM_LEADERBOARD = "vortex.player.expeditions.leaderboard";
    private static final String PERM_BREACH = "vortex.player.expeditions.breach";
    private static final String PERM_REWARDS = "vortex.player.expeditions.rewards";
    private static final String PERM_QUARTERMASTER = "vortex.player.expeditions.quartermaster";
    private static final String PERM_ABANDON = "vortex.player.expeditions.abandon";
    private static final String PERM_PARTY_INFO = "vortex.player.expeditions.party.info";
    private static final String PERM_PARTY_CREATE = "vortex.player.expeditions.party.create";
    private static final String PERM_PARTY_INVITE = "vortex.player.expeditions.party.invite";
    private static final String PERM_PARTY_ACCEPT = "vortex.player.expeditions.party.accept";
    private static final String PERM_PARTY_DECLINE = "vortex.player.expeditions.party.decline";
    private static final String PERM_PARTY_LEAVE = "vortex.player.expeditions.party.leave";
    private static final String PERM_PARTY_DISBAND = "vortex.player.expeditions.party.disband";

    private final ExpeditionBoardManager expeditionBoardManager;
    private final ExpeditionPartyManager partyManager;
    private final MythicBreachManager mythicBreachManager;
    private final BreachQuartermasterService breachQuartermasterService;
    private final MessageService messageService;

    public ExpeditionsCommand(
        ExpeditionBoardManager expeditionBoardManager,
        ExpeditionPartyManager partyManager,
        MythicBreachManager mythicBreachManager,
        BreachQuartermasterService breachQuartermasterService,
        MessageService messageService
    ) {
        this.expeditionBoardManager = expeditionBoardManager;
        this.partyManager = partyManager;
        this.mythicBreachManager = mythicBreachManager;
        this.breachQuartermasterService = breachQuartermasterService;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use expedition commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (deny(player, PERM_OPEN)) {
                return true;
            }
            expeditionBoardManager.openBoard(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (deny(player, PERM_STATUS)) {
                return true;
            }
            expeditionBoardManager.activeRun(player.getUniqueId()).ifPresentOrElse(
                run -> {
                    messageService.send(player, statusLine(run));
                    messageService.send(player,
                        (run.breachFinale()
                            ? "Finale: " + (run.breachLaunched() ? "Active breach launched" : "Ready to launch")
                            : "Progress: " + run.killCount() + "/" + run.targetKills())
                            + " | Score: " + String.format(Locale.US, "%.1f", run.score())
                            + " | Timer: " + formatDuration(run.elapsedSeconds()) + "/" + formatDuration(run.timeLimitSeconds())
                    );
                    messageService.send(player,
                        "Rotation: " + run.rotationId()
                            + " | Multiplier: x" + String.format(Locale.US, "%.2f", run.multiplier())
                            + " | Party Size: " + run.participantCount()
                    );
                    messageService.send(player,
                        "Breach Tokens: " + mythicBreachManager.breachTokenBalance(player)
                            + " | Pending Caches: " + mythicBreachManager.pendingRewardCount(player.getUniqueId())
                    );
                    mythicBreachManager.activeSession(player.getUniqueId()).ifPresent(session -> messageService.send(player,
                        "Breach Session: " + session.worldName()
                            + " | Boss: " + session.bossName()
                            + " | Phase: " + session.phase()
                            + " | Ability: " + session.activeAbility()
                            + " | Timer: " + formatDuration(session.elapsedSeconds()) + "/" + formatDuration(session.timeLimitSeconds())
                    ));
                    mythicBreachManager.activeSession(player.getUniqueId()).ifPresent(session -> messageService.send(player,
                        "Revives: " + session.remainingRevives() + "/" + session.totalRevives()
                            + " | Active Fighters: " + session.activeFighters()
                            + " | Eliminated: " + session.eliminatedPlayers()
                            + " | Grace: " + formatDuration(session.reconnectGraceSeconds())
                    ));
                },
                () -> {
                    messageService.send(player, "You do not have an active expedition contract.");
                    messageService.send(player,
                        "Breach Tokens: " + mythicBreachManager.breachTokenBalance(player)
                            + " | Pending Caches: " + mythicBreachManager.pendingRewardCount(player.getUniqueId())
                    );
                }
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("leaderboard")) {
            if (deny(player, PERM_LEADERBOARD)) {
                return true;
            }
            List<ExpeditionLeaderboardEntry> entries = expeditionBoardManager.currentLeaderboard(5);
            if (entries.isEmpty()) {
                messageService.send(player,
                    "No completed runs for rotation " + expeditionBoardManager.currentRotationId() + " yet."
                );
                return true;
            }

            messageService.send(player, "Mythic Expedition Leaderboard (" + expeditionBoardManager.currentRotationId() + ")");
            int rank = 1;
            for (ExpeditionLeaderboardEntry entry : entries) {
                messageService.send(player,
                    rank + ". " + entry.playerName()
                        + " | " + entry.scoreTier().name()
                        + " | Score " + String.format(Locale.US, "%.1f", entry.bestScore())
                        + " | Clear " + formatDuration(entry.clearSeconds())
                );
                rank++;
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("abandon")) {
            if (deny(player, PERM_ABANDON)) {
                return true;
            }
            expeditionBoardManager.abandonContract(player.getUniqueId());
            messageService.send(player, "Your expedition contract has been abandoned.");
            return true;
        }

        if (args[0].equalsIgnoreCase("breach")) {
            if (deny(player, PERM_BREACH)) {
                return true;
            }
            mythicBreachManager.launchOrRejoin(player).thenAccept(result -> messageService.send(player, result.message()));
            return true;
        }

        if (args[0].equalsIgnoreCase("rewards")) {
            if (deny(player, PERM_REWARDS)) {
                return true;
            }
            mythicBreachManager.openRewardCache(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("quartermaster")) {
            if (deny(player, PERM_QUARTERMASTER)) {
                return true;
            }
            breachQuartermasterService.openQuartermaster(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("party")) {
            return handlePartySubcommand(player, label, args);
        }

        messageService.send(player, "Usage: /" + label + " [open|status|leaderboard|breach|rewards|quartermaster|party|abandon]");
        return true;
    }

    private boolean handlePartySubcommand(Player player, String label, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("info")) {
            if (deny(player, PERM_PARTY_INFO)) {
                return true;
            }
            Optional<ExpeditionPartyManager.PartySnapshot> party = partyManager.partyOf(player.getUniqueId());
            if (party.isEmpty()) {
                messageService.send(player, "You are not in an expedition party.");
                messageService.send(player, "Usage: /" + label + " party [create|invite <player>|accept|decline|leave|disband|info]");
                return true;
            }
            ExpeditionPartyManager.PartySnapshot snapshot = party.get();
            messageService.send(player, "Party " + snapshot.partyId().toString().substring(0, 8));
            messageService.send(player, "Leader: " + resolveName(snapshot.leaderId()));
            messageService.send(player, "Members: " + snapshot.members().stream().map(this::resolveName).collect(Collectors.joining(", ")));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("create")) {
            if (deny(player, PERM_PARTY_CREATE)) {
                return true;
            }
            ExpeditionPartyManager.PartyResult result = partyManager.createParty(player.getUniqueId());
            messageService.send(player, result.message());
            return true;
        }

        if (action.equals("invite")) {
            if (deny(player, PERM_PARTY_INVITE)) {
                return true;
            }
            if (args.length < 3) {
                messageService.send(player, "Usage: /" + label + " party invite <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                messageService.send(player, "Player must be online to invite.");
                return true;
            }

            ExpeditionPartyManager.PartyResult result = partyManager.invite(player.getUniqueId(), target.getUniqueId());
            messageService.send(player, result.message());
            if (result.success()) {
                messageService.send(target,
                    resolveName(player.getUniqueId()) + " invited you to an expedition party. Use /expeditions party accept."
                );
            }
            return true;
        }

        if (action.equals("accept")) {
            if (deny(player, PERM_PARTY_ACCEPT)) {
                return true;
            }
            ExpeditionPartyManager.PartyResult result = partyManager.acceptInvite(player.getUniqueId());
            messageService.send(player, result.message());
            if (result.success() && result.party() != null) {
                notifyParty(result.party(), resolveName(player.getUniqueId()) + " joined the expedition party.");
            }
            return true;
        }

        if (action.equals("decline")) {
            if (deny(player, PERM_PARTY_DECLINE)) {
                return true;
            }
            ExpeditionPartyManager.PartyResult result = partyManager.declineInvite(player.getUniqueId());
            messageService.send(player, result.message());
            return true;
        }

        if (action.equals("leave")) {
            if (deny(player, PERM_PARTY_LEAVE)) {
                return true;
            }
            Optional<ExpeditionPartyManager.PartySnapshot> before = partyManager.partyOf(player.getUniqueId());
            expeditionBoardManager.abandonContract(player.getUniqueId());
            ExpeditionPartyManager.PartyResult result = partyManager.leave(player.getUniqueId());
            messageService.send(player, result.message());
            before.ifPresent(party -> notifyParty(party, resolveName(player.getUniqueId()) + " left the expedition party."));
            return true;
        }

        if (action.equals("disband")) {
            if (deny(player, PERM_PARTY_DISBAND)) {
                return true;
            }
            Optional<ExpeditionPartyManager.PartySnapshot> before = partyManager.partyOf(player.getUniqueId());
            if (before.isEmpty()) {
                messageService.send(player, "You are not in an expedition party.");
                return true;
            }

            ExpeditionPartyManager.PartySnapshot snapshot = before.get();
            ExpeditionPartyManager.PartyResult result = partyManager.disband(player.getUniqueId());
            messageService.send(player, result.message());
            if (result.success()) {
                expeditionBoardManager.abandonContracts(snapshot.members());
                notifyMembers(snapshot.members(), "Expedition party disbanded.");
            }
            return true;
        }

        messageService.send(player, "Usage: /" + label + " party [create|invite <player>|accept|decline|leave|disband|info]");
        return true;
    }

    private void notifyParty(ExpeditionPartyManager.PartySnapshot party, String message) {
        notifyMembers(party.members(), message);
    }

    private void notifyMembers(Set<UUID> members, String message) {
        for (UUID memberId : members) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && player.isOnline()) {
                messageService.send(player, message);
            }
        }
    }

    private String resolveName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String offline = Bukkit.getOfflinePlayer(playerId).getName();
        return offline == null ? playerId.toString().substring(0, 8) : offline;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            java.util.ArrayList<String> suggestions = new java.util.ArrayList<>();
            if (sender.hasPermission(PERM_OPEN)) {
                suggestions.add("open");
            }
            if (sender.hasPermission(PERM_STATUS)) {
                suggestions.add("status");
            }
            if (sender.hasPermission(PERM_LEADERBOARD)) {
                suggestions.add("leaderboard");
            }
            if (sender.hasPermission(PERM_BREACH)) {
                suggestions.add("breach");
            }
            if (sender.hasPermission(PERM_REWARDS)) {
                suggestions.add("rewards");
            }
            if (sender.hasPermission(PERM_QUARTERMASTER)) {
                suggestions.add("quartermaster");
            }
            if (hasAnyPartyPermission(sender)) {
                suggestions.add("party");
            }
            if (sender.hasPermission(PERM_ABANDON)) {
                suggestions.add("abandon");
            }
            return suggestions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            java.util.ArrayList<String> suggestions = new java.util.ArrayList<>();
            if (sender.hasPermission(PERM_PARTY_CREATE)) {
                suggestions.add("create");
            }
            if (sender.hasPermission(PERM_PARTY_INVITE)) {
                suggestions.add("invite");
            }
            if (sender.hasPermission(PERM_PARTY_ACCEPT)) {
                suggestions.add("accept");
            }
            if (sender.hasPermission(PERM_PARTY_DECLINE)) {
                suggestions.add("decline");
            }
            if (sender.hasPermission(PERM_PARTY_LEAVE)) {
                suggestions.add("leave");
            }
            if (sender.hasPermission(PERM_PARTY_DISBAND)) {
                suggestions.add("disband");
            }
            if (sender.hasPermission(PERM_PARTY_INFO)) {
                suggestions.add("info");
            }
            return suggestions;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("invite") && sender.hasPermission(PERM_PARTY_INVITE)) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private boolean hasAnyPartyPermission(CommandSender sender) {
        return sender.hasPermission(PERM_PARTY_INFO)
            || sender.hasPermission(PERM_PARTY_CREATE)
            || sender.hasPermission(PERM_PARTY_INVITE)
            || sender.hasPermission(PERM_PARTY_ACCEPT)
            || sender.hasPermission(PERM_PARTY_DECLINE)
            || sender.hasPermission(PERM_PARTY_LEAVE)
            || sender.hasPermission(PERM_PARTY_DISBAND);
    }

    private boolean deny(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        messageService.send(player, "<red>You do not have permission: " + permission + "</red>");
        return true;
    }

    private String statusLine(ExpeditionRunSnapshot run) {
        return "Active contract: " + run.title() + " | Objective: " + run.objective();
    }

    private String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
