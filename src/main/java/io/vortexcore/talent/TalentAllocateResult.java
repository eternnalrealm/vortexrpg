package io.vortexcore.talent;

public record TalentAllocateResult(boolean success, String message) {

    public static TalentAllocateResult success(String message) {
        return new TalentAllocateResult(true, message);
    }

    public static TalentAllocateResult failure(String message) {
        return new TalentAllocateResult(false, message);
    }
}
