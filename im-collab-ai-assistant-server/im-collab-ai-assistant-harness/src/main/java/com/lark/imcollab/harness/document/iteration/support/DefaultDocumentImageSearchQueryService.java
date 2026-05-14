package com.lark.imcollab.harness.document.iteration.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class DefaultDocumentImageSearchQueryService implements DocumentImageSearchQueryService {

    private final ChatModel chatModel;

    public DefaultDocumentImageSearchQueryService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String deriveQuery(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        String response = chatModel.call("""
                你是图片搜索词提炼器。
                从用户指令中提炼一个简短、具体、可用于图片搜索的中文或英文关键词短语。
                只输出关键词短语，不要解释，不要加引号，不要输出句子。
                用户指令：%s
                """.formatted(instruction));
        String query = response == null ? "" : response.trim();
        if (query.startsWith("```")) {
            int firstLineEnd = query.indexOf('\n');
            if (firstLineEnd >= 0) {
                query = query.substring(firstLineEnd + 1).trim();
            }
            if (query.endsWith("```")) {
                query = query.substring(0, query.length() - 3).trim();
            }
        }
        return query.isBlank() ? null : query;
    }
}
