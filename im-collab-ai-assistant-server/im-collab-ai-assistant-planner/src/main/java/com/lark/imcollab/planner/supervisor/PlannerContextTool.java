package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PlannerContextTool {

    @Tool(description = "Scenario B: judge whether current user input and workspace context are sufficient for planner.")
    public ContextSufficiencyResult evaluateContext(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        String instruction = rawInstruction == null ? "" : rawInstruction.trim();
        List<String> contextParts = new ArrayList<>();
        boolean hasCollectedContext = false;
        if (workspaceContext != null) {
            if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
                contextParts.add("selectedMessages=" + workspaceContext.getSelectedMessages().size());
                hasCollectedContext = hasCollectedContext || !containsOnlyLatestInstruction(workspaceContext, instruction);
            }
            if (workspaceContext.getSelectedMessageIds() != null && !workspaceContext.getSelectedMessageIds().isEmpty()) {
                contextParts.add("selectedMessageIds=" + workspaceContext.getSelectedMessageIds().size());
            }
            if (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty()) {
                contextParts.add("docRefs=" + workspaceContext.getDocRefs().size());
            }
            if (workspaceContext.getAttachmentRefs() != null && !workspaceContext.getAttachmentRefs().isEmpty()) {
                contextParts.add("attachments=" + workspaceContext.getAttachmentRefs().size());
            }
            if (workspaceContext.getTimeRange() != null && !workspaceContext.getTimeRange().isBlank()) {
                contextParts.add("timeRange=" + workspaceContext.getTimeRange());
            }
        }
        if (instruction.isBlank()) {
            return ContextSufficiencyResult.insufficient(
                    List.of("user_instruction"),
                    "你希望我完成什么任务？",
                    "empty instruction"
            );
        }
        boolean hasEmbeddedContext = hasEmbeddedTaskMaterial(instruction);
        if (!hasCollectedContext && !hasEmbeddedContext && instruction.length() < 24) {
            return ContextSufficiencyResult.insufficient(
                    List.of("source_context"),
                    "你希望我基于哪些内容来整理？可以直接贴材料、文档链接，或说明要整理的消息范围；如果有偏好的产物形式也可以一起说。",
                    "instruction too short and no external context"
            );
        }
        String summary = "instruction=" + instruction
                + (contextParts.isEmpty() ? "" : "\nworkspaceContext=" + String.join(", ", contextParts));
        if (session != null && session.getClarifiedInstruction() != null && !session.getClarifiedInstruction().isBlank()) {
            summary += "\nclarifiedInstruction=" + session.getClarifiedInstruction();
        }
        String reason;
        if (hasCollectedContext) {
            reason = "external workspace context accepted for planner";
        } else if (hasEmbeddedContext) {
            reason = "embedded instruction context accepted for planner";
        } else {
            reason = "context accepted for planner";
        }
        return ContextSufficiencyResult.sufficient(summary, reason);
    }

    private boolean hasEmbeddedTaskMaterial(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String normalized = instruction.trim();
        int delimiter = Math.max(normalized.lastIndexOf('：'), normalized.lastIndexOf(':'));
        if (delimiter >= 0 && normalized.length() - delimiter - 1 >= 24) {
            return true;
        }
        return normalized.contains("\n") && normalized.length() >= 40;
    }

    private boolean containsOnlyLatestInstruction(WorkspaceContext workspaceContext, String instruction) {
        if (workspaceContext == null
                || workspaceContext.getSelectedMessages() == null
                || workspaceContext.getSelectedMessages().size() != 1) {
            return false;
        }
        String onlyMessage = workspaceContext.getSelectedMessages().get(0);
        String normalizedMessage = normalize(onlyMessage);
        String normalizedInstruction = normalize(instruction);
        return (normalizedMessage.equals(normalizedInstruction)
                || normalizedMessage.contains(normalizedInstruction)
                || normalizedInstruction.contains(normalizedMessage))
                && (workspaceContext.getDocRefs() == null || workspaceContext.getDocRefs().isEmpty())
                && (workspaceContext.getAttachmentRefs() == null || workspaceContext.getAttachmentRefs().isEmpty())
                && (workspaceContext.getSelectedMessageIds() == null || workspaceContext.getSelectedMessageIds().isEmpty());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }
}
