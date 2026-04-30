package com.lark.imcollab.planner.intent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

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
}
