package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.TableModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

@Component
public class ResolveTableSchemaStepHandler implements ExecutionStepHandler {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ResolveTableSchemaStepHandler(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public String stepType() {
        return "RESOLVE_TABLE_SCHEMA";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        TableModel existing = (TableModel) step.getInput();
        if (existing != null && existing.getColumns() != null && !existing.getColumns().isEmpty()) {
            ctx.put("tableModel", existing);
            return;
        }
        String prompt = ctx.getString("generationPrompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("RESOLVE_TABLE_SCHEMA: no tableModel and no generationPrompt");
        }
        TableModel generated = generateTableModel(prompt);
        if (generated == null) {
            throw new IllegalStateException("RESOLVE_TABLE_SCHEMA: LLM failed to generate table schema");
        }
        ctx.put("tableModel", generated);
    }

    private TableModel generateTableModel(String prompt) {
        String response = chatModel.call(
                "你是表格结构生成器。根据用户描述，输出 JSON 格式的表格结构：\n"
                + "{\"columns\":[\"列名1\",\"列名2\"],\"rows\":[[\"值1\",\"值2\"]]}\n"
                + "只输出合法 JSON，不要解释。\n用户描述：" + prompt
        );
        try {
            var node = objectMapper.readTree(response.trim());
            List<String> columns = objectMapper.convertValue(node.get("columns"), new TypeReference<>() {});
            List<List<String>> rows = objectMapper.convertValue(node.get("rows"), new TypeReference<>() {});
            return TableModel.builder().columns(columns).rows(rows).build();
        } catch (Exception ignored) {
            return null;
        }
    }
}
