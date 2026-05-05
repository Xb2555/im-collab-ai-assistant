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
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (requiredPlan != null && hasText(requiredPlan.getClarificationQuestion())) {
            return ContextSufficiencyResult.insufficient(
                    List.of("source_context"),
                    requiredPlan.getClarificationQuestion(),
                    firstNonBlank(requiredPlan.getReason(), "workspace source needs user input")
            );
        }
        Optional<ContextAcquisitionPlan> acquisitionPlan = invokeAcquisitionAgent(session, taskId, rawInstruction, workspaceContext);
        if (acquisitionPlan.isPresent() && acquisitionPlan.get().isNeedCollection()) {
            return ContextSufficiencyResult.collect(acquisitionPlan.get(), acquisitionPlan.get().getReason());
        }
        ContextSufficiencyResult acquisitionClarification = null;
        if (acquisitionPlan.isPresent()
                && hasText(acquisitionPlan.get().getClarificationQuestion())
                && !(guardedResult.sufficient() && isStrongLocalContext(guardedResult))) {
            acquisitionClarification = ContextSufficiencyResult.insufficient(
                    List.of("source_context"),
                    acquisitionPlan.get().getClarificationQuestion(),
                    firstNonBlank(acquisitionPlan.get().getReason(), "context acquisition needs user input")
            );
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
        if (guardedResult.sufficient() && isStrongLocalContext(guardedResult)) {
            return guardedResult;
        }
        if (acquisitionClarification != null) {
            return acquisitionClarification;
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
            return extractStructured(structured, ContextSufficiencyResult.class);
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
            String prompt = buildAcquisitionPrompt(session, rawInstruction, workspaceContext);
            Optional<ContextAcquisitionPlan> plan = invokeAcquisitionAgentOnce(
                    prompt,
                    RunnableConfig.builder().threadId(taskId + ":planner:context-acquisition").build()
            );
            if (plan.isPresent() && hasInvalidImSearchTimeRange(plan.get())) {
                Optional<ContextAcquisitionPlan> repaired = invokeAcquisitionAgentOnce(
                        buildTimeRangeRepairPrompt(rawInstruction, workspaceContext, plan.get()),
                        RunnableConfig.builder().threadId(taskId + ":planner:context-acquisition:repair").build()
                );
                if (repaired.isPresent()) {
                    return repaired;
                }
            }
            return plan;
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<ContextAcquisitionPlan> invokeAcquisitionAgentOnce(String prompt, RunnableConfig config) throws Exception {
        Optional<OverAllState> state = contextAcquisitionAgent.invoke(prompt, config);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = state.get().data();
        Object structured = data.get("messages") == null ? data.get("message") : data.get("messages");
        return extractStructured(structured, ContextAcquisitionPlan.class);
    }

    private String buildTimeRangeRepairPrompt(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan invalidPlan
    ) {
        return """
                You are a strict ContextAcquisitionPlan JSON repairer.
                The previous plan is invalid because an IM_MESSAGE_SEARCH source has a timeRange but omitted startTime/endTime.

                Return the corrected ContextAcquisitionPlan JSON only.
                Preserve sourceType, chatId, threadId, query, selectionInstruction, limit, pageSize, and pageLimit.
                Preserve the user's original time phrase in timeRange.
                For every IM_MESSAGE_SEARCH source with any time condition, fill startTime and endTime as ISO_OFFSET_DATE_TIME strings.

                Current time: %s
                User instruction: %s
                Workspace chatId: %s
                Workspace threadId: %s
                Workspace chatType: %s

                Common relative time rules using Current time timezone:
                - 昨天下午: yesterday 12:00:00 to yesterday 18:00:00.
                - 昨天上午: yesterday 00:00:00 to yesterday 12:00:00.
                - 昨天: yesterday 00:00:00 to today 00:00:00.
                - 今天上午: today 00:00:00 to today 12:00:00.
                - 今天下午: today 12:00:00 to today 18:00:00.
                - 今天: today 00:00:00 to current time.
                - 最近N分钟/小时: current time minus N minutes/hours to current time.
                - N分钟前: a 10-minute window centered at N minutes before current time.

                Do not return a clarification for these common relative time expressions. Resolve them.
                If and only if the phrase is genuinely ambiguous, return {"needCollection":false,"sources":[],"reason":"ambiguous time range","clarificationQuestion":"请补充具体开始和结束时间。"}.

                Previous invalid JSON:
                %s
                """.formatted(
                currentTime(),
                rawInstruction == null ? "" : rawInstruction,
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getChatId()),
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getThreadId()),
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getChatType()),
                safeJson(invalidPlan)
        );
    }

    private boolean hasInvalidImSearchTimeRange(ContextAcquisitionPlan plan) {
        if (plan == null || !plan.isNeedCollection() || plan.getSources() == null) {
            return false;
        }
        return plan.getSources().stream().anyMatch(source -> source != null
                && source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH
                && hasText(source.getTimeRange())
                && (!hasText(source.getStartTime()) || !hasText(source.getEndTime())));
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String buildPrompt(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                You are the context sufficiency checker for Planner.
                Decide whether the available context is enough to create an executable plan.
                If workspace context only contains the user's latest instruction and no real source material, do NOT treat it as material to summarize.
                However, if the user instruction itself includes a concrete content body, topic details, or source material after a colon/newline, treat that embedded text as usable context.
                This embedded-material decision is semantic: judge the whole user message, not only keywords or punctuation.
                Inline task context is enough when it contains concrete facts such as goals, completed work, current progress, risks, decisions, next steps, metrics, requirements, or source excerpts.
                Example sufficient inline context: "请生成项目进展摘要。上下文：目标是验证 SUMMARY；已完成 A/B；风险是 C；下一步做 D。"
                Example insufficient request: "帮我整理一下，给老板看" without selected messages, document refs, retrievable chat range, or embedded facts.
                Conversation memory is only background for continuity; it is not source material for a new task.
                If the current user message starts a new task but only says a generic action like organizing or summarizing, do not infer a concrete topic from old memory.
                Ask what material/range/topic/output the user wants instead.
                Treat words like "plan", "three steps", "do not execute yet", "outline", or "draft" as planning constraints, not as source material.
                If the user asks for a project review, project summary, proposal, report, or stakeholder-facing document/PPT/summary but provides no project material,
                selected messages, document refs, attachments, or retrievable message range, mark the context insufficient and ask for the missing material.
                Only allow planning without source material when the user explicitly asks for a generic template, generic framework, or blank outline.
                If the user asks to pull/read/filter/summarize messages from the current chat or group and workspace context has chatId/threadId,
                return collectionRequired=true with an IM_MESSAGE_SEARCH acquisitionPlan.
                Copy the keyword into query when present, copy the whole criteria into selectionInstruction, and copy the time range into timeRange when present.
                HARD REQUIREMENT for IM_MESSAGE_SEARCH time filters:
                - If the user mentions any time expression, startTime and endTime MUST be non-empty ISO_OFFSET_DATE_TIME strings.
                - Keep the original phrase in timeRange for traceability, but never output only a vague timeRange such as "昨天" or "昨天下午".
                - Use Current time below as the reference clock and local timezone.
                - Examples: "昨天下午" means yesterday 12:00:00 to 18:00:00 in the Current time timezone; "今天上午" means today 00:00:00 to 12:00:00; "昨天" means yesterday 00:00:00 to today 00:00:00.
                - If you cannot infer startTime/endTime, return sufficient=false with a clarificationQuestion instead of collectionRequired=true.
                If the user asks to summarize "recent/prior discussion" or "the previous discussion about topic A/B/C",
                the topic names are only retrieval criteria, not source material. With chatId/threadId available, return collectionRequired=true.
                Do not treat the pull instruction itself as the messages to summarize.
                For vague requests like organizing, summarizing, or showing something to a stakeholder, the context is insufficient unless there are selected messages, document refs, attachments, or a concrete topic/material embedded in the instruction.
                Decision examples:
                - "新开一个任务：帮我整理一下" => insufficient, ask what material/range/topic/output to organize; do not collect IM history.
                - "帮我整理一下刚才关于供应商评审的讨论" with chatId => collectionRequired=true for IM_MESSAGE_SEARCH, query="供应商评审", selectionInstruction copies the whole request.
                - "把本群最近 10 分钟关于风险的消息整理成摘要" with chatId => collectionRequired=true for IM_MESSAGE_SEARCH, query="风险", timeRange="最近10分钟".
                If insufficient, return sufficient=false, missingItems, and one natural Chinese clarificationQuestion.
                Do not create plan steps.

                """);
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction).append("\n");
        builder.append("Conversation memory:\n").append(memoryService.renderContext(session)).append("\n");
        if (workspaceContext != null) {
            builder.append("Workspace context:\n");
            builder.append("chatId=").append(nullToEmpty(workspaceContext.getChatId())).append("\n");
            builder.append("threadId=").append(nullToEmpty(workspaceContext.getThreadId())).append("\n");
            builder.append("chatType=").append(nullToEmpty(workspaceContext.getChatType())).append("\n");
            builder.append("inputSource=").append(nullToEmpty(workspaceContext.getInputSource())).append("\n");
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
        builder.append("""
                Return ContextSufficiencyResult JSON only.
                When collectionRequired=true, include acquisitionPlan:
                {"sufficient":false,"contextSummary":"","missingItems":["source_context"],"clarificationQuestion":"","reason":"","collectionRequired":true,"acquisitionPlan":{"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"","threadId":"","query":"","timeRange":"","startTime":"","endTime":"","selectionInstruction":"","limit":30}],"reason":"","clarificationQuestion":""}}
                """);
        builder.append("Current time: ").append(currentTime()).append("\n");
        return builder.toString();
    }

    private String buildAcquisitionPrompt(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                You are Planner's context acquisition planner.
                Decide whether the task needs external context to be pulled before planning.
                You may request only these sources: IM_MESSAGE_SEARCH and LARK_DOC.
                Treat words like "plan", "three steps", "do not execute yet", "outline", or "draft" as planning constraints, not source material.
                If the user instruction itself includes concrete task context, background facts, current progress, risks, decisions, or source material, treat that as inline material and do not ask for external source just because workspace refs are empty.
                Judge inline material semantically from the whole message; do not rely on keywords or a single delimiter.
                Inline task context is enough when it contains goals, completed work, current progress, risks, decisions, next steps, metrics, requirements, or source excerpts.
                If a task depends on project facts, review facts, prior discussion, a referenced document, or stakeholder-facing content and those facts are absent,
                either request a concrete source or ask a natural clarification question. Do not let planning proceed on the user's request text alone.
                Conversation memory is not an external source to collect. For a new task, do not reuse the previous task topic from memory unless the current user message explicitly points to that previous task or a specific recent discussion/topic/range.
                If the current request is only a generic action such as "help me organize/summarize it" and gives no source, range, topic, document, selected messages, or embedded facts, return needCollection=false with a clarificationQuestion.
                Use IM_MESSAGE_SEARCH for all IM message retrieval, including historical keyword search, immediate windows such as "刚才/上面", explicit selected/time range contexts, and mixed keyword+time constraints.
                If the user refers to group messages but the current chat is p2p/private, do NOT request IM_MESSAGE_SEARCH from the private chat; ask the user to provide the group, time range, selected messages, or paste the material.
                Use LARK_DOC when docRefs are available or the user asks to use/read/convert a Lark document.
                If selectedMessages already contain real source material, do not request collection.
                The current user message itself is not selected source material unless selectedMessages contains additional real material.
                If no usable source id/ref exists, return needCollection=false and put a natural clarificationQuestion.
                Decision examples:
                - User says "新开一个任务：帮我整理一下" in a group: {"needCollection":false,"sources":[],"reason":"generic task without source scope","clarificationQuestion":"你想整理哪部分材料？可以告诉我时间范围、主题，或直接贴几条消息。"}
                - User says "整理历史消息中有关采购评审的讨论" in a group with chatId: request IM_MESSAGE_SEARCH with query "采购评审".
                - User says "帮我整理一下刚才关于供应商评审的讨论" in a group with chatId: request IM_MESSAGE_SEARCH with query "供应商评审" and the full sentence as selectionInstruction.
                - User says "本群最近 10 分钟带有 A 标记的风险消息整理成摘要" in a group with chatId: request IM_MESSAGE_SEARCH with query "风险", timeRange "最近10分钟", and that full filter.
                - User says "10分钟前关于采购评审的消息" in a group with chatId: request IM_MESSAGE_SEARCH with query "采购评审" and timeRange "10分钟前".
                Do not create plan steps and do not answer the user.
                For IM_MESSAGE_SEARCH, copy the user's exact message selection criteria into selectionInstruction.
                Examples of selection criteria include time window, topics, required marker/text, excluded marker/text, sender constraints, and "if none found then ask me".
                HARD REQUIREMENT for IM_MESSAGE_SEARCH time filters:
                - If the user mentions any time expression, startTime and endTime MUST be non-empty ISO_OFFSET_DATE_TIME strings.
                - Keep the original phrase in timeRange for traceability, but never output only a vague timeRange such as "昨天" or "昨天下午".
                - Use Current time below as the reference clock and local timezone.
                - Examples: "昨天下午" means yesterday 12:00:00 to 18:00:00 in the Current time timezone; "今天上午" means today 00:00:00 to 12:00:00; "昨天" means yesterday 00:00:00 to today 00:00:00.
                - If you cannot infer startTime/endTime, return needCollection=false with a clarificationQuestion instead of returning an IM_MESSAGE_SEARCH source.

                Return ContextAcquisitionPlan JSON only:
                {"needCollection":true|false,"sources":[{"sourceType":"IM_MESSAGE_SEARCH|LARK_DOC","chatId":"","threadId":"","query":"","timeRange":"","startTime":"","endTime":"","docRefs":[],"selectionInstruction":"","limit":30}],"reason":"","clarificationQuestion":""}

                """);
        builder.append("Current time: ").append(currentTime()).append("\n");
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction).append("\n");
        builder.append("Conversation memory:\n").append(memoryService.renderContext(session)).append("\n");
        builder.append("Workspace hints:\n");
        if (workspaceContext == null) {
            builder.append("none\n");
        } else {
            builder.append("chatId=").append(nullToEmpty(workspaceContext.getChatId())).append("\n");
            builder.append("threadId=").append(nullToEmpty(workspaceContext.getThreadId())).append("\n");
            builder.append("timeRange=").append(nullToEmpty(workspaceContext.getTimeRange())).append("\n");
            builder.append("chatType=").append(nullToEmpty(workspaceContext.getChatType())).append("\n");
            builder.append("inputSource=").append(nullToEmpty(workspaceContext.getInputSource())).append("\n");
            builder.append("selectedMessagesCount=").append(workspaceContext.getSelectedMessages() == null ? 0 : workspaceContext.getSelectedMessages().size()).append("\n");
            builder.append("selectedMessageIdsCount=").append(workspaceContext.getSelectedMessageIds() == null ? 0 : workspaceContext.getSelectedMessageIds().size()).append("\n");
            builder.append("docRefs=").append(workspaceContext.getDocRefs() == null ? List.of() : workspaceContext.getDocRefs()).append("\n");
        }
        return builder.toString();
    }

    private String currentTime() {
        return java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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
        if (hasExplicitImReference(workspaceContext)) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
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
                    .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                    .chatId(workspaceContext.getChatId())
                    .threadId(workspaceContext.getThreadId())
                    .timeRange(workspaceContext.getTimeRange())
                    .limit(plannerProperties.getContextCollection().getMaxImMessages())
                    .build());
        }
        if (isExplicitTimeRangeSelection(workspaceContext)
                && (hasText(workspaceContext.getChatId()) || hasText(workspaceContext.getThreadId()))
        ) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
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
        ContextAcquisitionPlan plan = withSelectionInstruction(
                requiredReferenceAcquisitionPlan(workspaceContext),
                rawInstruction
        );
        if (workspaceContext == null
                || plannerProperties.getContextCollection() == null
                || !plannerProperties.getContextCollection().isEnabled()
                || !referencesCurrentConversation(rawInstruction)
                || isPrivateConversationSource(workspaceContext)
                || (!hasText(workspaceContext.getChatId()) && !hasText(workspaceContext.getThreadId()))
                || hasRealSelectedMessages(workspaceContext)) {
            return plan;
        }
        String query = extractSearchQuery(rawInstruction);
        List<ContextSourceRequest> sources = plan == null || plan.getSources() == null
                ? new ArrayList<>()
                : new ArrayList<>(plan.getSources());
        sources.add(ContextSourceRequest.builder()
                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                .chatId(workspaceContext.getChatId())
                .threadId(workspaceContext.getThreadId())
                .query(query)
                .timeRange(hasRelativeTimeReference(rawInstruction) ? rawInstruction : workspaceContext.getTimeRange())
                .selectionInstruction(rawInstruction)
                .pageSize(50)
                .pageLimit(hasText(query) ? 5 : 1)
                .limit(plannerProperties.getContextCollection().getMaxImMessages())
                .build());
        return ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(sources)
                .reason("conversation references must be resolved before planning")
                .clarificationQuestion("")
                .build();
    }

    private ContextAcquisitionPlan withSelectionInstruction(ContextAcquisitionPlan plan, String rawInstruction) {
        if (plan == null || plan.getSources() == null || plan.getSources().isEmpty() || !hasText(rawInstruction)) {
            return plan;
        }
        List<ContextSourceRequest> sources = plan.getSources().stream()
                .map(source -> {
                    if (source == null
                            || (source.getSourceType() != ContextSourceTypeEnum.IM_HISTORY
                            && source.getSourceType() != ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                            || hasText(source.getSelectionInstruction())) {
                        return source;
                    }
                    String query = source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH
                            ? firstNonBlank(source.getQuery(), extractSearchQuery(rawInstruction))
                            : source.getQuery();
                    return ContextSourceRequest.builder()
                            .sourceType(source.getSourceType())
                            .chatId(source.getChatId())
                            .threadId(source.getThreadId())
                            .timeRange(source.getTimeRange())
                            .startTime(source.getStartTime())
                            .endTime(source.getEndTime())
                            .query(query)
                            .docRefs(source.getDocRefs())
                            .selectionInstruction(rawInstruction)
                            .limit(source.getLimit())
                            .pageSize(source.getPageSize())
                            .pageLimit(source.getPageLimit())
                            .build();
                })
                .toList();
        return ContextAcquisitionPlan.builder()
                .needCollection(plan.isNeedCollection())
                .sources(sources)
                .reason(plan.getReason())
                .clarificationQuestion(plan.getClarificationQuestion())
                .build();
    }

    private boolean hasRealSelectedMessages(WorkspaceContext workspaceContext) {
        return workspaceContext != null
                && workspaceContext.getSelectedMessages() != null
                && !workspaceContext.getSelectedMessages().isEmpty();
    }

    private boolean hasExplicitImReference(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || (!hasText(workspaceContext.getChatId()) && !hasText(workspaceContext.getThreadId()))) {
            return false;
        }
        if (workspaceContext.getSelectedMessageIds() != null && !workspaceContext.getSelectedMessageIds().isEmpty()) {
            return true;
        }
        if (isExplicitTimeRangeSelection(workspaceContext)) {
            return true;
        }
        return false;
    }

    private boolean referencesCurrentConversation(String rawInstruction) {
        if (!hasText(rawInstruction)) {
            return false;
        }
        String text = normalize(rawInstruction);
        boolean temporalReference = text.matches(".*(刚才|前面|上面|最近|之前|历史消息|本群|群里|聊天记录|这段对话|\\d{1,4}(分钟|分|小时|时|天|日)前).*");
        boolean conversationMaterial = text.matches(".*(讨论|聊|消息|对话|记录).*");
        boolean asksForSynthesis = text.matches(".*(整理|总结|汇总|生成|写成|输出).*");
        return asksForSynthesis && (text.contains("本群") || text.contains("群里") || (temporalReference && conversationMaterial));
    }

    static String extractSearchQuery(String rawInstruction) {
        if (!hasStaticText(rawInstruction)) {
            return "";
        }
        String text = rawInstruction.trim()
                .replaceAll("@[_a-zA-Z0-9\\-]+\\s*", "")
                .replaceAll("<at\\b[^>]*>[^<]*</at>\\s*", "")
                .trim();
        List<Pattern> patterns = List.of(
                Pattern.compile("有关(.{2,40}?)(?:的)?(?:讨论|消息|记录|聊天|内容)"),
                Pattern.compile("关于(.{2,40}?)(?:的)?(?:讨论|消息|记录|聊天|内容)"),
                Pattern.compile("(.{2,40}?)(?:相关|有关|关于)(?:的)?(?:讨论|消息|记录|聊天|内容)")
        );
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return cleanSearchQuery(matcher.group(1));
            }
        }
        return "";
    }

    private static String cleanSearchQuery(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("^(最近|近|之前|历史|历史消息|聊天记录|本群|群里|当前|有关|关于|和|与|中|\\d{1,4}\\s*(分钟|分|小时|时|天|日)前)+", "")
                .replaceAll("(整理|总结|汇总|输出|生成|写成|文档|ppt|PPT|分析)+$", "")
                .trim();
        return cleaned.length() > 40 ? cleaned.substring(0, 40).trim() : cleaned;
    }

    private boolean hasRelativeTimeReference(String rawInstruction) {
        if (!hasText(rawInstruction)) {
            return false;
        }
        String text = normalize(rawInstruction);
        return text.matches(".*(刚才|刚刚|前面|上面|这段对话|当前讨论|最近\\d{0,4}(分钟|分|小时|时)|\\d{1,4}(分钟|分|小时|时|天|日)前).*");
    }

    private boolean usesImmediateConversationWindow(String rawInstruction) {
        if (!hasText(rawInstruction)) {
            return false;
        }
        String text = normalize(rawInstruction);
        return text.matches(".*(刚才|刚刚|前面|上面|这段对话|当前讨论|最近\\d{0,4}(分钟|分|小时|时)).*");
    }

    private static boolean hasStaticText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isPrivateConversationSource(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return false;
        }
        String chatType = normalize(workspaceContext.getChatType());
        String inputSource = normalize(workspaceContext.getInputSource());
        return "p2p".equals(chatType) || "larkprivatechat".equals(inputSource) || "lark_private_chat".equals(inputSource);
    }

    private boolean isExplicitTimeRangeSelection(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || !hasText(workspaceContext.getTimeRange())) {
            return false;
        }
        String selectionType = normalize(workspaceContext.getSelectionType());
        return "timerange".equals(selectionType)
                || "time_range".equals(selectionType);
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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

    private <T> Optional<T> extractStructured(Object value, Class<T> type) {
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        if (value instanceof AssistantMessage assistantMessage) {
            return parseText(assistantMessage.getText(), type);
        }
        if (value instanceof Message message) {
            return parseText(message.getText(), type);
        }
        if (value instanceof CharSequence text) {
            return parseText(text.toString(), type);
        }
        if (value instanceof Map<?, ?> map) {
            try {
                return Optional.of(objectMapper.convertValue(map, type));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            Collections.reverse(values);
            for (Object item : values) {
                Optional<T> parsed = extractStructured(item, type);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private <T> Optional<T> parseText(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(extractJson(text), type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
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
