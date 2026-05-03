package com.lark.imcollab.app.planner.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ContextMessageSelectionService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ContextMessageSelectionService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public List<LarkMessageHistoryItem> select(
            String rawInstruction,
            String selectionInstruction,
            List<LarkMessageHistoryItem> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String criteria = firstNonBlank(selectionInstruction, rawInstruction);
        if (criteria.isBlank()) {
            return candidates;
        }
        try {
            String response = chatModel.call(buildPrompt(criteria, candidates));
            SelectionDecision decision = objectMapper.readValue(extractJson(response), SelectionDecision.class);
            if (decision == null || decision.selectedMessageIds == null || decision.selectedMessageIds.isEmpty()) {
                return List.of();
            }
            Set<String> selectedIds = new LinkedHashSet<>(decision.selectedMessageIds);
            selectedIds.addAll(auditMissingSelections(criteria, candidates, selectedIds));
            Set<String> finalSelectedIds = pruneInvalidSelections(criteria, candidates, selectedIds);
            return candidates.stream()
                    .filter(item -> item != null && item.messageId() != null && finalSelectedIds.contains(item.messageId()))
                    .toList();
        } catch (Exception exception) {
            log.warn("Failed to semantically filter IM context candidates, returning empty selection: {}",
                    exception.getMessage());
            return List.of();
        }
    }

    private Set<String> auditMissingSelections(
            String criteria,
            List<LarkMessageHistoryItem> candidates,
            Set<String> selectedIds
    ) {
        List<LarkMessageHistoryItem> unselected = candidates.stream()
                .filter(item -> item != null && item.messageId() != null && !selectedIds.contains(item.messageId()))
                .toList();
        if (unselected.isEmpty()) {
            return Set.of();
        }
        try {
            String response = chatModel.call(buildAuditPrompt(criteria, candidates, selectedIds, unselected));
            SelectionDecision decision = objectMapper.readValue(extractJson(response), SelectionDecision.class);
            if (decision == null || decision.selectedMessageIds == null || decision.selectedMessageIds.isEmpty()) {
                return Set.of();
            }
            return new LinkedHashSet<>(decision.selectedMessageIds);
        } catch (Exception exception) {
            log.warn("Failed to audit IM context candidate selection: {}", exception.getMessage());
            return Set.of();
        }
    }

    private Set<String> pruneInvalidSelections(
            String criteria,
            List<LarkMessageHistoryItem> candidates,
            Set<String> selectedIds
    ) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return Set.of();
        }
        List<LarkMessageHistoryItem> selectedCandidates = candidates.stream()
                .filter(item -> item != null && item.messageId() != null && selectedIds.contains(item.messageId()))
                .toList();
        if (selectedCandidates.isEmpty()) {
            return Set.of();
        }
        try {
            String response = chatModel.call(buildPrunePrompt(criteria, selectedCandidates));
            SelectionDecision decision = objectMapper.readValue(extractJson(response), SelectionDecision.class);
            if (decision == null || decision.selectedMessageIds == null) {
                return Set.of();
            }
            return new LinkedHashSet<>(decision.selectedMessageIds);
        } catch (Exception exception) {
            log.warn("Failed to prune IM context candidate selection, keeping pre-prune selection: {}",
                    exception.getMessage());
            return selectedIds;
        }
    }

    private String buildPrompt(String criteria, List<LarkMessageHistoryItem> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 Planner 的 IM 消息候选筛选器。
                只根据用户的整句筛选条件，判断哪些候选聊天消息应该作为任务上下文。
                不要总结，不要规划，不要回答用户。

                筛选要求：
                - 严格保留“同时满足用户全部筛选条件”的所有消息，不要只挑最重要的几条。
                - 用户可能同时给出时间、标记、包含词、排除词、主题类别、输出范围等多个条件；必须按整句合取判断。
                - 如果用户说“只总结其中属于 X 的消息 / 只要 X 类消息 / 仅提取 X”，那么只有内容本身属于 X 的消息能进入 selectedMessageIds；其他消息即使有相同标记，也只能作为约束参考，不能作为原始材料入选。
                - 说明输出范围、交付物形式、排除项、验证方式的消息属于任务约束；如果它没有描述 X 类事实本身，不要因为提到 X 这个词就入选。
                - 判断“属于 X”时看消息要表达的事实类别，而不是只看是否出现 X 字样。比如“这轮只验证风险摘要”是在说明输出约束，不是风险事实。
                - 如果用户只是说“总结这些消息”且没有进一步限定类别，则带标记且不违反排除条件的候选都应进入 selectedMessageIds。
                - 如果候选消息与用户条件大体匹配但你不确定是否该保留，先判断它是否满足全部硬性限定；满足才保留，不满足就不要保留。
                - 用户要求排除的消息必须排除。
                - 机器人/app 消息、当前任务指令、明显无关闲聊默认不选。
                - 不要因为消息主题是“待办/风险/决策/需求”等不同类别而漏选；前提是这些类别符合用户整句限定。
                - “待办”“验证”“继续处理”“后续动作”类消息只要满足用户整句条件，也必须保留；不要因为它像流程说明就排除。
                - 你不是摘要器，不要按重要性压缩候选；筛选阶段的目标是在“严格满足条件”的集合内高召回。
                - 如果没有符合条件的候选，selectedMessageIds 返回空数组。

                用户筛选条件：
                """).append(criteria).append("\n\n候选消息：\n");
        for (int index = 0; index < candidates.size(); index++) {
            LarkMessageHistoryItem item = candidates.get(index);
            if (item == null) {
                continue;
            }
            builder.append(index + 1)
                    .append(". id=").append(nullToEmpty(item.messageId()))
                    .append(" senderType=").append(nullToEmpty(item.senderType()))
                    .append(" sender=").append(firstNonBlank(item.senderName(), item.senderId(), "未知成员"))
                    .append(" content=").append(truncate(item.content(), 500))
                    .append("\n");
        }
        builder.append("""

                只输出 JSON：
                {"selectedMessageIds":["om_xxx"],"sufficient":true,"reason":""}
                """);
        return builder.toString();
    }

    private String buildAuditPrompt(
            String criteria,
            List<LarkMessageHistoryItem> allCandidates,
            Set<String> selectedIds,
            List<LarkMessageHistoryItem> unselected
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 Planner 的 IM 消息筛选审计器。
                第一轮已经选中一部分候选。你的任务不是总结，而是检查“未选候选”里是否还有符合用户整句条件、应该补回的消息。
                只返回需要补回的未选消息 ID。不要返回已选 ID。不要解释。

                审计原则：
                - 高召回优先，但只能补回“同时满足用户全部筛选条件”的未选候选。
                - 如果用户限定“只总结/只要/仅提取”某类消息，不属于该类的未选候选不要补回，即使它有相同标记。
                - 输出范围、交付物形式、排除项、验证方式等任务约束不要补回为正文材料，除非它本身描述的是用户限定的事实类别。
                - 不要按重要性压缩；待办、风险、决策、需求、后续验证等都可能是有效材料，前提是符合用户整句条件。
                - 明确违反排除条件、机器人/app 消息、当前任务指令、明显噪声不要补回。

                用户筛选条件：
                """).append(criteria).append("\n\n已选 ID：").append(selectedIds).append("\n\n未选候选：\n");
        for (int index = 0; index < unselected.size(); index++) {
            LarkMessageHistoryItem item = unselected.get(index);
            builder.append(index + 1)
                    .append(". id=").append(nullToEmpty(item.messageId()))
                    .append(" senderType=").append(nullToEmpty(item.senderType()))
                    .append(" sender=").append(firstNonBlank(item.senderName(), item.senderId(), "未知成员"))
                    .append(" content=").append(truncate(item.content(), 500))
                    .append("\n");
        }
        builder.append("""

                只输出 JSON：
                {"selectedMessageIds":["om_xxx"],"sufficient":true,"reason":""}
                """);
        return builder.toString();
    }

    private String buildPrunePrompt(String criteria, List<LarkMessageHistoryItem> selectedCandidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 Planner 的 IM 消息筛选复核器。
                下面这些消息已经被第一轮选中。你的任务是只保留仍然满足用户整句条件的消息 ID，并剔除误选。
                不要补新消息，不要总结，不要解释。

                复核原则：
                - 用户的排除条件优先级最高；违反排除条件的消息必须剔除，即使它同时满足包含词、标记或时间范围。
                - 用户的限定条件必须全部满足；只满足部分条件的消息必须剔除。
                - 如果用户限定“只总结/只要/仅提取”某类消息，消息正文表达的事实类别必须属于该类才保留。
                - 任务约束、输出形式、验证范围、噪声说明、当前任务指令本身都不能作为正文材料保留，除非用户明确要总结这些约束本身。
                - 如果没有仍然符合条件的消息，selectedMessageIds 返回空数组。

                用户筛选条件：
                """).append(criteria).append("\n\n已选候选：\n");
        for (int index = 0; index < selectedCandidates.size(); index++) {
            LarkMessageHistoryItem item = selectedCandidates.get(index);
            builder.append(index + 1)
                    .append(". id=").append(nullToEmpty(item.messageId()))
                    .append(" senderType=").append(nullToEmpty(item.senderType()))
                    .append(" sender=").append(firstNonBlank(item.senderName(), item.senderId(), "未知成员"))
                    .append(" content=").append(truncate(item.content(), 500))
                    .append("\n");
        }
        builder.append("""

                只输出 JSON：
                {"selectedMessageIds":["om_xxx"],"sufficient":true,"reason":""}
                """);
        return builder.toString();
    }

    private String extractJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static class SelectionDecision {
        public List<String> selectedMessageIds = new ArrayList<>();
        public boolean sufficient;
        public String reason;
    }
}
