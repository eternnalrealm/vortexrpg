package io.vortexcore.world;

import io.vortexcore.party.PartyRole;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ExpeditionPartyManager {

    private static final int MAX_PARTY_SIZE = 5;

    private final Duration inviteTtl;
    private final ConcurrentMap<UUID, PartyState> parties;
    private final ConcurrentMap<UUID, UUID> memberToParty;
    private final ConcurrentMap<UUID, PartyInvite> pendingInvites;

    public ExpeditionPartyManager(Duration inviteTtl) {
        this.inviteTtl = inviteTtl == null ? Duration.ofMinutes(2) : inviteTtl;
        this.parties = new ConcurrentHashMap<>();
        this.memberToParty = new ConcurrentHashMap<>();
        this.pendingInvites = new ConcurrentHashMap<>();
    }

    public PartyResult createParty(UUID leaderId) {
        if (leaderId == null) {
            return PartyResult.error("Leader id cannot be null.");
        }
        if (memberToParty.containsKey(leaderId)) {
            return PartyResult.error("You are already in a party.");
        }

        UUID partyId = UUID.randomUUID();
        PartyState party = new PartyState(partyId, leaderId);
        parties.put(partyId, party);
        memberToParty.put(leaderId, partyId);
        return PartyResult.success("Party created.", party.snapshot());
    }

    public PartyResult disband(UUID requesterId) {
        Optional<PartySnapshot> snapshot = partyOf(requesterId);
        if (snapshot.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartySnapshot party = snapshot.get();
        if (!party.leaderId().equals(requesterId)) {
            return PartyResult.error("Only the party leader can disband.");
        }

        PartyState removed = parties.remove(party.partyId());
        if (removed != null) {
            removed.members.forEach(member -> memberToParty.remove(member, party.partyId()));
            removeInvitesForParty(party.partyId());
        }
        return PartyResult.success("Party disbanded.", null);
    }

    public PartyResult leave(UUID memberId) {
        Optional<PartySnapshot> snapshot = partyOf(memberId);
        if (snapshot.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }

        PartySnapshot party = snapshot.get();
        PartyState state = parties.get(party.partyId());
        if (state == null) {
            memberToParty.remove(memberId, party.partyId());
            return PartyResult.success("You left the party.", null);
        }

        synchronized (state) {
            state.members.remove(memberId);
            state.roles.remove(memberId);
            memberToParty.remove(memberId, state.partyId);
            if (state.members.isEmpty()) {
                parties.remove(state.partyId, state);
                removeInvitesForParty(state.partyId);
                return PartyResult.success("Party closed.", null);
            }

            if (state.leaderId.equals(memberId)) {
                UUID nextLeader = state.members.iterator().next();
                state.leaderId = nextLeader;
                state.roles.put(nextLeader, PartyRole.LEADER);
            }
            return PartyResult.success("You left the party.", state.snapshot());
        }
    }

    public PartyResult invite(UUID leaderId, UUID targetId) {
        if (targetId == null || leaderId == null) {
            return PartyResult.error("Invalid party invite.");
        }
        if (leaderId.equals(targetId)) {
            return PartyResult.error("You cannot invite yourself.");
        }

        Optional<PartySnapshot> partySnapshot = partyOf(leaderId);
        if (partySnapshot.isEmpty()) {
            return PartyResult.error("Create a party first with /expeditions party create.");
        }
        PartySnapshot party = partySnapshot.get();
        PartyRole inviterRole = party.roles().getOrDefault(leaderId, PartyRole.MEMBER);
        if (!inviterRole.isAtLeast(PartyRole.OFFICER)) {
            return PartyResult.error("Only the leader or officers can invite.");
        }
        if (memberToParty.containsKey(targetId)) {
            return PartyResult.error("That player is already in a party.");
        }
        if (party.members().size() >= MAX_PARTY_SIZE) {
            return PartyResult.error("Party is full (max " + MAX_PARTY_SIZE + ").");
        }

        long expiresAt = System.currentTimeMillis() + inviteTtl.toMillis();
        pendingInvites.put(targetId, new PartyInvite(party.partyId(), leaderId, targetId, expiresAt));
        return PartyResult.success("Invite sent.", party);
    }

    public PartyResult acceptInvite(UUID targetId) {
        PartyInvite invite = pendingInvites.remove(targetId);
        if (invite == null) {
            return PartyResult.error("You do not have a pending party invite.");
        }
        if (invite.expiresAtEpochMillis < System.currentTimeMillis()) {
            return PartyResult.error("That invite expired.");
        }
        if (memberToParty.containsKey(targetId)) {
            return PartyResult.error("You are already in a party.");
        }

        PartyState state = parties.get(invite.partyId);
        if (state == null) {
            return PartyResult.error("That party no longer exists.");
        }

        synchronized (state) {
            if (state.members.size() >= MAX_PARTY_SIZE) {
                return PartyResult.error("Party is already full.");
            }
            state.members.add(targetId);
            state.roles.put(targetId, PartyRole.MEMBER);
            memberToParty.put(targetId, state.partyId);
            return PartyResult.success("Joined party.", state.snapshot());
        }
    }

    public PartyResult declineInvite(UUID targetId) {
        PartyInvite invite = pendingInvites.remove(targetId);
        if (invite == null) {
            return PartyResult.error("You do not have a pending party invite.");
        }
        return PartyResult.success("Invite declined.", partyOf(invite.inviterId).orElse(null));
    }

    public Optional<PartySnapshot> partyOf(UUID memberId) {
        UUID partyId = memberToParty.get(memberId);
        if (partyId == null) {
            return Optional.empty();
        }
        PartyState state = parties.get(partyId);
        if (state == null) {
            memberToParty.remove(memberId, partyId);
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    public Set<UUID> membersOf(UUID memberId) {
        return partyOf(memberId).map(PartySnapshot::members).orElse(Set.of(memberId));
    }

    public PartyResult kick(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            return PartyResult.error("You cannot kick yourself. Use leave instead.");
        }
        Optional<PartySnapshot> snap = partyOf(requesterId);
        if (snap.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartyState state = parties.get(snap.get().partyId());
        if (state == null) {
            return PartyResult.error("Party not found.");
        }
        synchronized (state) {
            if (!state.members.contains(targetId)) {
                return PartyResult.error("That player is not in your party.");
            }
            PartyRole requesterRole = state.roles.getOrDefault(requesterId, PartyRole.MEMBER);
            PartyRole targetRole = state.roles.getOrDefault(targetId, PartyRole.MEMBER);
            if (!requesterRole.canManage(targetRole)) {
                return PartyResult.error("You cannot kick a member of equal or higher rank.");
            }
            state.members.remove(targetId);
            state.roles.remove(targetId);
            memberToParty.remove(targetId, state.partyId);
            return PartyResult.success("Player kicked.", state.snapshot());
        }
    }

    public PartyResult promote(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            return PartyResult.error("You cannot promote yourself.");
        }
        Optional<PartySnapshot> snap = partyOf(requesterId);
        if (snap.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartyState state = parties.get(snap.get().partyId());
        if (state == null) {
            return PartyResult.error("Party not found.");
        }
        synchronized (state) {
            if (!state.leaderId.equals(requesterId)) {
                return PartyResult.error("Only the party leader can promote members.");
            }
            if (!state.members.contains(targetId)) {
                return PartyResult.error("That player is not in your party.");
            }
            PartyRole current = state.roles.getOrDefault(targetId, PartyRole.MEMBER);
            if (current == PartyRole.OFFICER) {
                return PartyResult.error("That player is already an officer. Use transfer to make them leader.");
            }
            if (current == PartyRole.LEADER) {
                return PartyResult.error("That player is already the leader.");
            }
            state.roles.put(targetId, PartyRole.OFFICER);
            return PartyResult.success("Player promoted to officer.", state.snapshot());
        }
    }

    public PartyResult demote(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            return PartyResult.error("You cannot demote yourself.");
        }
        Optional<PartySnapshot> snap = partyOf(requesterId);
        if (snap.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartyState state = parties.get(snap.get().partyId());
        if (state == null) {
            return PartyResult.error("Party not found.");
        }
        synchronized (state) {
            if (!state.leaderId.equals(requesterId)) {
                return PartyResult.error("Only the party leader can demote members.");
            }
            if (!state.members.contains(targetId)) {
                return PartyResult.error("That player is not in your party.");
            }
            PartyRole current = state.roles.getOrDefault(targetId, PartyRole.MEMBER);
            if (current == PartyRole.MEMBER) {
                return PartyResult.error("That player is already a regular member.");
            }
            state.roles.put(targetId, PartyRole.MEMBER);
            return PartyResult.success("Player demoted to member.", state.snapshot());
        }
    }

    public PartyResult transferLeadership(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            return PartyResult.error("You are already the leader.");
        }
        Optional<PartySnapshot> snap = partyOf(requesterId);
        if (snap.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartyState state = parties.get(snap.get().partyId());
        if (state == null) {
            return PartyResult.error("Party not found.");
        }
        synchronized (state) {
            if (!state.leaderId.equals(requesterId)) {
                return PartyResult.error("Only the party leader can transfer leadership.");
            }
            if (!state.members.contains(targetId)) {
                return PartyResult.error("That player is not in your party.");
            }
            state.roles.put(requesterId, PartyRole.OFFICER);
            state.roles.put(targetId, PartyRole.LEADER);
            state.leaderId = targetId;
            return PartyResult.success("Leadership transferred.", state.snapshot());
        }
    }

    public PartyResult setPartyName(UUID requesterId, String name) {
        Optional<PartySnapshot> snap = partyOf(requesterId);
        if (snap.isEmpty()) {
            return PartyResult.error("You are not in a party.");
        }
        PartyState state = parties.get(snap.get().partyId());
        if (state == null) {
            return PartyResult.error("Party not found.");
        }
        synchronized (state) {
            if (!state.leaderId.equals(requesterId)) {
                return PartyResult.error("Only the party leader can rename the party.");
            }
            if (name != null && name.length() > 32) {
                return PartyResult.error("Party name must be 32 characters or fewer.");
            }
            state.name = name == null ? "" : name;
            return PartyResult.success("Party renamed.", state.snapshot());
        }
    }

    public Collection<PartySnapshot> allParties() {
        return parties.values().stream().map(PartyState::snapshot).toList();
    }

    private void removeInvitesForParty(UUID partyId) {
        pendingInvites.entrySet().removeIf(entry -> entry.getValue().partyId.equals(partyId));
    }

    public record PartySnapshot(UUID partyId, UUID leaderId, Set<UUID> members, Map<UUID, PartyRole> roles, String name) {

        public PartySnapshot {
            members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
            roles = Map.copyOf(roles);
        }
    }

    public record PartyResult(boolean success, String message, PartySnapshot party) {

        public static PartyResult success(String message, PartySnapshot party) {
            return new PartyResult(true, message, party);
        }

        public static PartyResult error(String message) {
            return new PartyResult(false, message, null);
        }
    }

    private static final class PartyState {

        private final UUID partyId;
        private UUID leaderId;
        private String name;
        private final LinkedHashSet<UUID> members;
        private final ConcurrentHashMap<UUID, PartyRole> roles;

        private PartyState(UUID partyId, UUID leaderId) {
            this.partyId = partyId;
            this.leaderId = leaderId;
            this.name = "";
            this.members = new LinkedHashSet<>(List.of(leaderId));
            this.roles = new ConcurrentHashMap<>();
            this.roles.put(leaderId, PartyRole.LEADER);
        }

        private PartySnapshot snapshot() {
            return new PartySnapshot(partyId, leaderId, Set.copyOf(members), Map.copyOf(roles), name);
        }
    }

    private record PartyInvite(
        UUID partyId,
        UUID inviterId,
        UUID targetId,
        long expiresAtEpochMillis
    ) {
    }
}
