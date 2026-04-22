package io.vortexcore.integration.citizens;

import java.util.Arrays;
import java.util.Locale;

public enum CitizensNpcRole {
    CLASS_TRAINER("class_trainer"),
    SKILL_MASTER("skill_master"),
    EXPEDITION_BOARD("expedition_board"),
    QUARTERMASTER("quartermaster"),
    PROFESSION_MASTER("profession_master"),
    ADVENTURE_BOARD("adventure_board"),
    FRACTURE_GATEWAY("fracture_gateway"),
    NEURAL_GUIDE("neural_guide"),
    QUEST_GIVER("quest_giver");

    private final String id;

    CitizensNpcRole(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CitizensNpcRole parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
            .filter(role -> role.id.equalsIgnoreCase(input) || role.name().equalsIgnoreCase(input))
            .findFirst()
            .orElse(null);
    }

    public String displayName() {
        String[] parts = id.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
