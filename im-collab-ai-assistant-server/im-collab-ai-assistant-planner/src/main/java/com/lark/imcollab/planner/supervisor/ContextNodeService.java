package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class ContextNodeService {

    private final ReactAgent contextCollectorAgent;
    private final PlannerContextTool contextTool;
    private final PlannerRuntimeTool runtimeTool;
    private final PlannerConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;

    public ContextNodeService(
            @Qualifier("contextCollectorAgent") ReactAgent contextCollectorAgent,
            PlannerContextTool contextTool,
            PlannerRuntimeTool runtimeTool,
            PlannerConversationMemoryService memoryService,
            ObjectMapper objectMapper
    ) {
        this.contextCollectorAgent = contextCollectorAgent;
        this.contextTool = contextTool;
        this.runtimeTool = runtimeTool;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    public ContextSufficiencyResult check(
            PlanTaskSession session,
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        runtimeTool.projectStage(taskId, TaskEventTypeEnum.CONTEXT_CHECKING, "Checking task context");
        ContextSufficiencyResult guardedResult = contextTool.evaluateContext(session, rawInstruction, workspaceContext);
        if (guardedResult.sufficient() && isStrongLocalContext(guardedResult)) {
            return guardedResult;
        }
        Optional<ContextSufficiencyResult> modelResult = invokeContextAgent(session, taskId, rawInstruction, workspaceContext);
        if (modelResult.isPresent()) {
            ContextSufficiencyResult result = modelResult.get();
            if (!result.sufficient() && guardedResult.sufficient() && isStrongLocalContext(guardedResult)) {
                return guardedResult;
            }
            return result;
        }
        return guardedResult;
    }

    private Optional<ContextSufficiencyResult> invokeContextAgent(
            PlanTaskSession session,
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        try {
            Optional<OverAllState> state = contextCollectorAgent.invoke(
                    buildPrompt(session, rawInstruction, workspaceContext),
                    RunnableConfig.builder().threadId(taskId + ":planner:context-collector").build()
            );
            if (state.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> data = state.get().data();
            Object structured = data.get("messages") == null ? data.get("message") : data.get("messages");
            if (structured instanceof ContextSufficiencyResult result) {
                return Optional.of(result);
            }
            if (structured instanceof String text && !text.isBlank()) {
                return Optional.ofNullable(objectMapper.readValue(extractJson(text), ContextSufficiencyResult.class));
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String buildPrompt(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                You are the context sufficiency checker for Planner.
                Decide whether the available context is enough to create an executable plan.
                If workspace context only contains the user's latest instruction and no real source material, do NOT treat it as material to summarize.
                However, if the user instruction itself includes a concrete content body, topic details, or source material after a colon/newline, treat that embedded text as usable context.
                For vague requests like organizing, summarizing, or showing something to a stakeholder, the context is insufficient unless there are selected messages, document refs, attachments, or a concrete topic/material embedded in the instruction.
                If insufficient, return sufficient=false, missingItems, and one natural Chinese clarificationQuestion.
                Do not create plan steps.

                """);
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction).append("\n");
        builder.append("Conversation memory:\n").append(memoryService.renderContext(session)).append("\n");
        if (workspaceContext != null) {
            builder.append("Workspace context:\n");
            builder.append("selectedMessagesContainsOnlyLatestInstruction=")
                    .append(containsOnlyLatestInstruction(workspaceContext, rawInstruction))
                    .append("\n");
            if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
                builder.append(String.join("\n", workspaceContext.getSelectedMessages())).append("\n");
            }
            if (workspaceContext.getTimeRange() != null) {
                builder.append("timeRange=").append(workspaceContext.getTimeRange()).append("\n");
            }
        }
        builder.append("Return ContextSufficiencyResult JSON only.");
        return builder.toString();
    }

    private boolean containsOnlyLatestInstruction(WorkspaceContext workspaceContext, String rawInstruction) {
        if (workspaceContext == null
                || workspaceContext.getSelectedMessages() == null
                || workspaceContext.getSelectedMessages().size() != 1) {
            return false;
        }
        String onlyMessage = workspaceContext.getSelectedMessages().get(0);
        return normalize(onlyMessage).equals(normalize(rawInstruction))
                && (workspaceContext.getDocRefs() == null || workspaceContext.getDocRefs().isEmpty())
                && (workspaceContext.getAttachmentRefs() == null || workspaceContext.getAttachmentRefs().isEmpty())
                && (workspaceContext.getSelectedMessageIds() == null || workspaceContext.getSelectedMessageIds().isEmpty());
    }

    private boolean isStrongLocalContext(ContextSufficiencyResult result) {
        return result != null
                && result.reason() != null
                && (result.reason().contains("embedded instruction context")
                || result.reason().contains("external workspace context"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
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
}
