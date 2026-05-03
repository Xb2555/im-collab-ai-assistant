package com.lark.imcollab.planner.intent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class DefaultLlmChoiceResolver implements LlmChoiceResolver {

    private final ChatModel chatModel;

    public DefaultLlmChoiceResolver(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String chooseOne(String instruction, List<String> allowedChoices, String systemPrompt) {
        String prompt = systemPrompt
                + "\n\n用户输入：\n" + instruction
                + "\n\n可选值：\n" + String.join(", ", allowedChoices)
                + "\n\n只返回一个可选值本身，不要解释。";
        String response = chatModel.call(prompt);
        return response == null ? "" : response.trim();
    }

    @Override
    public List<String> chooseMany(String instruction, List<String> allowedChoices, String systemPrompt) {
        String prompt = systemPrompt
                + "\n\n用户输入：\n" + instruction
                + "\n\n可选值：\n" + String.join(", ", allowedChoices)
                + "\n\n可以选择一个或多个。只返回 JSON 字符串数组，例如 [\"DOC\",\"PPT\"]。不要解释。";
        String response = chatModel.call(prompt);
        return parseAllowedChoices(response, allowedChoices);
    }

    private List<String> parseAllowedChoices(String response, List<String> allowedChoices) {
        if (response == null || response.isBlank() || allowedChoices == null || allowedChoices.isEmpty()) {
            return List.of();
        }
        String normalized = response.trim().toUpperCase(Locale.ROOT);
        Set<String> selected = new LinkedHashSet<>();
        for (String choice : allowedChoices) {
            if (choice != null && normalized.contains(choice.toUpperCase(Locale.ROOT))) {
                selected.add(choice);
            }
        }
        return new ArrayList<>(selected);
    }
}
