package io.vortexcore.support;

import java.util.Locale;

public final class NameFormat {

    private NameFormat() {}

    /**
     * Converts a SCREAMING_SNAKE_CASE or snake_case identifier into Title Case display text.
     * For example {@code "SWORD_MASTER"} becomes {@code "Sword Master"}.
     * Returns {@code "Unknown"} for null or blank input.
     */
    public static String friendly(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String[] tokens = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return builder.toString();
    }
}
