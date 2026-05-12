package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.intent.IntentDecisionGuard;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.IntentRouterService;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
import com.lark.imcollab.planner.intent.HardRuleIntentClassifier;
import com.lark.imcollab.planner.service.CompletedArtifactIntentRecoveryService;
import com.lark.imcollab.planner.service.ConversationTaskStateService;
import com.lark.imcollab.planner.service.CurrentTaskContinuationArbiter;
import com.lark.imcollab.planner.service.PendingFollowUpConflictArbiter;
import com.lark.imcollab.planner.service.PendingFollowUpRecommendationMatcher;
import com.lark.imcollab.planner.service.PlannerConversationService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.RoutingEvidence;
import com.lark.imcollab.planner.service.RoutingEvidenceExtractor;
import com.lark.imcollab.planner.service.TaskSessionResolution;
import com.lark.imcollab.planner.service.TaskSessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DefaultPlannerPlanFacade implements PlannerPlanFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlannerPlanFacade.class);
    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");
    private static final String SUPPRESS_IMMEDIATE_REPLY = "__SUPPRESS_IMMEDIATE_REPLY__";
    private final RoutingEvidenceExtractor routingEvidenceExtractor;

    private final PlannerConversationService plannerConversationService;
    private final TaskSessionResolver taskSessionResolver;
    private final PlannerSessionService plannerSessionService;
    private final IntentRouterService intentRouterService;
    private final ConversationTaskStateService conversationTaskStateService;
    private final PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher;
    private final CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService;
    private final PendingFollowUpConflictArbiter pendingFollowUpConflictArbiter;

    @Autowired
    public DefaultPlannerPlanFacade(
            PlannerConversationService plannerConversationService,
            TaskSessionResolver taskSessionResolver,
            PlannerSessionService plannerSessionService,
            IntentRouterService intentRouterService,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService,
            PendingFollowUpConflictArbiter pendingFollowUpConflictArbiter,
            PlannerProperties plannerProperties
    ) {
        this.plannerConversationService = plannerConversationService;
        this.taskSessionResolver = taskSessionResolver;
        this.plannerSessionService = plannerSessionService;
        this.intentRouterService = intentRouterService;
        this.conversationTaskStateService = conversationTaskStateService;
        this.pendingFollowUpRecommendationMatcher = pendingFollowUpRecommendationMatcher;
        this.completedArtifactIntentRecoveryService = completedArtifactIntentRecoveryService;
        this.pendingFollowUpConflictArbiter = pendingFollowUpConflictArbiter;
        this.routingEvidenceExtractor = new RoutingEvidenceExtractor(plannerProperties);
    }

    public DefaultPlannerPlanFacade(
            PlannerConversationService plannerConversationService,
            TaskSessionResolver taskSessionResolver,
            PlannerSessionService plannerSessionService,
            LlmIntentClassifier llmIntentClassifier,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher
    ) {
        this(plannerConversationService, taskSessionResolver, plannerSessionService, buildPreviewIntentRouter(llmIntentClassifier, new PlannerProperties()),
                conversationTaskStateService, pendingFollowUpRecommendationMatcher,
                new CompletedArtifactIntentRecoveryService(taskSessionResolver),
                pendingFollowUpRecommendationMatcher == null ? null : new PendingFollowUpConflictArbiter(pendingFollowUpRecommendationMatcher, new PlannerProperties()),
                new PlannerProperties());
    }

    public DefaultPlannerPlanFacade(
            PlannerConversationService plannerConversationService,
            TaskSessionResolver taskSessionResolver,
            PlannerSessionService plannerSessionService,
            LlmIntentClassifier llmIntentClassifier,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService
    ) {
        this(plannerConversationService, taskSessionResolver, plannerSessionService, buildPreviewIntentRouter(llmIntentClassifier, new PlannerProperties()),
                conversationTaskStateService, pendingFollowUpRecommendationMatcher,
                completedArtifactIntentRecoveryService,
                pendingFollowUpRecommendationMatcher == null ? null : new PendingFollowUpConflictArbiter(pendingFollowUpRecommendationMatcher, new PlannerProperties()),
                new PlannerProperties());
    }

    @Override
    public String previewImmediateReply(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        String effectiveInput = stripLeadingMentionPlaceholders(firstText(userFeedback, rawInstruction));
        if (effectiveInput.isBlank()) {
            return "";
        }
        TaskSessionResolution resolution = taskSessionResolver.resolve(taskId, workspaceContext);
        PlanTaskSession session = resolution.existingSession() ? safeGet(resolution.taskId()) : null;
        if (hasPendingSelection(session)) {
            return "";
        }
        IntentRoutingResult routingResult = intentRouterService == null
                ? null
                : intentRouterService.classify(session, effectiveInput, resolution.existingSession());
        if (routingResult == null) {
            return "";
        }
        if (routingResult.type() == TaskCommandTypeEnum.QUERY_STATUS
                && "COMPLETED_TASKS".equalsIgnoreCase(routingResult.readOnlyView())) {
            return "";
        }
        CompletedArtifactIntentRecoveryService.RecoveryResult recoveryResult =
                completedArtifactIntentRecoveryService == null
                        ? CompletedArtifactIntentRecoveryService.RecoveryResult.none()
                        : completedArtifactIntentRecoveryService.recoverIntentRouting(
                                session,
                                resolution,
                                workspaceContext,
                                routingResult,
                                effectiveInput
                        );
        if (recoveryResult.recoveredIntent() != null) {
            routingResult = recoveryResult.recoveredIntent();
        }
        CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation =
                completedArtifactIntentRecoveryService == null
                        ? CompletedArtifactIntentRecoveryService.DirectRouteEvaluation.none("recovery service unavailable")
                        : completedArtifactIntentRecoveryService.evaluateCurrentCompletedArtifactRoute(
                                session,
                                resolution,
                                workspaceContext,
                                effectiveInput
                        );
        if (directRouteEvaluation.type() != CompletedArtifactIntentRecoveryService.DirectRouteType.NONE) {
            return immediateReceipt(TaskCommandTypeEnum.ADJUST_PLAN, AdjustmentTargetEnum.COMPLETED_ARTIFACT, session, workspaceContext);
        }
        String followUpPreview = previewPendingFollowUpImmediateReply(session, resolution, effectiveInput, routingResult);
        if (SUPPRESS_IMMEDIATE_REPLY.equals(followUpPreview)) {
            return "";
        }
        if (!followUpPreview.isBlank()) {
            return followUpPreview;
        }
        return immediateReceipt(routingResult.type(), routingResult.adjustmentTarget(), session, workspaceContext);
    }

    private static IntentRouterService buildPreviewIntentRouter(
            LlmIntentClassifier llmIntentClassifier,
            PlannerProperties plannerProperties
    ) {
        PlannerProperties properties = plannerProperties == null ? new PlannerProperties() : plannerProperties;
        return new IntentRouterService(
                new HardRuleIntentClassifier(),
                llmIntentClassifier,
                new IntentDecisionGuard(properties),
                properties
        );
    }

    @Override
    public PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        return plannerConversationService.handlePlanRequest(rawInstruction, workspaceContext, taskId, userFeedback);
    }

    @Override
    public PlanTaskSession getLatestSession(String taskId) {
        return safeGet(taskId);
    }

    private String immediateReceipt(TaskCommandTypeEnum type, AdjustmentTargetEnum adjustmentTarget, PlanTaskSession session, WorkspaceContext workspaceContext) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case START_TASK -> "🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。";
            case ADJUST_PLAN -> {
                if (session != null && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
                    // 明确要中断重规划，不受澄清标记影响
                    if (adjustmentTarget == AdjustmentTargetEnum.RUNNING_PLAN) {
                        yield "🔄 这条调整我收到了，我会先中断当前执行，再根据你的新要求重新规划，请稍候。";
                    }
                    // 用户在回应我们的澄清问题
                    if (session.getIntakeState() != null
                            && session.getIntakeState().getPendingInteractionType()
                                    == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT) {
                        yield "🧩 你的补充我接上了，我会带着这条信息继续往下处理。";
                    }
                    // 有可编辑产物时会先澄清，不会直接中断
                    if (taskSessionResolver.hasEditableArtifacts(session.getTaskId())
                            || taskSessionResolver.conversationHasEditableArtifacts(workspaceContext)) {
                        yield "🔄 这条调整我收到了，稍等我先确认一下你的意图。";
                    }
                    yield "🔄 这条调整我收到了，我会先中断当前执行，再根据你的新要求重新规划，请稍候。";
                }
                yield "🔄 这条调整我收到了，我先顺着当前任务梳理一下，再把更新结果回给你。";
            }
            case ANSWER_CLARIFICATION -> "🧩 你的补充我接上了，我会带着这条信息继续往下处理。";
            default -> "";
        };
    }

    private String previewPendingFollowUpImmediateReply(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String effectiveInput,
            IntentRoutingResult routingResult
    ) {
        if (conversationTaskStateService == null
                || pendingFollowUpRecommendationMatcher == null
                || pendingFollowUpConflictArbiter == null
                || resolution == null
                || resolution.continuationKey() == null
                || effectiveInput == null
                || effectiveInput.isBlank()) {
            return "";
        }
        if (hasPendingSelection(session)) {
            return "";
        }
        Optional<com.lark.imcollab.common.model.entity.ConversationTaskState> stateOptional =
                conversationTaskStateService.find(resolution.continuationKey());
        boolean awaitingSelection = stateOptional.map(com.lark.imcollab.common.model.entity.ConversationTaskState::isPendingFollowUpAwaitingSelection)
                .orElse(false);
        List<PendingFollowUpRecommendation> recommendations = stateOptional
                .map(state -> state.getPendingFollowUpRecommendations())
                .orElse(List.of());
        if (recommendations == null || recommendations.isEmpty()) {
            return "";
        }
        boolean explicitCurrentTaskContext = isExplicitCurrentTaskContinuationContext(
                session,
                resolution,
                stateOptional.orElse(null)
        );
        CurrentTaskContinuationArbiter.Decision decision = pendingFollowUpConflictArbiter.arbitratePreview(
                routingResult == null ? null : routingResult.type(),
                effectiveInput,
                recommendations,
                awaitingSelection,
                explicitCurrentTaskContext,
                CompletedArtifactIntentRecoveryService.DirectRouteEvaluation.none("preview phase does not route current artifact edit here")
        );
        RoutingEvidence evidence = routingEvidenceExtractor.extract(effectiveInput);
        PendingFollowUpRecommendationMatcher.CarryForwardHint carryForwardHint = decision.hint();
        log.info(
                "current_task_arbiter_decision taskId={} userInput='{}' upstreamType={} decision={} currentReference={} explicitCurrentTaskContext={} carryForwardHint={} recommendationCount={} selectedRecommendationId={} topRecommendationId={} topRecommendationScore={} secondRecommendationId={} secondRecommendationScore={} freshTaskScore={} currentTaskReferenceScore={} continuationIntentScore={} artifactEditScore={} newDeliverableScore={} ambiguousMaterialOrganizationScore={} reason={}",
                session == null ? null : session.getTaskId(),
                effectiveInput,
                routingResult == null ? null : routingResult.type(),
                decision.type(),
                decision.currentReference(),
                explicitCurrentTaskContext,
                carryForwardHint,
                recommendations.size(),
                decision.selectedRecommendation() == null ? null : decision.selectedRecommendation().getRecommendationId(),
                decision.topRecommendationId(),
                decision.topRecommendationScore(),
                decision.secondRecommendationId(),
                decision.secondRecommendationScore(),
                evidence.freshTaskScore(),
                evidence.currentTaskReferenceScore(),
                evidence.continuationIntentScore(),
                evidence.artifactEditScore(),
                evidence.newDeliverableScore(),
                evidence.ambiguousMaterialOrganizationScore(),
                decision.reason()
        );
        if (decision.type() == CurrentTaskContinuationArbiter.DecisionType.NO_DECISION
                || decision.type() == CurrentTaskContinuationArbiter.DecisionType.PROCEED_NEW_TASK
                || decision.type() == CurrentTaskContinuationArbiter.DecisionType.BYPASS_TO_COMPLETED_ARTIFACT_EDIT) {
            return "";
        }
        log.info(
                "pending_followup_preview_hint taskId={} userInput='{}' routingType={} carryForwardHint={} recommendationCount={} upstreamSuggestsStandaloneTask={} selectedRecommendationId={}",
                session == null ? null : session.getTaskId(),
                effectiveInput,
                routingResult == null ? null : routingResult.type(),
                carryForwardHint,
                recommendations.size(),
                routingResult != null && routingResult.type() == TaskCommandTypeEnum.START_TASK,
                decision.selectedRecommendation() == null ? null : decision.selectedRecommendation().getRecommendationId()
        );
        if (decision.type() == CurrentTaskContinuationArbiter.DecisionType.PROCEED_CURRENT_TASK) {
            return "🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。";
        }
        if (decision.type() == CurrentTaskContinuationArbiter.DecisionType.ASK_CURRENT_TASK_SELECTION) {
            return SUPPRESS_IMMEDIATE_REPLY;
        }
        if (decision.type() == CurrentTaskContinuationArbiter.DecisionType.ASK_NEW_OR_CURRENT) {
            return SUPPRESS_IMMEDIATE_REPLY;
        }
        return "";
    }

    private boolean isExplicitCurrentTaskContinuationContext(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            com.lark.imcollab.common.model.entity.ConversationTaskState state
    ) {
        if (session == null
                || session.getPlanningPhase() != PlanningPhaseEnum.COMPLETED
                || resolution == null
                || !resolution.existingSession()
                || state == null
                || state.getPendingFollowUpRecommendations() == null
                || state.getPendingFollowUpRecommendations().isEmpty()) {
            return false;
        }
        String taskId = session.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        boolean boundToCurrentTask = taskId.equals(resolution.taskId())
                || taskId.equals(state.getActiveTaskId())
                || taskId.equals(state.getLastCompletedTaskId());
        if (!boundToCurrentTask) {
            return false;
        }
        return state.getPendingFollowUpRecommendations().stream()
                .anyMatch(recommendation -> recommendation != null && taskId.equals(recommendation.getTargetTaskId()));
    }

    private boolean hasPendingSelection(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && (session.getIntakeState().getPendingTaskSelection() != null
                || session.getIntakeState().getPendingArtifactSelection() != null
                || session.getIntakeState().getPendingFollowUpConflictChoice() != null
                || session.getIntakeState().getPendingCurrentTaskContinuationChoice() != null);
    }

    private String buildFollowUpConflictPrompt(String newTaskInstruction, PendingFollowUpRecommendation recommendation) {
        if (recommendation == null) {
            return "❓ 我理解这句话有两种可能：1. 新开一个任务，单独处理“"
                    + newTaskInstruction.trim()
                    + "”；2. 接着当前任务继续处理，但我还需要你再选具体后续动作，或直接说明要修改哪个已有产物。回复 1 新开任务，回复 2 继续当前任务。";
        }
        String recommendationText = firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction());
        return "❓ 我理解这句话有两种可能：1. 新开一个任务，单独处理“"
                + newTaskInstruction.trim()
                + "”；2. 接着上一轮任务继续，执行“"
                + recommendationText
                + "”。回复 1 新开任务，回复 2 接着上一个任务。";
    }

    private PlanTaskSession safeGet(String taskId) {
        try {
            return plannerSessionService.get(taskId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String stripLeadingMentionPlaceholders(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.trim();
        String previous;
        do {
            previous = normalized;
            normalized = FEISHU_AT_TAG.matcher(normalized).replaceFirst("").trim();
            normalized = FEISHU_MENTION_TOKEN.matcher(normalized).replaceFirst("").trim();
            normalized = normalized.replaceFirst("^@[\\p{L}0-9_\\-]+\\s+", "").trim();
        } while (!normalized.equals(previous));
        return normalized;
    }
}
