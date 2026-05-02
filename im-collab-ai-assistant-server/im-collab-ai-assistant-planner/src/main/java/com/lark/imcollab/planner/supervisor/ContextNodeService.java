package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ContextNodeService {

    private final ReactAgent contextCollectorAgent;
    private final ReactAgent contextAcquisitionAgent;
    private final PlannerContextTool contextTool;
    private final PlannerRuntimeTool runtimeTool;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerProperties plannerProperties;
    private final ObjectMapper objectMapper;

    public ContextNodeService(
            @Qualifier("contextCollectorAgent") ReactAgent contextCollectorAgent,
            @Qualifier("contextAcquisitionAgent") ReactAgent contextAcquisitionAgent,
            PlannerContextTool contextTool,
            PlannerRuntimeTool runtimeTool,
            PlannerConversationMemoryService memoryService,
            PlannerProperties plannerProperties,
            ObjectMapper objectMapper
    ) {
        this.contextCollectorAgent = contextCollectorAgent;
        this.contextAcquisitionAgent = contextAcquisitionAgent;
        this.contextTool = contextTool;
        this.runtimeTool = runtimeTool;
        this.memoryService = memoryService;
        this.plannerProperties = plannerProperties;
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

        ContextAcquisitionPlan requiredPlan = requiredReferenceAcquisitionPlan(workspaceContext, rawInstruction);
        if (requiredPlan != null && requiredPlan.isNeedCollection()) {
            return ContextSufficiencyResult.collect(requiredPlan, "workspace source refs require collection");
        }
        if (guardedResult.sufficient() && isStrongLocalContext(guardedResult)) {
            return guardedResult;
        }
        Optional<ContextAcquisitionPlan> acquisitionPlan = invokeAcquisitionAgent(session, taskId, rawInstruction, workspaceContext);
        if (acquisitionPlan.isPresent() && acquisitionPlan.get().isNeedCollection()) {
            return ContextSufficiencyResult.collect(acquisitionPlan.get(), acquisitionPlan.get().getReason());
        }
        if (acquisitionPlan.isPresent()
                && hasText(acquisitionPlan.get().getClarificationQuestion())
                && !(guardedResult.sufficient() && isStrongLocalContext(guardedResult))) {
            return ContextSufficiencyResult.insufficient(
                    List.of("source_context"),
                    acquisitionPlan.get().getClarificationQuestion(),
                    firstNonBlank(acquisitionPlan.get().getReason(), "context acquisition needs user input")
            );
        }
        if (isExplicitUnavailableSource(guardedResult)) {
            return guardedResult;
        }
        ContextAcquisitionPlan fallbackPlan = defaultAcquisitionPlan(workspaceContext);
        if (fallbackPlan != null && fallbackPlan.isNeedCollection()) {
            return ContextSufficiencyResult.collect(fallbackPlan, "available workspace source can be collected");
        }
        Optional<ContextSufficiencyResult> modelResult = invokeContextAgent(session, taskId, rawInstruction, workspaceContext);
        if (modelResult.isPresent()) {
            ContextSufficiencyResult result = modelResult.get();
            if (result.collectionRequired()) {
                return result;
            }
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
            String text = structuredText(structured);
            if (text != null && !text.isBlank()) {
                return Optional.ofNullable(objectMapper.readValue(extractJson(text), ContextSufficiencyResult.class));
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<ContextAcquisitionPlan> invokeAcquisitionAgent(
            PlanTaskSession session,
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        if (contextAcquisitionAgent == null
                || plannerProperties.getContextCollection() == null
                || !plannerProperties.getContextCollection().isEnabled()) {
            return Optional.empty();
        }
        try {
            Optional<OverAllState> state = contextAcquisitionAgent.invoke(
                    buildAcquisitionPrompt(session, rawInstruction, workspaceContext),
                    RunnableConfig.builder().threadId(taskId + ":planner:context-acquisition").build()
            );
            if (state.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> data = state.get().data();
            Object structured = data.get("messages") == null ? data.get("message") : data.get("messages");
            if (structured instanceof ContextAcquisitionPlan plan) {
                return Optional.of(plan);
            }
            String text = structuredText(structured);
            if (text != null && !text.isBlank()) {
                return Optional.ofNullable(objectMapper.readValue(extractJson(text), ContextAcquisitionPlan.class));
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

    private String buildAcquisitionPrompt(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                You are Planner's context acquisition planner.
                Decide whether the task needs external context to be pulled before planning.
                You may request only these sources: IM_HISTORY and LARK_DOC.
                Use IM_HISTORY when the user refers to recent/prior discussion, chat messages, "刚才", "群里", "聊天记录", or when the task is vague but a chatId/threadId is available.
                Use LARK_DOC when docRefs are available or the user asks to use/read/convert a Lark document.
                If selectedMessages already contain real source material, do not request collection.
                If no usable source id/ref exists, return needCollection=false and put a natural clarificationQuestion.
                Do not create plan steps and do not answer the user.
                Return ContextAcquisitionPlan JSON only:
                {"needCollection":true|false,"sources":[{"sourceType":"IM_HISTORY|LARK_DOC","chatId":"","threadId":"","timeRange":"","docRefs":[],"limit":30}],"reason":"","clarificationQuestion":""}

                """);
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction).append("\n");
        builder.append("Conversation memory:\n").append(memoryService.renderContext(session)).append("\n");
        builder.append("Workspace hints:\n");
        if (workspaceContext == null) {
            builder.append("none\n");
        } else {
            builder.append("chatId=").append(nullToEmpty(workspaceContext.getChatId())).append("\n");
            builder.append("threadId=").append(nullToEmpty(workspaceContext.getThreadId())).append("\n");
            builder.append("timeRange=").append(nullToEmpty(workspaceContext.getTimeRange())).append("\n");
            builder.append("selectedMessagesCount=").append(workspaceContext.getSelectedMessages() == null ? 0 : workspaceContext.getSelectedMessages().size()).append("\n");
            builder.append("selectedMessageIdsCount=").append(workspaceContext.getSelectedMessageIds() == null ? 0 : workspaceContext.getSelectedMessageIds().size()).append("\n");
            builder.append("docRefs=").append(workspaceContext.getDocRefs() == null ? List.of() : workspaceContext.getDocRefs()).append("\n");
        }
        return builder.toString();
    }

    private ContextAcquisitionPlan defaultAcquisitionPlan(WorkspaceContext workspaceContext) {
        if (workspaceContext == null
                || plannerProperties.getContextCollection() == null
                || !plannerProperties.getContextCollection().isEnabled()) {
            return null;
        }
        List<ContextSourceRequest> sources = new ArrayList<>();
        if (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty()) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.LARK_DOC)
                    .docRefs(workspaceContext.getDocRefs())
                    .limit(1)
                    .build());
        }
        if (hasText(workspaceContext.getThreadId()) || hasText(workspaceContext.getChatId())) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_HISTORY)
                    .chatId(workspaceContext.getChatId())
                    .threadId(workspaceContext.getThreadId())
                    .timeRange(workspaceContext.getTimeRange())
                    .limit(plannerProperties.getContextCollection().getMaxImMessages())
                    .build());
        }
        if (sources.isEmpty()) {
            return null;
        }
        return ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(sources)
                .reason("workspace source refs are available")
                .clarificationQuestion("")
                .build();
    }

    private ContextAcquisitionPlan requiredReferenceAcquisitionPlan(WorkspaceContext workspaceContext) {
        if (workspaceContext == null
                || plannerProperties.getContextCollection() == null
                || !plannerProperties.getContextCollection().isEnabled()) {
            return null;
        }
        List<ContextSourceRequest> sources = new ArrayList<>();
        if (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty()) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.LARK_DOC)
                    .docRefs(workspaceContext.getDocRefs())
                    .limit(1)
                    .build());
        }
        if (workspaceContext.getSelectedMessageIds() != null && !workspaceContext.getSelectedMessageIds().isEmpty()) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_HISTORY)
                    .chatId(workspaceContext.getChatId())
                    .threadId(workspaceContext.getThreadId())
                    .timeRange(workspaceContext.getTimeRange())
                    .limit(plannerProperties.getContextCollection().getMaxImMessages())
                    .build());
        }
        if (hasText(workspaceContext.getTimeRange())
                && (hasText(workspaceContext.getChatId()) || hasText(workspaceContext.getThreadId()))
                && !hasRealSelectedMessages(workspaceContext)) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_HISTORY)
                    .chatId(workspaceContext.getChatId())
                    .threadId(workspaceContext.getThreadId())
                    .timeRange(workspaceContext.getTimeRange())
                    .limit(plannerProperties.getContextCollection().getMaxImMessages())
                    .build());
        }
        if (sources.isEmpty()) {
            return null;
        }
        return ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(sources)
                .reason("workspace references must be resolved before planning")
                .clarificationQuestion("")
                .build();
    }

    private ContextAcquisitionPlan requiredReferenceAcquisitionPlan(WorkspaceContext workspaceContext, String rawInstruction) {
        ContextAcquisitionPlan plan = requiredReferenceAcquisitionPlan(workspaceContext);
        if (plan != null) {
            return plan;
        }
        if (workspaceContext == null
                || plannerProperties.getContextCollection() == null
                || !plannerProperties.getContextCollection().isEnabled()) {
            return null;
        }
        if (!containsOnlyLatestInstruction(workspaceContext, rawInstruction)
                || !refersToConversationContext(rawInstruction)
                || (!hasText(workspaceContext.getChatId()) && !hasText(workspaceContext.getThreadId()))) {
            return null;
        }
        return ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(List.of(ContextSourceRequest.builder()
                        .sourceType(ContextSourceTypeEnum.IM_HISTORY)
                        .chatId(workspaceContext.getChatId())
                        .threadId(workspaceContext.getThreadId())
                        .timeRange(workspaceContext.getTimeRange())
                        .limit(plannerProperties.getContextCollection().getMaxImMessages())
                        .build()))
                .reason("user refers to prior conversation context")
                .clarificationQuestion("")
                .build();
    }

    private boolean hasRealSelectedMessages(WorkspaceContext workspaceContext) {
        return workspaceContext != null
                && workspaceContext.getSelectedMessages() != null
                && !workspaceContext.getSelectedMessages().isEmpty();
    }

    private boolean refersToConversationContext(String rawInstruction) {
        String normalized = normalize(rawInstruction);
        return normalized.contains("刚才")
                || normalized.contains("上面")
                || normalized.contains("前面")
                || normalized.contains("之前")
                || normalized.contains("最近")
                || normalized.contains("这段时间")
                || normalized.contains("讨论")
                || normalized.contains("聊天")
                || normalized.contains("消息");
    }

    private boolean containsOnlyLatestInstruction(WorkspaceContext workspaceContext, String rawInstruction) {
        if (workspaceContext == null
                || workspaceContext.getSelectedMessages() == null
                || workspaceContext.getSelectedMessages().size() != 1) {
            return false;
        }
        String onlyMessage = workspaceContext.getSelectedMessages().get(0);
        String normalizedMessage = normalize(onlyMessage);
        String normalizedInstruction = normalize(rawInstruction);
        return (normalizedMessage.equals(normalizedInstruction)
                || normalizedMessage.contains(normalizedInstruction)
                || normalizedInstruction.contains(normalizedMessage))
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

    private boolean isExplicitUnavailableSource(ContextSufficiencyResult result) {
        return result != null
                && !result.sufficient()
                && result.reason() != null
                && result.reason().contains("source explicitly unavailable");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private String structuredText(Object structured) {
        if (structured instanceof String text) {
            return text;
        }
        if (structured instanceof AssistantMessage message) {
            return message.getText();
        }
        return null;
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
