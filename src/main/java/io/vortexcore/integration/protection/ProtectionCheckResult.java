package io.vortexcore.integration.protection;

public record ProtectionCheckResult(boolean allowed, String provider, String message) {

    public static ProtectionCheckResult pass() {
        return new ProtectionCheckResult(true, "none", "");
    }
}
