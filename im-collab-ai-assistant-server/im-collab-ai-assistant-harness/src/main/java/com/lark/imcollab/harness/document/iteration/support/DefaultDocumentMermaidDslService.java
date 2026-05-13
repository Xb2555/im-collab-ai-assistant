package com.lark.imcollab.harness.document.iteration.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class DefaultDocumentMermaidDslService implements DocumentMermaidDslService {

    private final ChatModel chatModel;

    public DefaultDocumentMermaidDslService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generateMermaidDsl(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("Mermaid prompt is blank");
        }
        String response = chatModel.call("""
                你是 Mermaid 代码生成器。只输出合法 Mermaid 代码，不要解释。
                只允许 flowchart、graph、sequenceDiagram、stateDiagram-v2、erDiagram。
                用户需求：%s
                """.formatted(prompt));
        String text = response == null ? "" : response.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            if (start >= 0) {
                text = text.substring(start + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }
}
