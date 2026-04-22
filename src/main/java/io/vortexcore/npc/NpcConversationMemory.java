package io.vortexcore.npc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class NpcConversationMemory {

    private final int maxTurns;
    private final ConcurrentLinkedDeque<ConversationTurn> turns;

    public NpcConversationMemory(int maxTurns) {
        this.maxTurns = maxTurns;
        this.turns = new ConcurrentLinkedDeque<>();
    }

    public void append(String role, String content) {
        turns.addLast(new ConversationTurn(role, content, Instant.now()));
        while (turns.size() > maxTurns) {
            turns.pollFirst();
        }
    }

    public List<ConversationTurn> snapshot() {
        return List.copyOf(turns);
    }

    public record ConversationTurn(String role, String content, Instant timestamp) {
    }
}
