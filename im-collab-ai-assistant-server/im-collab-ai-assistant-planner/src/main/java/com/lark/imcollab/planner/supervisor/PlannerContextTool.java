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
            if (workspaceContext.getSourceArtifacts() != null && !workspaceContext.getSourceArtifacts().isEmpty()) {
                contextParts.add("sourceArtifacts=" + workspaceContext.getSourceArtifacts().size());
                hasCollectedContext = true;
            }
            if (workspaceContext.getAttachmentRefs() != null && !workspaceContext.getAttachmentRefs().isEmpty()) {
                contextParts.add("attachments=" + workspaceContext.getAttachmentRefs().size());
                hasCollectedContext = true;
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
        if (!hasCollectedContext) {
            return ContextSufficiencyResult.insufficient(
                    List.of("source_context"),
                    "这份内容要基于哪些材料来做？可以直接贴项目背景、文档链接，或说明要整理的消息范围；如果只是要一个通用模板，也可以直接告诉我。",
                    "no source material or embedded content"
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
        } else {
            reason = "context accepted for planner";
        }
        return ContextSufficiencyResult.sufficient(summary, reason);
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
                && (workspaceContext.getSourceArtifacts() == null || workspaceContext.getSourceArtifacts().isEmpty())
                && (workspaceContext.getAttachmentRefs() == null || workspaceContext.getAttachmentRefs().isEmpty())
                && (workspaceContext.getSelectedMessageIds() == null || workspaceContext.getSelectedMessageIds().isEmpty());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }
}
