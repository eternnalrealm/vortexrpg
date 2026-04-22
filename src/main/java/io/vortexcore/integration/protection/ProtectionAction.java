package io.vortexcore.integration.protection;

public enum ProtectionAction {
    SPELL_CAST("spell cast"),
    SPELL_TARGET("spell impact"),
    GATHER("gathering"),
    STATION_USE("station use"),
    FRACTURE_ADMIN("fracture control"),
    GATEWAY_USE("gateway use"),
    NPC_INTERACT("npc interaction");

    private final String displayName;

    ProtectionAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
