package io.vortexcore.party;

import io.vortexcore.ui.MessageService;
import io.vortexcore.ui.VortexMenuBuilder;
import io.vortexcore.world.ExpeditionPartyManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public final class PartyCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String PERM_CREATE = "vortex.player.party.create";
    private static final String PERM_INVITE = "vortex.player.party.invite";
    private static final String PERM_ACCEPT = "vortex.player.party.accept";
    private static final String PERM_DECLINE = "vortex.player.party.decline";
    private static final String PERM_LEAVE = "vortex.player.party.leave";
    private static final String PERM_DISBAND = "vortex.player.party.disband";
    private static final String PERM_KICK = "vortex.player.party.kick";
    private static final String PERM_PROMOTE = "vortex.player.party.promote";
    private static final String PERM_DEMOTE = "vortex.player.party.demote";
    private static final String PERM_TRANSFER = "vortex.player.party.transfer";
    private static final String PERM_INFO = "vortex.player.party.info";
    private static final String PERM_GUI = "vortex.player.party.gui";
    private static final String PERM_CHAT = "vortex.player.party.chat";
    private static final String PERM_SUMMON = "vortex.player.party.summon";
    private static final String PERM_READY = "vortex.player.party.ready";
    private static final String PERM_RENAME = "vortex.player.party.rename";
    private static final String PERM_LIST = "vortex.admin.party.list";

    private final ExpeditionPartyManager partyManager;
    private final PartyService partyService;
    private final VortexMenuBuilder menuBuilder;
    private final MessageService messageService;

    public PartyCommand(
        ExpeditionPartyManager partyManager,
        PartyService partyService,
        VortexMenuBuilder menuBuilder,
        MessageService messageService
    ) {
        this.partyManager = partyManager;
        this.partyService = partyService;
        this.menuBuilder = menuBuilder;
        this.messageService = messageService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }

        if (label.equalsIgnoreCase("pc")) {
            if (!player.hasPermission(PERM_CHAT)) {
                messageService.send(player, "<red>No permission.</red>");
                return true;
            }
            String message = String.join(" ", args);
            if (message.isEmpty()) {
                messageService.send(player, "<red>Usage: /pc <message></red>");
            } else {
                partyService.sendPartyMessage(player, message);
            }
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "gui";

        switch (sub) {
            case "create" -> handleCreate(player, args);
            case "disband" -> handleDisband(player);
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player);
            case "decline" -> handleDecline(player);
            case "kick" -> handleKick(player, args);
            case "promote" -> handlePromote(player, args);
            case "demote" -> handleDemote(player, args);
            case "transfer" -> handleTransfer(player, args);
            case "leave" -> handleLeave(player);
            case "info" -> handleInfo(player);
            case "gui", "open" -> handleGui(player);
            case "chat" -> handleChat(player, args);
            case "toggle" -> handleToggle(player);
            case "summon" -> handleSummon(player, args);
            case "ready" -> handleReady(player, args);
            case "rename" -> handleRename(player, args);
            case "list" -> handleList(player);
            default -> messageService.send(player,
                "<red>Usage: /party [create|invite|accept|decline|kick|promote|demote|transfer|leave|info|gui|chat|toggle|summon|ready|rename|disband|list]</red>");
        }
        return true;
    }



    private void handleCreate(Player player, String[] args) {
        if (!player.hasPermission(PERM_CREATE)) { deny(player); return; }
        ExpeditionPartyManager.PartyResult result = partyManager.createParty(player.getUniqueId());
        sendResult(player, result);
        if (result.success() && args.length > 1) {
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            partyManager.setPartyName(player.getUniqueId(), name);
            messageService.send(player, "<gray>Party named: <white>" + name + "</white></gray>");
        }
    }

    private void handleDisband(Player player) {
        if (!player.hasPermission(PERM_DISBAND)) { deny(player); return; }
        var before = partyManager.partyOf(player.getUniqueId());
        ExpeditionPartyManager.PartyResult result = partyManager.disband(player.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            before.ifPresent(party -> notifyMembers(party.members(), "<red>Party has been disbanded.</red>"));
        }
    }

    private void handleInvite(Player player, String[] args) {
        if (!player.hasPermission(PERM_INVITE)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party invite <player></red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messageService.send(player, "<red>Player not found or offline.</red>");
            return;
        }
        ExpeditionPartyManager.PartyResult result = partyManager.invite(player.getUniqueId(), target.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            messageService.send(target, "<gold>" + player.getName() + " invited you to their party!</gold>");
            messageService.send(target, "<gray>Use <white>/party accept</white> or <white>/party decline</white>.</gray>");
        }
    }

    private void handleAccept(Player player) {
        if (!player.hasPermission(PERM_ACCEPT)) { deny(player); return; }
        ExpeditionPartyManager.PartyResult result = partyManager.acceptInvite(player.getUniqueId());
        sendResult(player, result);
        if (result.success() && result.party() != null) {
            notifyMembers(result.party().members(), "<green>" + player.getName() + " joined the party!</green>");
        }
    }

    private void handleDecline(Player player) {
        if (!player.hasPermission(PERM_DECLINE)) { deny(player); return; }
        ExpeditionPartyManager.PartyResult result = partyManager.declineInvite(player.getUniqueId());
        sendResult(player, result);
    }

    private void handleKick(Player player, String[] args) {
        if (!player.hasPermission(PERM_KICK)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party kick <player></red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messageService.send(player, "<red>Player not found or offline.</red>");
            return;
        }
        var before = partyManager.partyOf(player.getUniqueId());
        ExpeditionPartyManager.PartyResult result = partyManager.kick(player.getUniqueId(), target.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            messageService.send(target, "<red>You have been kicked from the party.</red>");
            before.ifPresent(party -> notifyMembers(party.members(), "<yellow>" + target.getName() + " was kicked from the party.</yellow>"));
        }
    }

    private void handlePromote(Player player, String[] args) {
        if (!player.hasPermission(PERM_PROMOTE)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party promote <player></red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messageService.send(player, "<red>Player not found or offline.</red>");
            return;
        }
        ExpeditionPartyManager.PartyResult result = partyManager.promote(player.getUniqueId(), target.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            notifyMembers(result.party().members(), "<yellow>" + target.getName() + " has been promoted to <gold>Officer</gold>.</yellow>");
        }
    }

    private void handleDemote(Player player, String[] args) {
        if (!player.hasPermission(PERM_DEMOTE)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party demote <player></red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messageService.send(player, "<red>Player not found or offline.</red>");
            return;
        }
        ExpeditionPartyManager.PartyResult result = partyManager.demote(player.getUniqueId(), target.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            notifyMembers(result.party().members(), "<yellow>" + target.getName() + " has been demoted to Member.</yellow>");
        }
    }

    private void handleTransfer(Player player, String[] args) {
        if (!player.hasPermission(PERM_TRANSFER)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party transfer <player></red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messageService.send(player, "<red>Player not found or offline.</red>");
            return;
        }
        ExpeditionPartyManager.PartyResult result = partyManager.transferLeadership(player.getUniqueId(), target.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            notifyMembers(result.party().members(), "<gold>" + target.getName() + " is now the party leader!</gold>");
        }
    }

    private void handleLeave(Player player) {
        if (!player.hasPermission(PERM_LEAVE)) { deny(player); return; }
        var before = partyManager.partyOf(player.getUniqueId());
        ExpeditionPartyManager.PartyResult result = partyManager.leave(player.getUniqueId());
        sendResult(player, result);
        if (result.success()) {
            before.ifPresent(party -> notifyMembers(party.members(), "<yellow>" + player.getName() + " left the party.</yellow>"));
        }
    }

    private void handleInfo(Player player) {
        if (!player.hasPermission(PERM_INFO)) { deny(player); return; }
        var partyOpt = partyManager.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(player, "<red>You are not in a party. Use /party create to start one.</red>");
            return;
        }
        var party = partyOpt.get();
        String nameStr = party.name().isEmpty() ? "<gray>(unnamed)</gray>" : "<white>" + party.name() + "</white>";
        messageService.send(player, "<gold>━━━━━ Party Info ━━━━━</gold>");
        messageService.send(player, "<gray>Name:</gray> " + nameStr);
        messageService.send(player, "<gray>ID:</gray> <white>" + party.partyId().toString().substring(0, 8) + "</white>");
        messageService.send(player, "<gray>Members:</gray> <white>" + party.members().size() + "/5</white>");
        for (UUID memberId : party.members()) {
            PartyRole role = party.roles().getOrDefault(memberId, PartyRole.MEMBER);
            String name = resolveName(memberId);
            Player member = Bukkit.getPlayer(memberId);
            String status = (member != null && member.isOnline()) ? "<green>●</green>" : "<red>●</red>";
            messageService.send(player, "  " + status + " " + role.displayTag() + " <white>" + name + "</white>");
        }
        messageService.send(player, "<gold>━━━━━━━━━━━━━━━━━━━━━</gold>");
    }

    private void handleGui(Player player) {
        if (!player.hasPermission(PERM_GUI)) { deny(player); return; }
        var partyOpt = partyManager.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(player, "<red>You are not in a party. Use /party create to start one.</red>");
            return;
        }
        openPartyGui(player, partyOpt.get());
    }

    private void handleChat(Player player, String[] args) {
        if (!player.hasPermission(PERM_CHAT)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party chat <message></red>");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        partyService.sendPartyMessage(player, message);
    }

    private void handleToggle(Player player) {
        if (!player.hasPermission(PERM_CHAT)) { deny(player); return; }
        partyService.togglePartyChat(player);
    }

    private void handleSummon(Player player, String[] args) {
        if (!player.hasPermission(PERM_SUMMON)) { deny(player); return; }
        if (args.length >= 2 && args[1].equalsIgnoreCase("accept")) {
            partyService.acceptSummon(player);
        } else {
            partyService.startSummon(player);
        }
    }

    private void handleReady(Player player, String[] args) {
        if (!player.hasPermission(PERM_READY)) { deny(player); return; }
        if (args.length >= 2) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("accept") || action.equals("yes")) {
                partyService.respondReady(player, true);
            } else if (action.equals("decline") || action.equals("no")) {
                partyService.respondReady(player, false);
            } else {
                messageService.send(player, "<red>Usage: /party ready [accept|decline]</red>");
            }
        } else {
            partyService.startReadyCheck(player);
        }
    }

    private void handleRename(Player player, String[] args) {
        if (!player.hasPermission(PERM_RENAME)) { deny(player); return; }
        if (args.length < 2) {
            messageService.send(player, "<red>Usage: /party rename <name></red>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ExpeditionPartyManager.PartyResult result = partyManager.setPartyName(player.getUniqueId(), name);
        sendResult(player, result);
        if (result.success() && result.party() != null) {
            notifyMembers(result.party().members(), "<yellow>Party renamed to <white>" + name + "</white>.</yellow>");
        }
    }

    private void handleList(Player player) {
        if (!player.hasPermission(PERM_LIST)) { deny(player); return; }
        Collection<ExpeditionPartyManager.PartySnapshot> all = partyManager.allParties();
        if (all.isEmpty()) {
            messageService.send(player, "<gray>No active parties on the server.</gray>");
            return;
        }
        messageService.send(player, "<gold>━━━━━ Active Parties (" + all.size() + ") ━━━━━</gold>");
        for (var party : all) {
            String nameStr = party.name().isEmpty() ? party.partyId().toString().substring(0, 8) : party.name();
            messageService.send(player, "<yellow>" + nameStr + "</yellow> — "
                + "<gray>Leader:</gray> <white>" + resolveName(party.leaderId()) + "</white>"
                + " <gray>|</gray> <white>" + party.members().size() + "/5</white> members");
        }
    }



    private void openPartyGui(Player player, ExpeditionPartyManager.PartySnapshot party) {
        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(Component.text("Party Manager"), 4);

        String partyTitle = party.name().isEmpty() ? "Party " + party.partyId().toString().substring(0, 8) : party.name();
        builder.button(4, buildIcon(Material.PAPER, "<gold>" + partyTitle + "</gold>", List.of(
            "<gray>Members: <white>" + party.members().size() + "/5</white></gray>",
            "<gray>Leader: <white>" + resolveName(party.leaderId()) + "</white></gray>"
        )), ctx -> CompletableFuture.completedFuture(null));

        int[] memberSlots = {11, 12, 13, 14, 15};
        int index = 0;
        boolean isLeaderOrOfficer = party.roles()
            .getOrDefault(player.getUniqueId(), PartyRole.MEMBER)
            .isAtLeast(PartyRole.OFFICER);

        for (UUID memberId : party.members()) {
            if (index >= memberSlots.length) break;
            int slot = memberSlots[index++];
            PartyRole role = party.roles().getOrDefault(memberId, PartyRole.MEMBER);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(memberId));
            String displayName = role.symbol() + " <white>" + resolveName(memberId) + "</white>";
            skullMeta.displayName(MINI.deserialize(displayName));

            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize(role.displayTag()));
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                double hp = member.getHealth();
                double max = 20.0;
                var maxAttr = member.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (maxAttr != null) max = maxAttr.getValue();
                lore.add(MINI.deserialize("<red>❤ " + String.format("%.0f", hp) + "/" + String.format("%.0f", max) + "</red>"));
                lore.add(MINI.deserialize("<green>● Online</green>"));
            } else {
                lore.add(MINI.deserialize("<red>● Offline</red>"));
            }

            if (isLeaderOrOfficer && !memberId.equals(player.getUniqueId())) {
                lore.add(Component.empty());
                lore.add(MINI.deserialize("<yellow>Click to manage</yellow>"));
            }
            skullMeta.lore(lore);
            head.setItemMeta(skullMeta);

            if (isLeaderOrOfficer && !memberId.equals(player.getUniqueId())) {
                builder.button(slot, head, ctx -> {
                    openMemberManageGui(ctx.player(), memberId, party);
                    return CompletableFuture.completedFuture(null);
                });
            } else {
                builder.button(slot, head, ctx -> CompletableFuture.completedFuture(null));
            }
        }

        builder.button(29, buildIcon(Material.ENDER_PEARL, "<light_purple>Summon Party</light_purple>", List.of(
            "<gray>Teleport all members to you.</gray>",
            "<gray>Members must accept within 30s.</gray>"
        )), ctx -> {
            partyService.startSummon(ctx.player());
            return ctx.close();
        });

        builder.button(30, buildIcon(Material.LIME_DYE, "<green>Ready Check</green>", List.of(
            "<gray>Start a ready check for all members.</gray>",
            "<gray>30 second timeout.</gray>"
        )), ctx -> {
            partyService.startReadyCheck(ctx.player());
            return ctx.close();
        });

        boolean toggled = partyService.isPartyChatToggled(player.getUniqueId());
        builder.button(31, buildIcon(
            toggled ? Material.WRITTEN_BOOK : Material.BOOK,
            toggled ? "<green>Party Chat: ON</green>" : "<gray>Party Chat: OFF</gray>",
            List.of(
                "<gray>Toggle party-only chat mode.</gray>",
                "<gray>Or use <white>/pc <message></white> for quick chat.</gray>"
            )
        ), ctx -> {
            partyService.togglePartyChat(ctx.player());
            return ctx.close();
        });

        builder.button(32, buildIcon(Material.NAME_TAG, "<yellow>Rename Party</yellow>", List.of(
            "<gray>Current: " + (party.name().isEmpty() ? "(unnamed)" : party.name()) + "</gray>",
            "<gray>Use /party rename <name></gray>"
        )), ctx -> CompletableFuture.completedFuture(null));

        builder.button(33, buildIcon(Material.BARRIER, "<red>Leave Party</red>", List.of(
            "<gray>Click to leave the party.</gray>"
        )), ctx -> {
            partyManager.leave(ctx.player().getUniqueId());
            messageService.send(ctx.player(), "<yellow>You left the party.</yellow>");
            return ctx.close();
        });

        menuBuilder.open(player, builder.build());
    }

    private void openMemberManageGui(Player manager, UUID targetId, ExpeditionPartyManager.PartySnapshot party) {
        String targetName = resolveName(targetId);
        PartyRole targetRole = party.roles().getOrDefault(targetId, PartyRole.MEMBER);
        boolean isLeader = party.leaderId().equals(manager.getUniqueId());

        VortexMenuBuilder.MenuDefinition.Builder builder = menuBuilder.menu(
            Component.text("Manage: " + targetName), 1
        );

        if (isLeader && targetRole == PartyRole.MEMBER) {
            builder.button(2, buildIcon(Material.GOLDEN_APPLE, "<yellow>Promote to Officer</yellow>", List.of(
                "<gray>Grant invite & kick permissions.</gray>"
            )), ctx -> {
                var result = partyManager.promote(ctx.player().getUniqueId(), targetId);
                sendResult(ctx.player(), result);
                if (result.success()) {
                    notifyMembers(result.party().members(), "<yellow>" + targetName + " promoted to <gold>Officer</gold>.</yellow>");
                }
                return ctx.close();
            });
        }

        if (isLeader && targetRole == PartyRole.OFFICER) {
            builder.button(2, buildIcon(Material.FERMENTED_SPIDER_EYE, "<yellow>Demote to Member</yellow>", List.of(
                "<gray>Remove officer permissions.</gray>"
            )), ctx -> {
                var result = partyManager.demote(ctx.player().getUniqueId(), targetId);
                sendResult(ctx.player(), result);
                if (result.success()) {
                    notifyMembers(result.party().members(), "<yellow>" + targetName + " demoted to Member.</yellow>");
                }
                return ctx.close();
            });
        }

        PartyRole managerRole = party.roles().getOrDefault(manager.getUniqueId(), PartyRole.MEMBER);
        if (managerRole.canManage(targetRole)) {
            builder.button(4, buildIcon(Material.IRON_SWORD, "<red>Kick</red>", List.of(
                "<gray>Remove from party.</gray>"
            )), ctx -> {
                var result = partyManager.kick(ctx.player().getUniqueId(), targetId);
                sendResult(ctx.player(), result);
                if (result.success()) {
                    Player target = Bukkit.getPlayer(targetId);
                    if (target != null) {
                        messageService.send(target, "<red>You have been kicked from the party.</red>");
                    }
                }
                return ctx.close();
            });
        }

        if (isLeader) {
            builder.button(6, buildIcon(Material.GOLDEN_HELMET, "<gold>Transfer Leadership</gold>", List.of(
                "<gray>Make this player the new leader.</gray>",
                "<gray>You will become an officer.</gray>"
            )), ctx -> {
                var result = partyManager.transferLeadership(ctx.player().getUniqueId(), targetId);
                sendResult(ctx.player(), result);
                if (result.success()) {
                    notifyMembers(result.party().members(), "<gold>" + targetName + " is now the party leader!</gold>");
                }
                return ctx.close();
            });
        }

        builder.button(8, buildIcon(Material.ARROW, "<gray>Back</gray>", List.of()), ctx -> {
            var refreshedParty = partyManager.partyOf(ctx.player().getUniqueId());
            if (refreshedParty.isPresent()) {
                openPartyGui(ctx.player(), refreshedParty.get());
            }
            return CompletableFuture.completedFuture(null);
        });

        menuBuilder.open(manager, builder.build());
    }



    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("pc")) return List.of();

        if (args.length == 1) {
            return filter(List.of(
                "create", "invite", "accept", "decline", "kick", "promote", "demote",
                "transfer", "leave", "info", "gui", "chat", "toggle", "summon", "ready",
                "rename", "disband", "list"
            ), args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("kick") || sub.equals("promote")
                || sub.equals("demote") || sub.equals("transfer")) {
                return filterOnline(args[1]);
            }
            if (sub.equals("ready")) {
                return filter(List.of("accept", "decline"), args[1]);
            }
            if (sub.equals("summon")) {
                return filter(List.of("accept"), args[1]);
            }
        }

        return List.of();
    }



    private void sendResult(Player player, ExpeditionPartyManager.PartyResult result) {
        messageService.send(player, result.success()
            ? "<green>" + result.message() + "</green>"
            : "<red>" + result.message() + "</red>");
    }

    private void deny(Player player) {
        messageService.send(player, "<red>No permission.</red>");
    }

    private void notifyMembers(Set<UUID> members, String message) {
        for (UUID memberId : members) {
            Player p = Bukkit.getPlayer(memberId);
            if (p != null && p.isOnline()) {
                messageService.send(p, message);
            }
        }
    }

    private String resolveName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offline = Bukkit.getOfflinePlayer(playerId).getName();
        return offline == null ? playerId.toString().substring(0, 8) : offline;
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

    private List<String> filterOnline(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(lower))
            .toList();
    }
}
