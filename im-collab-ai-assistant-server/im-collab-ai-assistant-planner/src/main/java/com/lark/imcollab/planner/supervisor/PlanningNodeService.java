package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.ClarificationSourceMaterialAssessment;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.SourceArtifactRef;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.prompt.AgentPromptContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class PlanningNodeService {

    private final ReactAgent intentAgent;
    private final ReactAgent planningAgent;
    private final PlannerSessionService sessionService;
    private final PlanQualityService qualityService;
    private final TaskRuntimeProjectionService projectionService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerQuestionTool questionTool;
    private final ContextAcquisitionNodeService contextAcquisitionNodeService;
    private final ReactAgent clarificationSourceMaterialJudgeAgent;
    private final ObjectMapper objectMapper;

    @Autowired
    public PlanningNodeService(
            @Qualifier("intentAgent") ReactAgent intentAgent,
            @Qualifier("planningAgent") ReactAgent planningAgent,
            PlannerSessionService sessionService,
            PlanQualityService qualityService,
            TaskRuntimeProjectionService projectionService,
            PlannerConversationMemoryService memoryService,
            PlannerQuestionTool questionTool,
            ContextAcquisitionNodeService contextAcquisitionNodeService,
            @Qualifier("clarificationSourceMaterialJudgeAgent") ReactAgent clarificationSourceMaterialJudgeAgent,
            ObjectMapper objectMapper
    ) {
        this.intentAgent = intentAgent;
        this.planningAgent = planningAgent;
        this.sessionService = sessionService;
        this.qualityService = qualityService;
        this.projectionService = projectionService;
        this.memoryService = memoryService;
        this.questionTool = questionTool;
        this.contextAcquisitionNodeService = contextAcquisitionNodeService;
        this.clarificationSourceMaterialJudgeAgent = clarificationSourceMaterialJudgeAgent;
        this.objectMapper = objectMapper;
    }

    PlanningNodeService(
            ReactAgent intentAgent,
            ReactAgent planningAgent,
            PlannerSessionService sessionService,
            PlanQualityService qualityService,
            TaskRuntimeProjectionService projectionService,
            PlannerConversationMemoryService memoryService,
            PlannerQuestionTool questionTool,
            ObjectMapper objectMapper
    ) {
        this(intentAgent, planningAgent, sessionService, qualityService, projectionService, memoryService,
                questionTool, null, null, objectMapper);
    }

    public PlanTaskSession plan(String taskId, String rawInstruction, WorkspaceContext workspaceContext, String userFeedback) {
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        if (!hasText(session.getRawInstruction()) && hasText(rawInstruction)) {
            session.setRawInstruction(rawInstruction.trim());
        }
        String workspacePromptContext = renderWorkspaceContext(workspaceContext, rawInstruction);
        String conversationMemory = memoryService.renderContext(session);
        String planningInput = buildPlanningInput(session, rawInstruction, workspaceContext, userFeedback, conversationMemory);
        projectionService.projectStage(session, TaskEventTypeEnum.INTENT_ROUTING, "Understanding user intent");
        Optional<IntentSnapshot> intent = invokeAgent(
                intentAgent,
                planningInput,
                config(taskId, "intent", session, rawInstruction, workspacePromptContext, conversationMemory),
                IntentSnapshot.class);
        if (intent.isEmpty()) {
            questionTool.askUser(session, java.util.List.of("我还需要确认一下：这次任务要产出文档、PPT，还是摘要？"));
            return sessionService.get(taskId);
        }
        qualityService.applyIntentReady(session, intent.get());
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "INTENT_READY");
        ContextCollectionOutcome intentCollection = null;
        if (!feedbackCanSatisfyIntentSource(session, intent.get(), userFeedback)) {
            intentCollection = collectContextFromIntentSource(
                    taskId,
                    rawInstruction,
                    workspaceContext,
                    intent.get()
            );
        } else if (intent.get().getSourceScope() != null) {
            log.info("Skip intent source collection because clarification feedback provides usable context: taskId={}", taskId);
        }
        if (intentCollection != null) {
            if (intentCollection.contextResult() != null && !intentCollection.contextResult().sufficient()) {
                questionTool.askUser(session, List.of(firstNonBlank(
                        intentCollection.contextResult().clarificationQuestion(),
                        "我还需要你提供要整理的聊天内容或文档链接。"
                )));
                return sessionService.get(taskId);
            }
            workspaceContext = intentCollection.workspaceContext();
            workspacePromptContext = renderWorkspaceContext(workspaceContext, rawInstruction);
            planningInput = buildPlanningInput(session, rawInstruction, workspaceContext, userFeedback, conversationMemory);
        }
        if (shouldPauseForMissingSlots(session, intent.get(), planningInput)) {
            questionTool.askUser(session, List.of(buildMissingSlotQuestion(intent.get())));
            return sessionService.get(taskId);
        }

        projectionService.projectStage(session, TaskEventTypeEnum.PLANNING_STARTED, "Generating task plan");
        Optional<PlanBlueprint> blueprint = invokeAgent(
                planningAgent,
                planningInput + "\n\nIntent snapshot:\n" + toJson(intent.get()),
                config(taskId, "planning", session, rawInstruction, workspacePromptContext, conversationMemory),
                PlanBlueprint.class
        );
        if (blueprint.isPresent() && !hasPlanCards(blueprint.get())) {
            log.warn("Planning agent returned empty planCards, repairing once: taskId={}", taskId);
            blueprint = repairPlanBlueprint(taskId, planningInput, intent.get(), blueprint.get());
        }
        if (blueprint.isPresent() && !hasPlanCards(blueprint.get())) {
            log.warn("Planning repair still returned empty planCards, building bounded executable plan: taskId={}", taskId);
            blueprint = buildBoundedExecutablePlan(taskId, planningInput, intent.get());
        }
        if (blueprint.isEmpty()) {
            log.warn("Planning agent returned empty output, building bounded executable plan: taskId={}", taskId);
            blueprint = buildBoundedExecutablePlan(taskId, planningInput, intent.get());
        }
        if (blueprint.isEmpty()) {
            questionTool.askUser(session, java.util.List.of("我还需要补充一点信息，才能生成稳定计划：你希望最终交付物是什么？"));
            return sessionService.get(taskId);
        }
        PlanBlueprint readyBlueprint = blueprint.get();
        attachCollectedSourceScope(readyBlueprint, workspaceContext);
        normalizeCollectedContextWording(readyBlueprint, workspaceContext);
        qualityService.applyPlanReady(session, readyBlueprint);
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private ContextCollectionOutcome collectContextFromIntentSource(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            IntentSnapshot intentSnapshot
    ) {
        if (contextAcquisitionNodeService == null
                || hasWorkspaceContextMaterial(workspaceContext)
                || intentSnapshot == null
                || intentSnapshot.getSourceScope() == null) {
            return null;
        }
        WorkspaceContext intentScope = intentSnapshot.getSourceScope();
        List<ContextSourceRequest> sources = new ArrayList<>();
        if ((hasText(workspaceContext == null ? null : workspaceContext.getChatId())
                || hasText(workspaceContext == null ? null : workspaceContext.getThreadId()))
                && hasIntentSourceRequest(intentScope)) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                    .chatId(workspaceContext == null ? "" : workspaceContext.getChatId())
                    .threadId(workspaceContext == null ? "" : workspaceContext.getThreadId())
                    .timeRange(firstNonBlank(intentScope.getTimeRange(), intentSnapshot.getTimeRange(), workspaceContext == null ? null : workspaceContext.getTimeRange()))
                    .query(ContextNodeService.extractSearchQuery(rawInstruction))
                    .selectionInstruction(rawInstruction)
                    .pageSize(50)
                    .pageLimit(5)
                    .build());
        }
        if (intentScope.getDocRefs() != null && !intentScope.getDocRefs().isEmpty()) {
            sources.add(ContextSourceRequest.builder()
                    .sourceType(ContextSourceTypeEnum.LARK_DOC)
                    .docRefs(intentScope.getDocRefs())
                    .selectionInstruction(rawInstruction)
                    .build());
        }
        if (sources.isEmpty()) {
            return null;
        }
        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(sources)
                .reason("intent source scope requires context acquisition")
                .clarificationQuestion("")
                .build();
        return contextAcquisitionNodeService.collect(taskId, rawInstruction, workspaceContext, plan);
    }

    private boolean hasIntentSourceRequest(WorkspaceContext sourceScope) {
        if (sourceScope == null) {
            return false;
        }
        return hasText(sourceScope.getSelectionType())
                || hasText(sourceScope.getTimeRange())
                || (sourceScope.getSelectedMessageIds() != null && !sourceScope.getSelectedMessageIds().isEmpty());
    }

    private void attachCollectedSourceScope(PlanBlueprint blueprint, WorkspaceContext workspaceContext) {
        if (blueprint == null || workspaceContext == null || !hasWorkspaceContextMaterial(workspaceContext)) {
            return;
        }
        WorkspaceContext sourceScope = blueprint.getSourceScope();
        if (sourceScope == null || !hasWorkspaceContextMaterial(sourceScope)) {
            blueprint.setSourceScope(workspaceContext);
            return;
        }
        if ((sourceScope.getSelectedMessages() == null || sourceScope.getSelectedMessages().isEmpty())
                && workspaceContext.getSelectedMessages() != null
                && !workspaceContext.getSelectedMessages().isEmpty()) {
            sourceScope.setSelectedMessages(workspaceContext.getSelectedMessages());
        }
        if ((sourceScope.getSelectedMessageIds() == null || sourceScope.getSelectedMessageIds().isEmpty())
                && workspaceContext.getSelectedMessageIds() != null
                && !workspaceContext.getSelectedMessageIds().isEmpty()) {
            sourceScope.setSelectedMessageIds(workspaceContext.getSelectedMessageIds());
        }
        if ((!hasText(sourceScope.getInputSource()) || isCollectedImSource(workspaceContext.getInputSource()))
                && hasText(workspaceContext.getInputSource())) {
            sourceScope.setInputSource(workspaceContext.getInputSource());
        }
        if ((sourceScope.getSourceArtifacts() == null || sourceScope.getSourceArtifacts().isEmpty())
                && workspaceContext.getSourceArtifacts() != null
                && !workspaceContext.getSourceArtifacts().isEmpty()) {
            sourceScope.setSourceArtifacts(workspaceContext.getSourceArtifacts());
        }
    }

    private boolean isCollectedImSource(String inputSource) {
        return hasText(inputSource) && normalize(inputSource).startsWith("im-search");
    }

    private void normalizeCollectedContextWording(PlanBlueprint blueprint, WorkspaceContext workspaceContext) {
        if (blueprint == null || !isAutoCollectedContext(workspaceContext) || blueprint.getPlanCards() == null) {
            return;
        }
        for (UserPlanCard card : blueprint.getPlanCards()) {
            if (card == null) {
                continue;
            }
            card.setTitle(neutralizeSelectedMessageWording(card.getTitle()));
            card.setDescription(neutralizeSelectedMessageWording(card.getDescription()));
            if (card.getAgentTaskPlanCards() == null) {
                continue;
            }
            for (AgentTaskPlanCard taskCard : card.getAgentTaskPlanCards()) {
                if (taskCard == null) {
                    continue;
                }
                taskCard.setTitle(neutralizeSelectedMessageWording(taskCard.getTitle()));
                taskCard.setInput(neutralizeSelectedMessageWording(taskCard.getInput()));
                taskCard.setContext(neutralizeSelectedMessageWording(taskCard.getContext()));
            }
        }
    }

    private boolean isAutoCollectedContext(WorkspaceContext workspaceContext) {
        if (!hasWorkspaceContextMaterial(workspaceContext)) {
            return false;
        }
        String selectionType = normalize(workspaceContext.getSelectionType());
        return !("message".equals(selectionType)
                || "cherrypick".equals(selectionType)
                || "cherry_pick".equals(selectionType)
                || "selected_messages".equals(selectionType));
    }

    private String neutralizeSelectedMessageWording(String value) {
        if (!hasText(value)) {
            return value;
        }
        return value.replace("选中的", "已读取的")
                .replace("用户选中的", "已读取的")
                .replace("精选的", "已读取的");
    }

    private boolean hasWorkspaceContextMaterial(WorkspaceContext workspaceContext) {
        return workspaceContext != null
                && ((workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty())
                || (workspaceContext.getSelectedMessageIds() != null && !workspaceContext.getSelectedMessageIds().isEmpty())
                || (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty())
                || (workspaceContext.getSourceArtifacts() != null && !workspaceContext.getSourceArtifacts().isEmpty())
                || (workspaceContext.getAttachmentRefs() != null && !workspaceContext.getAttachmentRefs().isEmpty()));
    }

    private Optional<PlanBlueprint> repairPlanBlueprint(
            String taskId,
            String planningInput,
            IntentSnapshot intentSnapshot,
            PlanBlueprint emptyBlueprint
    ) {
        String repairPrompt = """
                The previous planning result had empty planCards, so it is not executable.
                Repair the plan using ONLY the user instruction, intent snapshot, conversation memory, and supported capability boundary below.

                Supported planCard.type values: DOC, PPT, SUMMARY.
                Supported agent taskType values:
                - DOC -> WRITE_DOC
                - PPT -> WRITE_SLIDES
                - SUMMARY -> GENERATE_SUMMARY
                Mermaid is a DOC content requirement, not a separate artifact.

                Return valid JSON only. The top-level object MUST contain a non-empty planCards array.
                Each card MUST include cardId, title, description, type, dependsOn, availableActions, and one agentTaskPlanCards item.
                Use stable sequential ids: card-001, card-002, task-001, task-002.

                User and context input:
                %s

                Intent snapshot:
                %s

                Previous invalid blueprint:
                %s
                """.formatted(planningInput, toJson(intentSnapshot), toJson(emptyBlueprint));
        Optional<PlanBlueprint> repaired = invokeAgent(
                planningAgent,
                repairPrompt,
                config(taskId, "planning", sessionService.getOrCreate(taskId), planningInput, planningInput, memoryService.renderContext(sessionService.getOrCreate(taskId))),
                PlanBlueprint.class
        );
        if (repaired.isPresent() && hasPlanCards(repaired.get())) {
            return repaired;
        }
        return Optional.of(emptyBlueprint);
    }

    Optional<PlanBlueprint> buildBoundedExecutablePlan(String taskId, String planningInput, IntentSnapshot intentSnapshot) {
        List<PlanCardTypeEnum> deliverables = resolveSupportedDeliverables(planningInput, intentSnapshot);
        if (deliverables.isEmpty()) {
            return Optional.empty();
        }
        List<UserPlanCard> cards = new ArrayList<>();
        for (PlanCardTypeEnum deliverable : deliverables) {
            cards.add(buildCard(taskId, deliverable, cards, planningInput, intentSnapshot));
        }
        LinkedHashSet<ScenarioCodeEnum> scenarioPath = new LinkedHashSet<>();
        scenarioPath.add(ScenarioCodeEnum.A_IM);
        scenarioPath.add(ScenarioCodeEnum.B_PLANNING);
        if (deliverables.contains(PlanCardTypeEnum.DOC) || deliverables.contains(PlanCardTypeEnum.SUMMARY)) {
            scenarioPath.add(ScenarioCodeEnum.C_DOC);
        }
        if (deliverables.contains(PlanCardTypeEnum.PPT)) {
            scenarioPath.add(ScenarioCodeEnum.D_PRESENTATION);
        }
        return Optional.of(PlanBlueprint.builder()
                .taskBrief(firstNonBlank(intentSnapshot == null ? null : intentSnapshot.getUserGoal(), extractInstruction(planningInput)))
                .scenarioPath(new ArrayList<>(scenarioPath))
                .deliverables(deliverables.stream().map(Enum::name).toList())
                .sourceScope(intentSnapshot == null ? null : intentSnapshot.getSourceScope())
                .constraints(intentSnapshot == null ? List.of() : safeList(intentSnapshot.getConstraints()))
                .successCriteria(defaultSuccessCriteria(deliverables, planningInput))
                .risks(defaultRisks(planningInput))
                .planCards(cards)
                .build());
    }

    private UserPlanCard buildCard(
            String taskId,
            PlanCardTypeEnum type,
            List<UserPlanCard> existingCards,
            String planningInput,
            IntentSnapshot intentSnapshot
    ) {
        String cardId = "card-" + String.format("%03d", existingCards.size() + 1);
        String title = switch (type) {
            case DOC -> "生成结构化文档";
            case PPT -> "生成汇报 PPT 初稿";
            case SUMMARY -> "生成任务摘要";
        };
        String description = switch (type) {
            case DOC -> "基于用户提供的上下文，撰写结构化文档";
            case PPT -> hasCardOfType(existingCards, PlanCardTypeEnum.DOC)
                    ? "基于技术方案文档，整理一份用于汇报的 PPT 初稿"
                    : "基于用户目标和已有上下文，整理一份用于汇报的 PPT 初稿";
            case SUMMARY -> "基于当前任务目标和已生成内容，输出一段简洁摘要";
        };
        AgentTaskTypeEnum agentTaskType = switch (type) {
            case DOC -> AgentTaskTypeEnum.WRITE_DOC;
            case PPT -> AgentTaskTypeEnum.WRITE_SLIDES;
            case SUMMARY -> AgentTaskTypeEnum.GENERATE_SUMMARY;
        };
        List<String> dependsOn = dependenciesFor(type, existingCards);
        return UserPlanCard.builder()
                .cardId(cardId)
                .taskId(taskId)
                .title(title)
                .description(description)
                .type(type)
                .status("PENDING")
                .progress(0)
                .dependsOn(dependsOn)
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .agentTaskPlanCards(List.of(AgentTaskPlanCard.builder()
                        .taskId("task-" + String.format("%03d", existingCards.size() + 1))
                        .parentCardId(cardId)
                        .taskType(agentTaskType)
                        .status("PENDING")
                        .input(firstNonBlank(intentSnapshot == null ? null : intentSnapshot.getUserGoal(), extractInstruction(planningInput)))
                        .tools(defaultTools(type))
                        .context(description)
                        .build()))
                .build();
    }

    private List<PlanCardTypeEnum> resolveSupportedDeliverables(String planningInput, IntentSnapshot intentSnapshot) {
        LinkedHashSet<PlanCardTypeEnum> resolved = new LinkedHashSet<>();
        if (intentSnapshot != null && intentSnapshot.getDeliverableTargets() != null) {
            for (String target : intentSnapshot.getDeliverableTargets()) {
                toPlanCardType(target).ifPresent(resolved::add);
            }
        }
        return new ArrayList<>(resolved);
    }

    private boolean hasMissingSlots(IntentSnapshot intentSnapshot) {
        return intentSnapshot != null
                && intentSnapshot.getMissingSlots() != null
                && intentSnapshot.getMissingSlots().stream().anyMatch(this::hasText);
    }

    private boolean shouldPauseForMissingSlots(PlanTaskSession session, IntentSnapshot intentSnapshot, String planningInput) {
        if (!hasMissingSlots(intentSnapshot)) {
            return false;
        }
        if (hasSupportedDeliverable(intentSnapshot)
                && (hasSubstantialClarificationAnswer(session)
                || hasEmbeddedTaskMaterial(planningInput)
                || hasWorkspaceMaterial(planningInput))) {
            return false;
        }
        return true;
    }

    private boolean feedbackCanSatisfyIntentSource(
            PlanTaskSession session,
            IntentSnapshot intentSnapshot,
            String userFeedback
    ) {
        if (!requiresExternalSourceCollection(intentSnapshot)) {
            return false;
        }
        if (!hasSupportedDeliverable(intentSnapshot)) {
            return false;
        }
        Optional<ClarificationSourceMaterialAssessment> assessment = assessClarificationSourceMaterial(
                session,
                intentSnapshot,
                userFeedback
        );
        if (assessment.isPresent()) {
            return assessment.get().isCanReplaceExternalSourceCollection();
        }
        return hasInlineSourceMaterial(userFeedback) || latestClarificationAnswer(session)
                .map(this::hasInlineSourceMaterial)
                .orElse(false);
    }

    private Optional<ClarificationSourceMaterialAssessment> assessClarificationSourceMaterial(
            PlanTaskSession session,
            IntentSnapshot intentSnapshot,
            String userFeedback
    ) {
        if (clarificationSourceMaterialJudgeAgent == null || intentSnapshot == null) {
            return Optional.empty();
        }
        String prompt = """
                Task source scope:
                %s

                Original instruction:
                %s

                Latest user feedback:
                %s

                Existing clarification answers:
                %s
                """.formatted(
                toJson(intentSnapshot.getSourceScope()),
                firstNonBlank(session == null ? null : session.getRawInstruction(), intentSnapshot.getUserGoal()),
                firstNonBlank(userFeedback, ""),
                session == null || session.getClarificationAnswers() == null ? "[]" : toJson(session.getClarificationAnswers())
        );
        return invokeAgent(
                clarificationSourceMaterialJudgeAgent,
                prompt,
                config(
                        session == null ? "unknown-task" : session.getTaskId(),
                        "clarification-source-material-judge",
                        session == null ? PlanTaskSession.builder().taskId("unknown-task").build() : session,
                        firstNonBlank(session == null ? null : session.getRawInstruction(), intentSnapshot.getUserGoal()),
                        prompt,
                        ""
                ),
                ClarificationSourceMaterialAssessment.class
        );
    }

    private boolean requiresExternalSourceCollection(IntentSnapshot intentSnapshot) {
        if (intentSnapshot == null || intentSnapshot.getSourceScope() == null) {
            return false;
        }
        WorkspaceContext sourceScope = intentSnapshot.getSourceScope();
        return hasIntentSourceRequest(sourceScope)
                || (sourceScope.getDocRefs() != null && !sourceScope.getDocRefs().isEmpty());
    }

    private boolean hasInlineSourceMaterial(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim();
        int delimiter = Math.max(normalized.lastIndexOf('：'), normalized.lastIndexOf(':'));
        if (delimiter >= 0 && delimiter < normalized.length() - 1) {
            return hasSubstantialInlineMaterial(normalized.substring(delimiter + 1));
        }
        return hasSubstantialInlineMaterial(normalized);
    }

    private Optional<String> latestClarificationAnswer(PlanTaskSession session) {
        if (session == null || session.getClarificationAnswers() == null) {
            return Optional.empty();
        }
        List<String> answers = session.getClarificationAnswers().stream()
                .filter(this::hasText)
                .map(String::trim)
                .toList();
        if (answers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(answers.get(answers.size() - 1));
    }

    private boolean hasSupportedDeliverable(IntentSnapshot intentSnapshot) {
        return intentSnapshot != null
                && intentSnapshot.getDeliverableTargets() != null
                && intentSnapshot.getDeliverableTargets().stream()
                .anyMatch(target -> toPlanCardType(target).isPresent());
    }

    private boolean hasSubstantialClarificationAnswer(PlanTaskSession session) {
        if (session == null || session.getClarificationAnswers() == null) {
            return false;
        }
        return session.getClarificationAnswers().stream()
                .filter(this::hasText)
                .map(String::trim)
                .mapToInt(String::length)
                .sum() >= 12;
    }

    private String buildMissingSlotQuestion(IntentSnapshot intentSnapshot) {
        List<String> missingSlots = intentSnapshot.getMissingSlots().stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .limit(3)
                .toList();
        boolean hasDeliverable = hasSupportedDeliverable(intentSnapshot);
        boolean hasUnsupportedDeliverable = hasUnsupportedDeliverable(intentSnapshot);
        if (hasUnsupportedDeliverable && !hasDeliverable) {
            return "这个产物我现在还不能直接稳定生成。可以先转成文档里的 Mermaid 图、PPT 页面或一段摘要，你想用哪种？";
        }
        String suffix = hasDeliverable
                ? "。你可以直接贴材料、文档链接，或告诉我取哪段消息。"
                : "。你可以直接贴材料、文档链接，或告诉我想做成文档、PPT 还是摘要。";
        if (missingSlots.isEmpty()) {
            return hasDeliverable
                    ? "可以，我还差要整理的内容。你可以直接贴材料、文档链接，或告诉我取哪段消息。"
                    : "可以，我还差一点上下文。你可以直接贴材料、文档链接，或告诉我想做成文档、PPT 还是摘要。";
        }
        String joinedSlots = String.join("、", missingSlots);
        if (hasDeliverable) {
            return "可以，产物形式我记下了。我还差要整理的内容：你可以直接贴材料、文档链接，或告诉我取哪段消息。";
        }
        return "我还差一点信息：" + joinedSlots + suffix;
    }

    private boolean hasUnsupportedDeliverable(IntentSnapshot intentSnapshot) {
        if (intentSnapshot == null || intentSnapshot.getDeliverableTargets() == null) {
            return false;
        }
        return intentSnapshot.getDeliverableTargets().stream()
                .filter(this::hasText)
                .anyMatch(target -> toPlanCardType(target).isEmpty());
    }

    private Optional<PlanCardTypeEnum> toPlanCardType(String raw) {
        if (!hasText(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(PlanCardTypeEnum.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private List<String> dependenciesFor(PlanCardTypeEnum type, List<UserPlanCard> existingCards) {
        if (existingCards == null || existingCards.isEmpty()) {
            return List.of();
        }
        if (type == PlanCardTypeEnum.PPT) {
            return lastCardIdOfType(existingCards, PlanCardTypeEnum.DOC).map(List::of).orElse(List.of());
        }
        if (type == PlanCardTypeEnum.SUMMARY) {
            return lastNonSummaryCardId(existingCards).map(List::of).orElse(List.of());
        }
        return List.of();
    }

    private Optional<String> lastCardIdOfType(List<UserPlanCard> cards, PlanCardTypeEnum type) {
        for (int index = cards.size() - 1; index >= 0; index--) {
            UserPlanCard card = cards.get(index);
            if (card != null && card.getType() == type && hasText(card.getCardId())) {
                return Optional.of(card.getCardId());
            }
        }
        return Optional.empty();
    }

    private Optional<String> lastNonSummaryCardId(List<UserPlanCard> cards) {
        for (int index = cards.size() - 1; index >= 0; index--) {
            UserPlanCard card = cards.get(index);
            if (card != null && card.getType() != PlanCardTypeEnum.SUMMARY && hasText(card.getCardId())) {
                return Optional.of(card.getCardId());
            }
        }
        return Optional.empty();
    }

    private boolean hasCardOfType(List<UserPlanCard> cards, PlanCardTypeEnum type) {
        return cards != null && cards.stream().anyMatch(card -> card != null && card.getType() == type);
    }

    private boolean hasEmbeddedTaskMaterial(String planningInput) {
        if (!hasText(planningInput)) {
            return false;
        }
        String instruction = extractInstruction(planningInput);
        if (!hasText(instruction)) {
            return false;
        }
        int delimiter = Math.max(instruction.lastIndexOf('：'), instruction.lastIndexOf(':'));
        if (delimiter >= 0) {
            return hasSubstantialInlineMaterial(instruction.substring(delimiter + 1));
        }
        return instruction.contains("\n") && instruction.length() >= 40;
    }

    private boolean hasSubstantialInlineMaterial(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.contains("\n") && normalized.length() >= 40) {
            return true;
        }
        return normalized.length() >= 48;
    }

    private boolean hasWorkspaceMaterial(String planningInput) {
        if (!hasText(planningInput)) {
            return false;
        }
        String normalized = planningInput.toLowerCase(Locale.ROOT);
        return normalized.contains("selected messages:")
                || normalized.contains("source artifacts:")
                || normalized.contains("doc refs:")
                || normalized.contains("attachment refs:");
    }

    private List<String> defaultTools(PlanCardTypeEnum type) {
        return switch (type) {
            case DOC -> List.of("lark-doc");
            case PPT -> List.of("lark-slides");
            case SUMMARY -> List.of("summary-writer");
        };
    }

    private List<String> defaultSuccessCriteria(List<PlanCardTypeEnum> deliverables, String planningInput) {
        List<String> criteria = new ArrayList<>();
        if (deliverables.contains(PlanCardTypeEnum.DOC)) {
            criteria.add("文档结构清晰、内容可交付");
        }
        if (deliverables.contains(PlanCardTypeEnum.PPT)) {
            criteria.add("PPT 初稿与文档或任务目标保持一致");
        }
        if (deliverables.contains(PlanCardTypeEnum.SUMMARY)) {
            criteria.add("摘要简洁，可直接复用");
        }
        return criteria;
    }

    private List<String> defaultRisks(String planningInput) {
        return List.of();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String extractInstruction(String planningInput) {
        if (!hasText(planningInput)) {
            return "";
        }
        String marker = "User instruction:";
        int start = planningInput.indexOf(marker);
        if (start < 0) {
            return planningInput.trim();
        }
        String instructionBlock = planningInput.substring(start + marker.length());
        List<String> nextMarkers = List.of("\nUser feedback:", "\nWorkspace context:", "\nConversation memory:");
        int end = instructionBlock.length();
        for (String nextMarker : nextMarkers) {
            int index = instructionBlock.indexOf(nextMarker);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return instructionBlock.substring(0, end).trim();
    }

    private RunnableConfig config(
            String taskId,
            String agentName,
            PlanTaskSession session,
            String rawInstruction,
            String context,
            String conversationMemory
    ) {
        RunnableConfig base = RunnableConfig.builder()
                .threadId(taskId + ":planner:" + agentName)
                .build();
        return AgentPromptContext.withPlanningPromptContext(
                base,
                session,
                rawInstruction,
                context,
                conversationMemory,
                agentName.endsWith("-agent") ? agentName : agentName + "-agent"
        );
    }

    private <T> Optional<T> invokeAgent(ReactAgent agent, String prompt, RunnableConfig config, Class<T> type) {
        try {
            Optional<OverAllState> state = agent.invoke(prompt, config);
            if (state.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> data = state.get().data();
            Optional<T> fromMessage = extractStructured(data.get("message"), type);
            if (fromMessage.isPresent()) {
                return fromMessage;
            }
            return extractStructured(data.get("messages"), type);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    <T> Optional<T> extractStructured(Object value, Class<T> type) {
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

    boolean hasPlanCards(PlanBlueprint blueprint) {
        return blueprint != null
                && blueprint.getPlanCards() != null
                && blueprint.getPlanCards().stream().anyMatch(card -> card != null && card.getType() != null);
    }

    private <T> Optional<T> parseText(String text, Class<T> type) {
        if (!hasText(text)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(extractJson(text), type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String buildPlanningInput(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String userFeedback,
            String conversationMemory
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction);
        if (hasText(userFeedback)) {
            builder.append("\nUser feedback: ").append(userFeedback);
        }
        String workspacePromptContext = renderWorkspaceContext(workspaceContext, rawInstruction);
        if (hasText(workspacePromptContext)) {
            builder.append("\nWorkspace context:\n").append(workspacePromptContext);
        }
        if (hasText(conversationMemory)) {
            builder.append("\nConversation memory:\n").append(conversationMemory);
        }
        return builder.toString();
    }

    private String renderWorkspaceContext(WorkspaceContext workspaceContext, String rawInstruction) {
        if (workspaceContext == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        List<String> selectedMessages = realSelectedMessages(workspaceContext, rawInstruction);
        if (!selectedMessages.isEmpty()) {
            builder.append("Selected messages:\n")
                    .append(String.join("\n", selectedMessages))
                    .append("\n");
        }
        if (workspaceContext.getSelectedMessageIds() != null
                && !workspaceContext.getSelectedMessageIds().isEmpty()
                && !selectedMessages.isEmpty()) {
            builder.append("Selected message ids: ")
                    .append(String.join(", ", workspaceContext.getSelectedMessageIds()))
                    .append("\n");
        }
        if (workspaceContext.getSourceArtifacts() != null && !workspaceContext.getSourceArtifacts().isEmpty()) {
            builder.append("Source artifacts:\n");
            for (SourceArtifactRef artifact : workspaceContext.getSourceArtifacts()) {
                if (artifact == null) {
                    continue;
                }
                builder.append("- [")
                        .append(artifact.getArtifactType() == null ? "" : artifact.getArtifactType().name())
                        .append("] ")
                        .append(firstNonBlank(artifact.getTitle(), artifact.getUrl(), artifact.getArtifactId()))
                        .append("\n");
                if (hasText(artifact.getPreview())) {
                    builder.append("  preview: ").append(artifact.getPreview().trim()).append("\n");
                }
            }
        }
        return builder.toString().trim();
    }

    private List<String> realSelectedMessages(WorkspaceContext workspaceContext, String rawInstruction) {
        if (workspaceContext == null
                || workspaceContext.getSelectedMessages() == null
                || workspaceContext.getSelectedMessages().isEmpty()) {
            return List.of();
        }
        List<String> messages = workspaceContext.getSelectedMessages().stream()
                .filter(this::hasText)
                .toList();
        if (messages.size() == 1 && isSameAsInstruction(messages.get(0), rawInstruction)) {
            return List.of();
        }
        return messages;
    }

    private boolean isSameAsInstruction(String message, String rawInstruction) {
        String normalizedMessage = normalize(message);
        String normalizedInstruction = normalize(rawInstruction);
        return hasText(normalizedMessage)
                && hasText(normalizedInstruction)
                && (normalizedMessage.equals(normalizedInstruction)
                || normalizedMessage.contains(normalizedInstruction)
                || normalizedInstruction.contains(normalizedMessage));
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String compact(String value) {
        return normalize(value).replaceAll("\\s+", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
