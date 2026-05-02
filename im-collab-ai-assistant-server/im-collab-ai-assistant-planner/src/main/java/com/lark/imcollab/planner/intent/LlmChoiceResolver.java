package com.lark.imcollab.planner.intent;

import java.util.List;

public interface LlmChoiceResolver {

    String chooseOne(String instruction, List<String> allowedChoices, String systemPrompt);

    default List<String> chooseMany(String instruction, List<String> allowedChoices, String systemPrompt) {
        String choice = chooseOne(instruction, allowedChoices, systemPrompt);
        if (choice == null || choice.isBlank()) {
            return List.of();
        }
        String normalized = choice.trim();
        return allowedChoices.stream()
                .filter(value -> normalized.equalsIgnoreCase(value)
                        || normalized.toUpperCase(java.util.Locale.ROOT).contains(value.toUpperCase(java.util.Locale.ROOT)))
                .distinct()
                .toList();
    }
}
