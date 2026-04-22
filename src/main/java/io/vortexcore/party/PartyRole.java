package io.vortexcore.party;

public enum PartyRole {

    LEADER,
    OFFICER,
    MEMBER;

    public boolean canManage(PartyRole other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isAtLeast(PartyRole required) {
        return this.ordinal() <= required.ordinal();
    }

    public String displayTag() {
        return switch (this) {
            case LEADER -> "<gold>★ Leader</gold>";
            case OFFICER -> "<yellow>◆ Officer</yellow>";
            case MEMBER -> "<gray>● Member</gray>";
        };
    }

    public String chatPrefix() {
        return switch (this) {
            case LEADER -> "<gold>★</gold> ";
            case OFFICER -> "<yellow>◆</yellow> ";
            case MEMBER -> "";
        };
    }

    public String symbol() {
        return switch (this) {
            case LEADER -> "<gold>★</gold>";
            case OFFICER -> "<yellow>◆</yellow>";
            case MEMBER -> "";
        };
    }
}
