package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PendingCurrentTaskContinuationChoice;
import com.lark.imcollab.common.model.entity.PendingFollowUpConflictChoice;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PendingTaskSelection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.SourceArtifactRef;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ReplanScopeEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.planner.supervisor.PlannerExecutionTool;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecisionResult;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.planner.supervisor.PlannerToolResult;
import com.lark.imcollab.planner.supervisor.ReadOnlyNodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlannerConversationService {

    private static final Logger log = LoggerFactory.getLogger(PlannerConversationService.class);

    private final TaskSessionResolver sessionResolver;
    private final TaskIntakeService intakeService;
    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerSupervisorGraphRunner graphRunner;
    private final PlannerExecutionTool executionTool;
    private final TaskRuntimeService taskRuntimeService;
    private final ReplanScopeService replanScopeService;
    private final CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService;
    private final FollowUpArtifactContextResolver followUpArtifactContextResolver;
    private final ConversationTaskStateService conversationTaskStateService;
    private final PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher;
    private final PendingFollowUpConflictArbiter pendingFollowUpConflictArbiter;
    private final FollowUpRecommendationExecutionService followUpRecommendationExecutionService;
    private final ReadOnlyNodeService readOnlyNodeService;
    private final RoutingEvidenceExtractor routingEvidenceExtractor;
    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");
    private static final Pattern SINGLE_DIGIT_SELECTION = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");
    private static final DateTimeFormatter COMPLETED_TASK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String SELECTION_PURPOSE_COMPLETED_TASK_LIST = "COMPLETED_TASK_LIST";
    private static final String SELECTION_PURPOSE_COMPLETED_TASK_ADJUSTMENT = "COMPLETED_TASK_ADJUSTMENT";

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner, null, null, null, new CompletedArtifactIntentRecoveryService(sessionResolver), null, null, null, null, null, null, new PlannerProperties());
    }

    @Autowired
    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService,
            ReplanScopeService replanScopeService,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService,
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher,
            FollowUpRecommendationExecutionService followUpRecommendationExecutionService,
            ReadOnlyNodeService readOnlyNodeService,
            PendingFollowUpConflictArbiter pendingFollowUpConflictArbiter,
            PlannerProperties plannerProperties
    ) {
        this.sessionResolver = sessionResolver;
        this.intakeService = intakeService;
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.memoryService = memoryService;
        this.graphRunner = graphRunner;
        this.executionTool = executionTool;
        this.taskRuntimeService = taskRuntimeService;
        this.replanScopeService = replanScopeService;
        this.completedArtifactIntentRecoveryService = completedArtifactIntentRecoveryService;
        this.followUpArtifactContextResolver = followUpArtifactContextResolver;
        this.conversationTaskStateService = conversationTaskStateService;
        this.pendingFollowUpRecommendationMatcher = pendingFollowUpRecommendationMatcher;
        this.followUpRecommendationExecutionService = followUpRecommendationExecutionService;
        this.readOnlyNodeService = readOnlyNodeService;
        this.pendingFollowUpConflictArbiter = pendingFollowUpConflictArbiter;
        this.routingEvidenceExtractor = new RoutingEvidenceExtractor(plannerProperties);
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService,
            ReplanScopeService replanScopeService,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService,
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher,
            FollowUpRecommendationExecutionService followUpRecommendationExecutionService,
            ReadOnlyNodeService readOnlyNodeService,
            PendingFollowUpConflictArbiter pendingFollowUpConflictArbiter
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                executionTool, taskRuntimeService, replanScopeService, completedArtifactIntentRecoveryService,
                followUpArtifactContextResolver, conversationTaskStateService, pendingFollowUpRecommendationMatcher,
                followUpRecommendationExecutionService, readOnlyNodeService, pendingFollowUpConflictArbiter,
                new PlannerProperties());
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                executionTool, taskRuntimeService, new ReplanScopeService(taskRuntimeService), new CompletedArtifactIntentRecoveryService(sessionResolver), null, null, null, null, null, null, new PlannerProperties());
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService,
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                executionTool, taskRuntimeService, new ReplanScopeService(taskRuntimeService), new CompletedArtifactIntentRecoveryService(sessionResolver), followUpArtifactContextResolver, conversationTaskStateService,
                pendingFollowUpRecommendationMatcher, null, null,
                pendingFollowUpRecommendationMatcher == null ? null : new PendingFollowUpConflictArbiter(pendingFollowUpRecommendationMatcher),
                new PlannerProperties());
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService,
            ReplanScopeService replanScopeService,
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                executionTool, taskRuntimeService, replanScopeService, new CompletedArtifactIntentRecoveryService(sessionResolver), followUpArtifactContextResolver, conversationTaskStateService,
                pendingFollowUpRecommendationMatcher, null, null,
                pendingFollowUpRecommendationMatcher == null ? null : new PendingFollowUpConflictArbiter(pendingFollowUpRecommendationMatcher),
                new PlannerProperties());
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                null, null, new ReplanScopeService(null), completedArtifactIntentRecoveryService, null, null, null, null, null, null, new PlannerProperties());
    }

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner,
            PlannerExecutionTool executionTool,
            TaskRuntimeService taskRuntimeService,
            ReplanScopeService replanScopeService,
            CompletedArtifactIntentRecoveryService completedArtifactIntentRecoveryService,
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher,
            FollowUpRecommendationExecutionService followUpRecommendationExecutionService,
            ReadOnlyNodeService readOnlyNodeService
    ) {
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner,
                executionTool, taskRuntimeService, replanScopeService, completedArtifactIntentRecoveryService,
                followUpArtifactContextResolver, conversationTaskStateService, pendingFollowUpRecommendationMatcher,
                followUpRecommendationExecutionService, readOnlyNodeService,
                pendingFollowUpRecommendationMatcher == null ? null : new PendingFollowUpConflictArbiter(pendingFollowUpRecommendationMatcher),
                new PlannerProperties());
    }

    public PlanTaskSession handlePlanRequest(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        TaskSessionResolution resolution = sessionResolver.resolve(taskId, workspaceContext);
        PlanTaskSession session = resolution.existingSession()
                ? sessionService.get(resolution.taskId())
                : transientSession(resolution.taskId(), workspaceContext);
        PlanTaskSession currentTaskContinuationChoiceResult = tryResumePendingCurrentTaskContinuationChoice(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext
        );
        if (currentTaskContinuationChoiceResult != null) {
            return currentTaskContinuationChoiceResult;
        }
        PlanTaskSession followUpConflictChoiceResult = tryResumePendingFollowUpConflictChoice(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext
        );
        if (followUpConflictChoiceResult != null) {
            return followUpConflictChoiceResult;
        }
        TaskIntakeDecision preliminaryIntakeDecision = intakeService.decide(
                session,
                rawInstruction,
                userFeedback,
                resolution.existingSession()
        );
        TaskIntakeDecision executingAdjustmentClarification = absorbExecutingPlanAdjustmentClarification(
                session,
                resolution,
                preliminaryIntakeDecision,
                userFeedback,
                rawInstruction
        );
        boolean resumedExecutingPlanAdjustmentClarification = executingAdjustmentClarification != null;
        if (executingAdjustmentClarification != null) {
            preliminaryIntakeDecision = executingAdjustmentClarification;
        }
        boolean bypassPendingSelections = isForcedNewTask(preliminaryIntakeDecision);
        PlanTaskSession artifactSelectionResult = tryResumePendingArtifactSelection(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext,
                bypassPendingSelections
        );
        if (artifactSelectionResult != null) {
            return artifactSelectionResult;
        }
        PlanTaskSession selectionResult = tryResumePendingTaskSelection(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext,
                bypassPendingSelections,
                preliminaryIntakeDecision
        );
        if (selectionResult != null) {
            return selectionResult;
        }
        String userInput = firstText(userFeedback, rawInstruction);
        String graphInstruction = stripLeadingMentionPlaceholders(userInput);

        TaskIntakeDecision intakeDecision = preliminaryIntakeDecision;
        intakeDecision = absorbDocLinksDuringClarification(session, resolution, workspaceContext, intakeDecision, userFeedback, rawInstruction);
        intakeDecision = absorbSourceContextSupplementForReadyPlan(session, resolution, workspaceContext, intakeDecision, userFeedback, rawInstruction);
        CompletedArtifactIntentRecoveryService.RecoveryResult recoveryResult =
                completedArtifactIntentRecoveryService == null
                        ? CompletedArtifactIntentRecoveryService.RecoveryResult.none()
                        : completedArtifactIntentRecoveryService.recoverTaskIntake(
                                session,
                                resolution,
                                workspaceContext,
                                intakeDecision,
                                graphInstruction.isBlank() ? intakeDecision.effectiveInput() : graphInstruction
                        );
        if (recoveryResult.type() == CompletedArtifactIntentRecoveryService.RecoveryType.RECOVERED
                && recoveryResult.recoveredDecision() != null) {
            log.info("completed_artifact_new_task_recovered taskId={} instruction='{}' originalIntakeType={} recoveredIntakeType={} artifactTypes={} artifactCount={} explicitArtifactType={} reason={}",
                    session == null ? null : session.getTaskId(),
                    graphInstruction,
                    intakeDecision == null ? null : intakeDecision.intakeType(),
                    recoveryResult.recoveredDecision().intakeType(),
                    recoveryResult.candidates().stream().map(ArtifactRecord::getType).distinct().toList(),
                    recoveryResult.candidates().size(),
                    recoveryResult.recoveredArtifactType(),
                    recoveryResult.reason());
            intakeDecision = recoveryResult.recoveredDecision();
        } else if (recoveryResult.type() == CompletedArtifactIntentRecoveryService.RecoveryType.SELECTION_REQUIRED) {
            log.info("completed_artifact_new_task_recovery_selection_required taskId={} instruction='{}' originalIntakeType={} artifactTypes={} artifactCount={} explicitArtifactType={} reason={}",
                    session == null ? null : session.getTaskId(),
                    graphInstruction,
                    intakeDecision == null ? null : intakeDecision.intakeType(),
                    recoveryResult.candidates().stream().map(ArtifactRecord::getType).distinct().toList(),
                    recoveryResult.candidates().size(),
                    recoveryResult.recoveredArtifactType(),
                    recoveryResult.reason());
            return startRecoveredArtifactSelection(session, graphInstruction, workspaceContext, recoveryResult.candidates());
        } else if (intakeDecision != null && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK && isCompleted(session)) {
            log.info("completed_artifact_new_task_recovery_rejected taskId={} instruction='{}' originalIntakeType={} reason={}",
                    session == null ? null : session.getTaskId(),
                    graphInstruction,
                    intakeDecision.intakeType(),
                    recoveryResult.reason());
        }
        clearPendingFollowUpRecommendationsIfExplicitNewTask(resolution, intakeDecision);
        PlanTaskSession earlyCompletedArtifactAdjustment = tryRouteCurrentCompletedArtifactAdjustment(
                taskId,
                resolution,
                session,
                intakeDecision,
                workspaceContext,
                graphInstruction
        );
        if (earlyCompletedArtifactAdjustment != null) {
            return earlyCompletedArtifactAdjustment;
        }
        CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation =
                completedArtifactIntentRecoveryService == null
                        ? CompletedArtifactIntentRecoveryService.DirectRouteEvaluation.none("recovery service unavailable")
                        : completedArtifactIntentRecoveryService.evaluateCurrentCompletedArtifactRoute(
                                session,
                                resolution,
                                workspaceContext,
                                graphInstruction
                        );
        if (intakeDecision != null
                && intakeDecision.intakeType() == TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            if (directRouteEvaluation.type() == CompletedArtifactIntentRecoveryService.DirectRouteType.SELECTION_REQUIRED) {
                return startRecoveredArtifactSelection(session, graphInstruction, workspaceContext, directRouteEvaluation.candidates());
            }
            if (directRouteEvaluation.type() == CompletedArtifactIntentRecoveryService.DirectRouteType.DIRECT_ROUTE) {
                PlanTaskSession directCompletedArtifactAdjustment = tryRouteCurrentCompletedArtifactAdjustment(
                        taskId,
                        resolution,
                        session,
                        intakeDecision,
                        workspaceContext,
                        graphInstruction
                );
                if (directCompletedArtifactAdjustment != null) {
                    return directCompletedArtifactAdjustment;
                }
            }
        }
        CurrentTaskContinuationArbiter.Decision followUpDecision = evaluatePendingFollowUpConflict(
                session,
                resolution,
                workspaceContext,
                intakeDecision,
                userInput,
                directRouteEvaluation
        );
        if (followUpDecision != null) {
            if (followUpDecision.type() == CurrentTaskContinuationArbiter.DecisionType.ASK_NEW_OR_CURRENT) {
                return currentTaskContinuationChoiceReply(session, workspaceContext, resolution, userInput, followUpDecision);
            }
            if (followUpDecision.type() == CurrentTaskContinuationArbiter.DecisionType.ASK_CURRENT_TASK_SELECTION
                    && followUpDecision.candidateRecommendations() != null
                    && !followUpDecision.candidateRecommendations().isEmpty()) {
                if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
                    conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), true);
                }
                return followUpSelectionReply(session, followUpDecision.candidateRecommendations());
            }
            if (followUpDecision.type() == CurrentTaskContinuationArbiter.DecisionType.PROCEED_NEW_TASK
                    || followUpDecision.type() == CurrentTaskContinuationArbiter.DecisionType.NO_DECISION
                    || followUpDecision.type() == CurrentTaskContinuationArbiter.DecisionType.BYPASS_TO_COMPLETED_ARTIFACT_EDIT) {
                // Let normal routing continue.
            } else {
                PlanTaskSession followUpResult = tryResumePendingFollowUpRecommendation(
                        session,
                        resolution,
                        userInput,
                        workspaceContext,
                        intakeDecision
                );
                if (followUpResult != null) {
                    return finalizePlanAdjustmentResult(followUpResult);
                }
            }
        } else {
            PlanTaskSession followUpResult = tryResumePendingFollowUpRecommendation(
                    session,
                    resolution,
                    userInput,
                    workspaceContext,
                    intakeDecision
            );
            if (followUpResult != null) {
                return finalizePlanAdjustmentResult(followUpResult);
            }
        }
        if (shouldStartFreshTask(taskId, resolution, intakeDecision)) {
            workspaceContext = carryForwardCompletedArtifactContext(session, workspaceContext, intakeDecision, graphInstruction);
            resolution = new TaskSessionResolution(UUID.randomUUID().toString(), false, resolution.continuationKey());
            session = transientSession(resolution.taskId(), workspaceContext);
        }
        if (graphInstruction.isBlank()) {
            graphInstruction = intakeDecision.effectiveInput();
        }
        PlanTaskSession pureReadOnly = tryHandlePureReadOnlyRequest(
                session,
                resolution,
                workspaceContext,
                intakeDecision,
                graphInstruction
        );
        if (pureReadOnly != null) {
            return pureReadOnly;
        }
        // 用户在回应我们的澄清问题时，对"修改已有产物"的表述不能依赖 LLM 分类，用硬规则强制纠正
        intakeDecision = refineClarificationResponseTarget(session, intakeDecision, graphInstruction);
        // 在中断逻辑修改 intakeState 之前取出原始指令，updateSessionEnvelope 会重建 intakeState
        String pendingAdjustmentInstruction = session.getIntakeState() != null
                ? session.getIntakeState().getPendingAdjustmentInstruction()
                : null;
        if (shouldAutoInterruptExecutingTaskForReplan(taskId, resolution, session, intakeDecision, workspaceContext)) {
            return autoInterruptExecutingTaskForReplan(session, resolution, workspaceContext, intakeDecision, graphInstruction, pendingAdjustmentInstruction);
        }
        if (shouldRejectExecutingCompletedArtifactAdjustment(taskId, resolution, session, intakeDecision, workspaceContext)) {
            memoryService.appendUserTurn(session, graphInstruction, intakeDecision.intakeType(),
                    workspaceContext == null ? null : workspaceContext.getInputSource());
            PlanTaskSession rejected = rejectExecutingCompletedArtifactAdjustment(session);
            if (rejected != null && rejected.getIntakeState() != null
                    && hasText(rejected.getIntakeState().getAssistantReply())) {
                memoryService.appendAssistantTurn(rejected, rejected.getIntakeState().getAssistantReply());
            }
            // 拒绝后清除澄清标记，后续消息不再视为澄清回应
            if (rejected != null && rejected.getIntakeState() != null
                    && rejected.getIntakeState().getPendingInteractionType()
                            == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT) {
                rejected.getIntakeState().setPendingInteractionType(null);
                saveWithoutVersionChangeBestEffort(rejected, current -> {
                    if (current.getIntakeState() != null) {
                        current.getIntakeState().setPendingInteractionType(null);
                    }
                }, "clear_rejected_executing_adjustment_pending");
            }
            return rejected;
        }
        if (shouldClarifyExecutingAdjustmentTarget(taskId, resolution, session, intakeDecision, workspaceContext)) {
            memoryService.appendUserTurn(session, graphInstruction, intakeDecision.intakeType(),
                    workspaceContext == null ? null : workspaceContext.getInputSource());
            PlanTaskSession clarified = clarifyExecutingAdjustmentTarget(session, graphInstruction);
            if (clarified != null && clarified.getIntakeState() != null
                    && hasText(clarified.getIntakeState().getAssistantReply())) {
                memoryService.appendAssistantTurn(clarified, clarified.getIntakeState().getAssistantReply());
            }
            return clarified;
        }
        PlanTaskSession directCompletedArtifactAdjustment = tryRouteCurrentCompletedArtifactAdjustment(
                taskId,
                resolution,
                session,
                intakeDecision,
                workspaceContext,
                graphInstruction
        );
        if (directCompletedArtifactAdjustment != null) {
            return directCompletedArtifactAdjustment;
        }
        if (shouldRouteCompletedAdjustment(taskId, resolution, session, intakeDecision, workspaceContext)) {
            PlanTaskSession adjustmentResult = routeCompletedAdjustmentTaskSelection(
                    graphInstruction,
                    workspaceContext,
                    resolution,
                    session
            );
            if (adjustmentResult != null) {
                return adjustmentResult;
            }
        }
        if (shouldRouteCompletedTaskList(intakeDecision, workspaceContext)) {
            PlanTaskSession listResult = routeCompletedTaskList(
                    graphInstruction,
                    workspaceContext,
                    resolution,
                    session
            );
            if (listResult != null) {
                return listResult;
            }
        }
        PlanTaskSession resumedExecuting = tryResumeExecutingAfterInterruptedAdjustmentClarification(
                session,
                intakeDecision,
                graphInstruction
        );
        if (resumedExecuting != null) {
            return resumedExecuting;
        }
        if (shouldRejectPrematureExecutionConfirmation(session, intakeDecision)) {
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "当前还没到可执行阶段。我会先把计划或上下文准备好，等进入可执行状态后你再回复“开始执行”。"
            );
        }
        if (shouldShortCircuitWithoutTask(resolution, intakeDecision)) {
            updateSessionEnvelope(session, workspaceContext, intakeDecision, resolution, graphInstruction);
            return session;
        }
        if (!resolution.existingSession()) {
            session = sessionService.getOrCreate(resolution.taskId());
        }
        if (shouldBindConversation(resolution, intakeDecision)) {
            sessionResolver.bindConversation(resolution);
        }

        updateSessionEnvelope(session, workspaceContext, intakeDecision, resolution, graphInstruction);
        memoryService.appendUserTurn(
                session,
                graphInstruction,
                intakeDecision.intakeType(),
                workspaceContext == null ? null : workspaceContext.getInputSource());
        if (intakeDecision.assistantReply() != null && !intakeDecision.assistantReply().isBlank()) {
            memoryService.appendAssistantTurn(session, intakeDecision.assistantReply());
        }
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(session.getTaskId(), "INTAKE_ACCEPTED");

        PlanTaskSession result = graphRunner.run(
                PlannerSupervisorDecision.fromIntake(intakeDecision.intakeType(), intakeDecision.routingReason()),
                session.getTaskId(),
                graphInstruction,
                workspaceContext,
                userFeedback
        );
        taskBridgeService.ensureTask(result);
        markAwaitingExecutionConfirmationIfNeeded(result);
        if (resumedExecutingPlanAdjustmentClarification && result != null) {
            if (result.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
                markPendingInteractionType(result, PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
                markResumeOriginalExecutionAvailable(result, true);
            } else {
                markResumeOriginalExecutionAvailable(result, false);
            }
        }
        return result;
    }

    private PlanTaskSession tryResumePendingArtifactSelection(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String rawInstruction,
            String userFeedback,
            WorkspaceContext workspaceContext,
            boolean bypassPendingSelection
    ) {
        if (bypassPendingSelection) {
            return null;
        }
        PendingArtifactSelection selection = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getPendingArtifactSelection();
        if (selection == null) {
            return null;
        }
        String input = firstText(userFeedback, rawInstruction);
        if (selection.getExpiresAt() != null && selection.getExpiresAt().isBefore(Instant.now())) {
            return pendingArtifactSelectionReply(
                    session,
                    selection,
                    "这个产物选择已经过期了。你可以重新说一下要修改哪个产物。"
            );
        }
        Integer index = parseCandidateIndex(input);
        List<PendingArtifactCandidate> candidates = selection.getCandidates() == null ? List.of() : selection.getCandidates();
        if (index == null || index < 1 || index > candidates.size()) {
            return pendingArtifactSelectionReply(
                    session,
                    selection,
                    "我还没识别出要选哪一个产物，请直接回复候选产物前面的编号。"
            );
        }
        PendingArtifactCandidate candidate = candidates.get(index - 1);
        session.getIntakeState().setPendingArtifactSelection(null);
        session.getIntakeState().setPendingInteractionType(null);
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        saveWithoutVersionChangeBestEffort(session, current -> {
            current.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingArtifactSelection(null);
                current.getIntakeState().setPendingInteractionType(null);
            }
        }, "pending_artifact_selection_clear");
        String instruction = appendTargetArtifact(selection.getOriginalInstruction(), candidate.getArtifactId());
        PlanTaskSession result = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "artifact selected for completed task adjustment"),
                selection.getTaskId(),
                instruction,
                workspaceContext,
                null
        );
        taskBridgeService.ensureTask(result);
        return result;
    }

    private PlanTaskSession pendingArtifactSelectionReply(
            PlanTaskSession session,
            PendingArtifactSelection selection,
            String reply
    ) {
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(reply);
        intakeState.setPendingArtifactSelection(selection);
        intakeState.setPendingAdjustmentInstruction(selection.getOriginalInstruction());
        intakeState.setPendingInteractionType(PendingInteractionTypeEnum.COMPLETED_ARTIFACT_SELECTION);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
            currentState.setAssistantReply(reply);
            currentState.setPendingArtifactSelection(selection);
            currentState.setPendingAdjustmentInstruction(selection.getOriginalInstruction());
            currentState.setPendingInteractionType(PendingInteractionTypeEnum.COMPLETED_ARTIFACT_SELECTION);
            current.setIntakeState(currentState);
        }, "pending_artifact_selection_reply");
        return session;
    }

    private String appendTargetArtifact(String instruction, String artifactId) {
        String safeInstruction = instruction == null ? "" : instruction.trim();
        if (!hasText(artifactId)) {
            return safeInstruction;
        }
        return safeInstruction + "\n目标产物ID：" + artifactId;
    }

    private PlanTaskSession tryResumePendingTaskSelection(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String rawInstruction,
            String userFeedback,
            WorkspaceContext workspaceContext,
            boolean bypassPendingSelection,
            TaskIntakeDecision preliminaryIntakeDecision
    ) {
        if (bypassPendingSelection) {
            return null;
        }
        PendingTaskSelection selection = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getPendingTaskSelection();
        if (selection == null) {
            return null;
        }
        String input = firstText(userFeedback, rawInstruction);
        if (selection.getExpiresAt() != null && selection.getExpiresAt().isBefore(Instant.now())) {
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "这个选择已经过期了。你可以重新说一下要修改哪个已完成任务。"
            );
        }
        Integer index = parseCandidateIndex(input);
        if (index == null && shouldReplayCompletedTaskList(selection, preliminaryIntakeDecision)) {
            return routeCompletedTaskList(
                    firstText(selection.getOriginalInstruction(), input),
                    workspaceContext,
                    resolution,
                    session
            );
        }
        List<PendingTaskCandidate> candidates = selection.getCandidates() == null ? List.of() : selection.getCandidates();
        if (index == null || index < 1 || index > candidates.size()) {
            PlanTaskSession replySession = transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "我还没识别出要选哪一个，请直接回复候选任务前面的编号。"
            );
            if (replySession.getIntakeState() != null) {
                replySession.getIntakeState().setPendingTaskSelection(selection);
                saveWithoutVersionChangeBestEffort(replySession, current -> {
                    if (current.getIntakeState() != null) {
                        current.getIntakeState().setPendingTaskSelection(selection);
                    }
                }, "pending_task_selection_restore");
            }
            return replySession;
        }
        PendingTaskCandidate candidate = candidates.get(index - 1);
        session.getIntakeState().setPendingTaskSelection(null);
        session.getIntakeState().setPendingInteractionType(null);
        saveWithoutVersionChangeBestEffort(session, current -> {
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingTaskSelection(null);
                current.getIntakeState().setPendingInteractionType(null);
            }
        }, "pending_task_selection_clear");
        sessionResolver.bindConversation(new TaskSessionResolution(candidate.getTaskId(), true, resolution.continuationKey()));
        if (SELECTION_PURPOSE_COMPLETED_TASK_LIST.equals(selection.getSelectionPurpose())) {
            return buildCompletedTaskListSelectionReply(
                    candidate,
                    workspaceContext,
                    resolution,
                    selection.getOriginalInstruction()
            );
        }
        refreshSelectedTaskContext(candidate.getTaskId(), workspaceContext, resolution, selection.getOriginalInstruction());
        String routedInstruction = routeInstructionToEditableArtifact(candidate.getTaskId(), selection.getOriginalInstruction());
        PlanTaskSession result = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "completed task selected for adjustment"),
                candidate.getTaskId(),
                routedInstruction,
                workspaceContext,
                null
        );
        taskBridgeService.ensureTask(result);
        return result;
    }

    private PlanTaskSession routeCompletedAdjustmentTaskSelection(
            String instruction,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            PlanTaskSession session
    ) {
        List<PendingTaskCandidate> candidates = sessionResolver.resolveCompletedCandidates(workspaceContext);
        if (candidates.isEmpty()) {
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "我还没找到这个聊天里你可修改的已完成任务。你可以先在任务工作台里选择具体任务，或说明任务标题。"
            );
        }
        if (candidates.size() == 1) {
            PendingTaskCandidate candidate = candidates.get(0);
            sessionResolver.bindConversation(new TaskSessionResolution(candidate.getTaskId(), true, resolution.continuationKey()));
            refreshSelectedTaskContext(candidate.getTaskId(), workspaceContext, resolution, instruction);
            String routedInstruction = routeInstructionToEditableArtifact(candidate.getTaskId(), instruction);
            PlanTaskSession result = graphRunner.run(
                    new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "single completed task adjusted from conversation"),
                    candidate.getTaskId(),
                    routedInstruction,
                    workspaceContext,
                    null
            );
            taskBridgeService.ensureTask(result);
            return result;
        }
        return pendingTaskSelectionReply(session, workspaceContext, resolution, instruction, candidates);
    }

    private PlanTaskSession routeCompletedTaskList(
            String instruction,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            PlanTaskSession session
    ) {
        List<PendingTaskCandidate> candidates = sessionResolver.resolveCompletedCandidates(workspaceContext);
        if (candidates.isEmpty()) {
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "我还没找到这个聊天里已完成的任务。你可以先完成一个任务，或者去任务工作台查看。"
            );
        }
        return pendingTaskSelectionReply(
                session,
                workspaceContext,
                resolution,
                instruction,
                candidates,
                SELECTION_PURPOSE_COMPLETED_TASK_LIST
        );
    }

    private void refreshSelectedTaskContext(
            String taskId,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction
    ) {
        refreshSelectedTaskContext(
                taskId,
                workspaceContext,
                resolution,
                instruction,
                TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                "completed task adjustment from current IM message",
                null,
                null,
                null
        );
    }

    private void refreshSelectedTaskContext(
            String taskId,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            TaskIntakeTypeEnum intakeType,
            String routingReason,
            String assistantReply,
            String readOnlyView,
            AdjustmentTargetEnum adjustmentTarget
    ) {
        if (!hasText(taskId)) {
            return;
        }
        PlanTaskSession selected = sessionService.get(taskId);
        if (selected == null) {
            return;
        }
        updateSessionEnvelope(
                selected,
                workspaceContext,
                new TaskIntakeDecision(
                        intakeType,
                        instruction,
                        routingReason,
                        assistantReply,
                        readOnlyView,
                        adjustmentTarget),
                new TaskSessionResolution(taskId, true, resolution == null ? null : resolution.continuationKey()),
                instruction
        );
        if (selected.getIntakeState() != null) {
            selected.getIntakeState().setPendingTaskSelection(null);
            selected.getIntakeState().setPendingInteractionType(null);
        }
        saveWithoutVersionChangeBestEffort(selected, current -> {
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingTaskSelection(null);
                current.getIntakeState().setPendingInteractionType(null);
            }
        }, "refresh_selected_task_context");
    }

    private PlanTaskSession buildCompletedTaskListSelectionReply(
            PendingTaskCandidate candidate,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction
    ) {
        if (candidate == null || !hasText(candidate.getTaskId())) {
            return null;
        }
        PlanTaskSession selected = sessionService.get(candidate.getTaskId());
        if (selected == null) {
            return null;
        }
        TaskIntakeState intakeState = selected.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : selected.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.STATUS_QUERY);
        intakeState.setContinuedConversation(true);
        intakeState.setContinuationKey(resolution == null ? null : resolution.continuationKey());
        intakeState.setLastUserMessage(firstText(instruction, "查看已完成任务"));
        intakeState.setRoutingReason("completed task selected from list");
        intakeState.setAssistantReply(selectedCompletedTaskReply(candidate));
        intakeState.setReadOnlyView("COMPLETED_TASKS");
        selected.setIntakeState(intakeState);
        log.info("read_only_skipped_session_persist taskId={} planningPhase={} intakeType={} readOnlyView={} conversationKey={}",
                selected.getTaskId(),
                selected.getPlanningPhase(),
                intakeState.getIntakeType(),
                intakeState.getReadOnlyView(),
                resolution == null ? null : resolution.continuationKey());
        if (conversationTaskStateService != null) {
            conversationTaskStateService.syncFromSession(selected);
        }
        return selected;
    }

    private PlanTaskSession pendingTaskSelectionReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            List<PendingTaskCandidate> candidates
    ) {
        return pendingTaskSelectionReply(
                session,
                workspaceContext,
                resolution,
                instruction,
                candidates,
                SELECTION_PURPOSE_COMPLETED_TASK_ADJUSTMENT
        );
    }

    private PlanTaskSession pendingTaskSelectionReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            List<PendingTaskCandidate> candidates,
            String selectionPurpose
    ) {
        PlanTaskSession selectionSession = session;
        TaskSessionResolution selectionResolution = resolution;
        if (isCompleted(session)) {
            selectionSession = transientSession(UUID.randomUUID().toString(), workspaceContext);
            selectionResolution = new TaskSessionResolution(
                    selectionSession.getTaskId(),
                    false,
                    resolution == null ? null : resolution.continuationKey()
            );
        }
        String reply = buildCandidateReply(candidates, selectionPurpose);
        updateSessionEnvelope(
                selectionSession,
                workspaceContext,
                new TaskIntakeDecision(TaskIntakeTypeEnum.UNKNOWN, instruction, "multiple completed tasks require user selection", reply, null),
                selectionResolution,
                instruction
        );
        selectionSession.getIntakeState().setPendingTaskSelection(PendingTaskSelection.builder()
                .conversationKey(sessionResolver.conversationKey(workspaceContext))
                .originalInstruction(instruction)
                .selectionPurpose(selectionPurpose)
                .candidates(candidates)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());
        selectionSession.getIntakeState().setPendingInteractionType(PendingInteractionTypeEnum.COMPLETED_TASK_SELECTION);
        selectionSession.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        saveWithoutVersionChangeBestEffort(selectionSession, current -> current.setPlanningPhase(PlanningPhaseEnum.INTAKE),
                "pending_task_selection_reply");
        if (conversationTaskStateService != null) {
            conversationTaskStateService.syncFromSession(selectionSession);
        }
        sessionResolver.bindConversation(new TaskSessionResolution(
                selectionSession.getTaskId(),
                false,
                resolution == null ? null : resolution.continuationKey()
        ));
        return selectionSession;
    }

    private PlanTaskSession currentTaskContinuationChoiceReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            CurrentTaskContinuationArbiter.Decision decision
    ) {
        PendingFollowUpRecommendation recommendation = decision == null ? null : decision.selectedRecommendation();
        if (recommendation == null && decision != null
                && decision.candidateRecommendations() != null
                && decision.candidateRecommendations().size() == 1) {
            recommendation = decision.candidateRecommendations().get(0);
        }
        PlanTaskSession choiceSession = session;
        TaskSessionResolution choiceResolution = resolution;
        if (isCompleted(session)) {
            choiceSession = transientSession(UUID.randomUUID().toString(), workspaceContext);
            choiceResolution = new TaskSessionResolution(
                    choiceSession.getTaskId(),
                    false,
                    resolution == null ? null : resolution.continuationKey()
            );
        }
        String reply = buildCurrentTaskContinuationPrompt(instruction, recommendation);
        updateSessionEnvelope(
                choiceSession,
                workspaceContext,
                new TaskIntakeDecision(TaskIntakeTypeEnum.UNKNOWN, instruction, "current task continuation requires user choice", reply, null),
                choiceResolution,
                instruction
        );
        TaskIntakeState intakeState = choiceSession.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : choiceSession.getIntakeState();
        intakeState.setAssistantReply(reply);
        intakeState.setPendingCurrentTaskContinuationChoice(PendingCurrentTaskContinuationChoice.builder()
                .conversationKey(sessionResolver.conversationKey(workspaceContext))
                .originalInstruction(instruction)
                .targetTaskId(session == null ? null : session.getTaskId())
                .continuationType("FOLLOW_UP_RECOMMENDATION")
                .selectedRecommendationId(recommendation == null ? null : recommendation.getRecommendationId())
                .candidateRecommendationIds(decision == null || decision.candidateRecommendations() == null
                        ? List.of()
                        : decision.candidateRecommendations().stream()
                                .filter(value -> value != null && hasText(value.getRecommendationId()))
                                .map(PendingFollowUpRecommendation::getRecommendationId)
                                .toList())
                .newTaskInstruction(instruction)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());
        intakeState.setPendingInteractionType(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE);
        choiceSession.setIntakeState(intakeState);
        choiceSession.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        saveWithoutVersionChangeBestEffort(choiceSession, current -> current.setPlanningPhase(PlanningPhaseEnum.ASK_USER),
                "pending_current_task_continuation_choice_reply");
        if (conversationTaskStateService != null) {
            conversationTaskStateService.syncFromSession(choiceSession);
        }
        sessionResolver.bindConversation(new TaskSessionResolution(
                choiceSession.getTaskId(),
                false,
                resolution == null ? null : resolution.continuationKey()
        ));
        return choiceSession;
    }

    private PlanTaskSession followUpConflictChoiceReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            PendingFollowUpRecommendation recommendation
    ) {
        if (recommendation == null) {
            return session;
        }
        PlanTaskSession choiceSession = session;
        TaskSessionResolution choiceResolution = resolution;
        if (isCompleted(session)) {
            choiceSession = transientSession(UUID.randomUUID().toString(), workspaceContext);
            choiceResolution = new TaskSessionResolution(
                    choiceSession.getTaskId(),
                    false,
                    resolution == null ? null : resolution.continuationKey()
            );
        }
        String reply = buildFollowUpConflictPrompt(instruction, recommendation);
        updateSessionEnvelope(
                choiceSession,
                workspaceContext,
                new TaskIntakeDecision(TaskIntakeTypeEnum.UNKNOWN, instruction, "follow-up conflict requires user choice", reply, null),
                choiceResolution,
                instruction
        );
        TaskIntakeState intakeState = choiceSession.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : choiceSession.getIntakeState();
        intakeState.setAssistantReply(reply);
        intakeState.setPendingFollowUpConflictChoice(PendingFollowUpConflictChoice.builder()
                .conversationKey(sessionResolver.conversationKey(workspaceContext))
                .originalInstruction(instruction)
                .selectedRecommendationId(recommendation.getRecommendationId())
                .selectedRecommendationTitle(firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction()))
                .selectedRecommendationPlannerInstruction(recommendation.getPlannerInstruction())
                .targetTaskId(recommendation.getTargetTaskId())
                .newTaskInstruction(instruction)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());
        intakeState.setPendingInteractionType(PendingInteractionTypeEnum.FOLLOW_UP_CONFLICT_CHOICE);
        choiceSession.setIntakeState(intakeState);
        choiceSession.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        saveWithoutVersionChangeBestEffort(choiceSession, current -> current.setPlanningPhase(PlanningPhaseEnum.ASK_USER),
                "pending_followup_conflict_choice_reply");
        if (conversationTaskStateService != null) {
            conversationTaskStateService.syncFromSession(choiceSession);
        }
        sessionResolver.bindConversation(new TaskSessionResolution(
                choiceSession.getTaskId(),
                false,
                resolution == null ? null : resolution.continuationKey()
        ));
        return choiceSession;
    }

    private PlanTaskSession tryResumePendingCurrentTaskContinuationChoice(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String rawInstruction,
            String userFeedback,
            WorkspaceContext workspaceContext
    ) {
        PendingCurrentTaskContinuationChoice choice = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getPendingCurrentTaskContinuationChoice();
        if (choice == null) {
            return null;
        }
        if (choice.getExpiresAt() != null && choice.getExpiresAt().isBefore(Instant.now())) {
            clearPendingCurrentTaskContinuationChoice(session);
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "这个选择已经过期了。你可以重新说一下是要新开一个任务，还是继续当前任务。"
            );
        }
        String input = firstText(userFeedback, rawInstruction);
        Integer index = parseCandidateIndex(input);
        if (index == null || (index != 1 && index != 2)) {
            TaskIntakeState intakeState = session.getIntakeState();
            intakeState.setAssistantReply("请直接回复 1 或 2：1 新开任务，2 继续当前任务。");
            saveWithoutVersionChangeBestEffort(session, current -> {
                if (current.getIntakeState() != null) {
                    current.getIntakeState().setAssistantReply("请直接回复 1 或 2：1 新开任务，2 继续当前任务。");
                }
            }, "pending_current_task_continuation_choice_invalid_reply");
            return session;
        }
        if (index == 1) {
            String newTaskInstruction = firstText(choice.getNewTaskInstruction(), choice.getOriginalInstruction());
            clearPendingCurrentTaskContinuationChoice(session);
            if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
                conversationTaskStateService.clearPendingFollowUpRecommendations(resolution.continuationKey());
            }
            return handlePlanRequest(newTaskInstruction, workspaceContext, null, null);
        }

        List<PendingFollowUpRecommendation> candidates = resolvePendingFollowUpRecommendations(choice, resolution);
        PendingFollowUpRecommendation recommendation = candidates.stream()
                .filter(value -> value != null
                        && hasText(value.getRecommendationId())
                        && value.getRecommendationId().equals(choice.getSelectedRecommendationId()))
                .findFirst()
                .orElse(candidates.size() == 1 ? candidates.get(0) : null);
        clearPendingCurrentTaskContinuationChoice(session);
        if (hasText(choice.getTargetTaskId())) {
            sessionResolver.bindConversation(new TaskSessionResolution(
                    choice.getTargetTaskId(),
                    true,
                    resolution == null ? null : resolution.continuationKey()
            ));
        }
        if (recommendation == null) {
            if (!candidates.isEmpty()) {
                if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
                    conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), false);
                }
                return currentTaskActionSelectionReply(session, choice, candidates, resolution);
            }
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "我没找到刚才那条可继续的当前任务动作了。你可以重新说一下要继续哪个任务。"
            );
        }
        if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
            conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), false);
        }
        return currentTaskActionSelectionReply(session, choice, List.of(recommendation), resolution);
    }

    private PlanTaskSession currentTaskActionSelectionReply(
            PlanTaskSession session,
            PendingCurrentTaskContinuationChoice choice,
            List<PendingFollowUpRecommendation> recommendations,
            TaskSessionResolution resolution
    ) {
        String targetTaskId = choice == null ? null : choice.getTargetTaskId();
        PlanTaskSession response = hasText(targetTaskId) ? sessionService.get(targetTaskId) : session;
        if (response == null) {
            response = session == null ? PlanTaskSession.builder().taskId(UUID.randomUUID().toString()).build() : session;
        }
        TaskIntakeState intakeState = response.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : response.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.STATUS_QUERY);
        intakeState.setContinuedConversation(true);
        intakeState.setContinuationKey(resolution == null ? null : resolution.continuationKey());
        intakeState.setLastUserMessage(firstText(
                choice == null ? null : choice.getOriginalInstruction(),
                choice == null ? null : choice.getNewTaskInstruction()
        ));
        intakeState.setRoutingReason("current completed task resumed from continuation choice");
        intakeState.setAssistantReply(buildCurrentTaskActionSelectionReply(choice, recommendations));
        intakeState.setReadOnlyView("COMPLETED_TASKS");
        intakeState.setPendingInteractionType(null);
        intakeState.setPendingCurrentTaskContinuationChoice(null);
        intakeState.setPendingFollowUpConflictChoice(null);
        response.setIntakeState(intakeState);
        if (conversationTaskStateService != null) {
            conversationTaskStateService.syncFromSession(response);
        }
        return response;
    }

    private String buildCurrentTaskActionSelectionReply(
            PendingCurrentTaskContinuationChoice choice,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        StringBuilder builder = new StringBuilder("已切回这个已完成任务。你可以继续做两类操作：");
        if (recommendations != null && !recommendations.isEmpty()) {
            builder.append("\n\n推荐下一步：");
            for (int index = 0; index < recommendations.size(); index++) {
                PendingFollowUpRecommendation recommendation = recommendations.get(index);
                builder.append("\n").append(index + 1).append(". ")
                        .append(firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction()));
            }
            builder.append("\n回复编号即可执行对应推荐。");
        }
        String targetTaskId = choice == null ? null : choice.getTargetTaskId();
        List<ArtifactRecord> editableArtifacts = hasText(targetTaskId)
                ? sessionResolver.resolveEditableArtifacts(targetTaskId)
                : List.of();
        if (!editableArtifacts.isEmpty()) {
            builder.append("\n\n修改已有产物：");
            for (ArtifactRecord artifact : editableArtifacts) {
                builder.append("\n- ");
                if (artifact.getType() != null) {
                    builder.append("[").append(artifact.getType().name()).append("] ");
                }
                builder.append(firstText(artifact.getTitle(), artifact.getArtifactId()));
                if (hasText(artifact.getUrl())) {
                    builder.append("\n  ").append(artifact.getUrl().trim());
                } else if (hasText(artifact.getPreview())) {
                    builder.append("\n  内容预览已生成，正式链接还在回流中。");
                }
            }
            builder.append("\n也可以直接说要改哪一页、哪一段或新增什么内容。");
        } else {
            builder.append("\n\n也可以直接描述你想在当前任务里继续调整什么。");
        }
        return builder.toString();
    }

    private List<PendingFollowUpRecommendation> resolvePendingFollowUpRecommendations(
            PendingCurrentTaskContinuationChoice choice,
            TaskSessionResolution resolution
    ) {
        if (choice == null
                || conversationTaskStateService == null
                || resolution == null
                || !hasText(resolution.continuationKey())) {
            return List.of();
        }
        List<String> candidateIds = choice.getCandidateRecommendationIds() == null
                ? List.of()
                : choice.getCandidateRecommendationIds();
        return conversationTaskStateService.find(resolution.continuationKey())
                .map(ConversationTaskState::getPendingFollowUpRecommendations)
                .stream()
                .flatMap(List::stream)
                .filter(recommendation -> recommendation != null
                        && hasText(recommendation.getRecommendationId())
                        && (recommendation.getRecommendationId().equals(choice.getSelectedRecommendationId())
                        || candidateIds.contains(recommendation.getRecommendationId())))
                .toList();
    }

    private void clearPendingCurrentTaskContinuationChoice(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setPendingCurrentTaskContinuationChoice(null);
        if (session.getIntakeState().getPendingInteractionType() == PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE) {
            session.getIntakeState().setPendingInteractionType(null);
        }
        saveWithoutVersionChangeBestEffort(session, current -> {
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingCurrentTaskContinuationChoice(null);
                if (current.getIntakeState().getPendingInteractionType() == PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE) {
                    current.getIntakeState().setPendingInteractionType(null);
                }
            }
        }, "pending_current_task_continuation_choice_clear");
    }

    private PlanTaskSession tryResumePendingFollowUpConflictChoice(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String rawInstruction,
            String userFeedback,
            WorkspaceContext workspaceContext
    ) {
        PendingFollowUpConflictChoice choice = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getPendingFollowUpConflictChoice();
        if (choice == null) {
            return null;
        }
        if (choice.getExpiresAt() != null && choice.getExpiresAt().isBefore(Instant.now())) {
            clearPendingFollowUpConflictChoice(session);
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "这个选择已经过期了。你可以重新说一下是要新开一个任务，还是继续上一轮任务。"
            );
        }
        String input = firstText(userFeedback, rawInstruction);
        Integer index = parseCandidateIndex(input);
        if (index == null || (index != 1 && index != 2)) {
            TaskIntakeState intakeState = session.getIntakeState();
            intakeState.setAssistantReply("请直接回复 1 或 2：1 新开任务，2 接着上一个任务。");
            saveWithoutVersionChangeBestEffort(session, current -> {
                if (current.getIntakeState() != null) {
                    current.getIntakeState().setAssistantReply("请直接回复 1 或 2：1 新开任务，2 接着上一个任务。");
                }
            }, "pending_followup_conflict_choice_invalid_reply");
            return session;
        }
        if (index == 1) {
            String newTaskInstruction = firstText(choice.getNewTaskInstruction(), choice.getOriginalInstruction());
            clearPendingFollowUpConflictChoice(session);
            if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
                conversationTaskStateService.clearPendingFollowUpRecommendations(resolution.continuationKey());
            }
            return handlePlanRequest(newTaskInstruction, workspaceContext, null, null);
        }
        PendingFollowUpRecommendation recommendation = resolvePendingFollowUpRecommendation(choice, resolution);
        if (recommendation == null) {
            clearPendingFollowUpConflictChoice(session);
            return transientReply(
                    session,
                    workspaceContext,
                    resolution,
                    "我没找到刚才那条可继续的后续动作了。你可以重新说一下要继续哪个任务。"
            );
        }
        clearPendingFollowUpConflictChoice(session);
        String targetTaskId = hasText(choice.getTargetTaskId()) ? choice.getTargetTaskId() : recommendation.getTargetTaskId();
        if (hasText(targetTaskId)) {
            sessionResolver.bindConversation(new TaskSessionResolution(
                    targetTaskId,
                    true,
                    resolution == null ? null : resolution.continuationKey()
            ));
        }
        if (conversationTaskStateService != null && hasText(resolution == null ? null : resolution.continuationKey())) {
            conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), false);
        }
        PendingCurrentTaskContinuationChoice currentTaskChoice = PendingCurrentTaskContinuationChoice.builder()
                .conversationKey(choice.getConversationKey())
                .originalInstruction(choice.getOriginalInstruction())
                .targetTaskId(targetTaskId)
                .continuationType("FOLLOW_UP_RECOMMENDATION")
                .selectedRecommendationId(recommendation.getRecommendationId())
                .candidateRecommendationIds(List.of(recommendation.getRecommendationId()))
                .newTaskInstruction(choice.getNewTaskInstruction())
                .expiresAt(choice.getExpiresAt())
                .build();
        return currentTaskActionSelectionReply(session, currentTaskChoice, List.of(recommendation), resolution);
    }

    private PendingFollowUpRecommendation resolvePendingFollowUpRecommendation(
            PendingFollowUpConflictChoice choice,
            TaskSessionResolution resolution
    ) {
        if (choice == null
                || conversationTaskStateService == null
                || resolution == null
                || !hasText(resolution.continuationKey())) {
            return null;
        }
        return conversationTaskStateService.find(resolution.continuationKey())
                .map(ConversationTaskState::getPendingFollowUpRecommendations)
                .stream()
                .flatMap(List::stream)
                .filter(recommendation -> recommendation != null
                        && hasText(recommendation.getRecommendationId())
                        && recommendation.getRecommendationId().equals(choice.getSelectedRecommendationId()))
                .findFirst()
                .orElse(null);
    }

    private void clearPendingFollowUpConflictChoice(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setPendingFollowUpConflictChoice(null);
        if (session.getIntakeState().getPendingInteractionType() == PendingInteractionTypeEnum.FOLLOW_UP_CONFLICT_CHOICE) {
            session.getIntakeState().setPendingInteractionType(null);
        }
        saveWithoutVersionChangeBestEffort(session, current -> {
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingFollowUpConflictChoice(null);
                if (current.getIntakeState().getPendingInteractionType() == PendingInteractionTypeEnum.FOLLOW_UP_CONFLICT_CHOICE) {
                    current.getIntakeState().setPendingInteractionType(null);
                }
            }
        }, "pending_followup_conflict_choice_clear");
    }

    private String buildFollowUpConflictPrompt(String newTaskInstruction, PendingFollowUpRecommendation recommendation) {
        String recommendationText = recommendation == null
                ? "接着上一轮任务继续"
                : firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction());
        return "我理解这句话有两种可能：1. 新开一个任务，单独处理“"
                + safeText(newTaskInstruction)
                + "”；2. 接着上一轮任务继续，执行“"
                + recommendationText
                + "”。回复 1 新开任务，回复 2 接着上一个任务。";
    }

    private String buildCurrentTaskContinuationPrompt(String newTaskInstruction, PendingFollowUpRecommendation recommendation) {
        if (recommendation == null) {
            return "我理解这句话有两种可能：1. 新开一个任务，单独处理“"
                    + safeText(newTaskInstruction)
                    + "”；2. 接着当前任务继续处理，但我还需要你再选具体后续动作，或直接说明要修改哪个已有产物。回复 1 新开任务，回复 2 继续当前任务。";
        }
        String recommendationText = firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction());
        return "我理解这句话有两种可能：1. 新开一个任务，单独处理“"
                + safeText(newTaskInstruction)
                + "”；2. 继续当前任务，执行“"
                + recommendationText
                + "”。回复 1 新开任务，回复 2 继续当前任务。";
    }

    private PlanTaskSession transientReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String reply
    ) {
        updateSessionEnvelope(
                session,
                workspaceContext,
                new TaskIntakeDecision(TaskIntakeTypeEnum.UNKNOWN, reply, "completed adjustment transient reply", reply, null),
                resolution,
                reply
        );
        session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        saveWithoutVersionChangeBestEffort(session, current -> current.setPlanningPhase(PlanningPhaseEnum.INTAKE),
                "transient_reply");
        return session;
    }

    private boolean shouldAutoInterruptExecutingTaskForReplan(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext
    ) {
        if (hasText(explicitTaskId)
                || resolution == null
                || !resolution.existingSession()
                || session == null
                || session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                || !isImConversation(workspaceContext)) {
            return false;
        }
        if (intakeDecision == null || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        AdjustmentTargetEnum adjustmentTarget = intakeDecision.adjustmentTarget();
        if (adjustmentTarget == null || adjustmentTarget == AdjustmentTargetEnum.RUNNING_PLAN) {
            return true;
        }
        if (adjustmentTarget == AdjustmentTargetEnum.UNKNOWN) {
            return !shouldClarifyExecutingAdjustmentTarget(
                    explicitTaskId, resolution, session, intakeDecision, workspaceContext);
        }
        return false;
    }

    private boolean shouldRejectExecutingCompletedArtifactAdjustment(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext
    ) {
        if (hasText(explicitTaskId)
                || resolution == null
                || !resolution.existingSession()
                || session == null
                || session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                || !isImConversation(workspaceContext)
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        if (intakeDecision.adjustmentTarget() != AdjustmentTargetEnum.COMPLETED_ARTIFACT) {
            return false;
        }
        // 意图可能模糊，优先询问澄清；有澄清标记时（已问过）才直接拒绝
        return !shouldClarifyExecutingAdjustmentTarget(
                explicitTaskId, resolution, session, intakeDecision, workspaceContext);
    }

    private boolean shouldClarifyExecutingAdjustmentTarget(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext
    ) {
        if (hasText(explicitTaskId)
                || resolution == null
                || !resolution.existingSession()
                || session == null
                || session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                || !isImConversation(workspaceContext)
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        AdjustmentTargetEnum target = intakeDecision.adjustmentTarget();
        if (target != AdjustmentTargetEnum.UNKNOWN && target != AdjustmentTargetEnum.COMPLETED_ARTIFACT) {
            return false;
        }
        // 已经问过一次，下次不再重复，让 shouldAutoInterrupt / shouldReject 直接处理
        if (session.getIntakeState() != null
                && session.getIntakeState().getPendingInteractionType()
                        == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT) {
            return false;
        }
        // 当前任务有产物，或对话内近期已完成任务有产物，才值得问；否则中断是唯一选项
        return sessionResolver.hasEditableArtifacts(session.getTaskId())
                || sessionResolver.conversationHasEditableArtifacts(workspaceContext);
    }

    private PlanTaskSession clarifyExecutingAdjustmentTarget(PlanTaskSession session, String originalInstruction) {
        PlanTaskSession result = updateExecutingAdjustmentReply(
                session,
                "当前有任务正在执行，暂时无法同时修改其他已完成的文档或 PPT。如果你是想调整当前执行计划，我可以先中断并重新规划，需要吗？",
                PlanningPhaseEnum.EXECUTING,
                AdjustmentTargetEnum.UNKNOWN
        );
        // 标记已问过，防止下条消息仍 UNKNOWN 时再次询问
        markPendingInteractionType(result, PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
        // 锁住原始指令，用户确认中断时作为 replan 目标，避免上下文丢失
        if (result != null && result.getIntakeState() != null && hasText(originalInstruction)) {
            result.getIntakeState().setPendingAdjustmentInstruction(originalInstruction);
            sessionService.saveWithoutVersionChange(result);
        }
        return result;
    }

    private TaskIntakeDecision refineClarificationResponseTarget(
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            String input
    ) {
        if (session == null
                || session.getIntakeState() == null
                || session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                || session.getIntakeState().getPendingInteractionType()
                        != PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT
                || intakeDecision.adjustmentTarget() == AdjustmentTargetEnum.COMPLETED_ARTIFACT
                || !hasText(input)) {
            return intakeDecision;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        boolean mentionsArtifact = lower.contains("文档") || lower.contains("ppt")
                || lower.contains("产物") || lower.contains("doc");
        boolean mentionsExisting = lower.contains("已有") || lower.contains("已生成")
                || lower.contains("现有") || lower.contains("之前") || lower.contains("刚生成");
        if (!(mentionsArtifact && mentionsExisting)) {
            return intakeDecision;
        }
        return new TaskIntakeDecision(
                intakeDecision.intakeType(),
                intakeDecision.effectiveInput(),
                intakeDecision.routingReason(),
                intakeDecision.assistantReply(),
                intakeDecision.readOnlyView(),
                AdjustmentTargetEnum.COMPLETED_ARTIFACT
        );
    }

    private PlanTaskSession autoInterruptExecutingTaskForReplan(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            String graphInstruction,
            String pendingAdjustmentInstruction
    ) {
        String replanInstruction = hasText(pendingAdjustmentInstruction) ? pendingAdjustmentInstruction : graphInstruction;
        ReplanScopeService.ReplanScopeDecision scopeDecision = replanScopeService == null
                ? null
                : replanScopeService.inferForInterruptedExecution(session, replanInstruction, false);
        session = saveWithoutVersionChangeRetrying(session, current -> {
            updateSessionEnvelope(current, workspaceContext, intakeDecision, resolution, graphInstruction);
            if (scopeDecision != null) {
                replanScopeService.apply(
                        current,
                        scopeDecision,
                        scopeDecision.scope() != ReplanScopeEnum.FULL_TASK_RESET
                );
            }
            memoryService.appendUserTurn(
                    current,
                    graphInstruction,
                    intakeDecision.intakeType(),
                    workspaceContext == null ? null : workspaceContext.getInputSource());
        });
        sessionService.publishEvent(session.getTaskId(), "INTAKE_ACCEPTED");

        if (taskRuntimeService != null) {
            taskRuntimeService.appendUserIntervention(session.getTaskId(), graphInstruction);
        }
        session = saveWithoutVersionChangeRetrying(session, current -> {
            current.setPlanningPhase(PlanningPhaseEnum.INTERRUPTING);
            current.setTransitionReason("Interrupt current execution for IM plan adjustment");
        });
        sessionService.publishEvent(session.getTaskId(), "INTERRUPTING");
        if (taskRuntimeService != null) {
            taskRuntimeService.projectPhaseTransition(
                    session.getTaskId(),
                    PlanningPhaseEnum.INTERRUPTING,
                    com.lark.imcollab.common.model.enums.TaskEventTypeEnum.EXECUTION_INTERRUPTING
            );
        }

        PlannerToolResult cancelResult = executionTool == null
                ? PlannerToolResult.failure(session.getTaskId(), null, "执行桥接尚未就绪，无法中断当前任务。")
                : executionTool.interruptExecution(session.getTaskId(), "interrupt execution for plan adjustment");
        if (cancelResult == null || !cancelResult.success()) {
            log.warn("Planner autoInterrupt failed to cancel execution task={} reason={}",
                    session.getTaskId(), cancelResult == null ? null : cancelResult.message());
            return updateExecutingAdjustmentReply(
                    session,
                    "当前执行还没成功中断，先不进入重规划。请稍后再试，或在任务工作台手动中断。",
                    PlanningPhaseEnum.EXECUTING
            );
        }

        session = sessionService.get(session.getTaskId());
        session = saveWithoutVersionChangeRetrying(session, current -> {
            current.setPlanningPhase(PlanningPhaseEnum.REPLANNING);
            current.setActiveExecutionAttemptId(null);
            if (current.getIntakeState() != null) {
                current.getIntakeState().setPendingAdjustmentInstruction(null);
            }
            current.setTransitionReason("Replanning after IM execution interrupt: " + replanInstruction);
        });
        sessionService.publishEvent(session.getTaskId(), "REPLANNING");
        if (taskRuntimeService != null) {
            taskRuntimeService.projectPhaseTransition(
                    session.getTaskId(),
                    PlanningPhaseEnum.REPLANNING,
                    com.lark.imcollab.common.model.enums.TaskEventTypeEnum.PLAN_ADJUSTED
            );
        }

        PlanTaskSession replanned = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "interrupt running task and replan from IM"),
                session.getTaskId(),
                replanInstruction,
                workspaceContext,
                replanInstruction
        );
        finalizePlanAdjustmentResult(replanned);
        if (replanned == null) {
            return sessionService.get(session.getTaskId());
        }
        if (replanned.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY) {
            if (replanned.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
                clearAssistantReply(replanned);
            }
            if (replanned.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
                markPendingInteractionType(replanned, PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
                markResumeOriginalExecutionAvailable(replanned, true);
            }
            return replanned;
        }
        markResumeOriginalExecutionAvailable(replanned, false);
        return replanned;
    }

    private PlanTaskSession finalizePlanAdjustmentResult(PlanTaskSession session) {
        if (session == null) {
            return null;
        }
        taskBridgeService.ensureTask(session);
        if (taskRuntimeService != null && session.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY) {
            taskRuntimeService.reconcilePlanReadyProjection(
                    session,
                    com.lark.imcollab.common.model.enums.TaskEventTypeEnum.PLAN_ADJUSTED
            );
        }
        return session;
    }

    private PlanTaskSession rejectExecutingCompletedArtifactAdjustment(PlanTaskSession session) {
        return updateExecutingAdjustmentReply(
                session,
                "当前有任务正在执行，请等当前任务完成后再修改已有产物。",
                PlanningPhaseEnum.EXECUTING,
                AdjustmentTargetEnum.COMPLETED_ARTIFACT
        );
    }

    private boolean shouldRouteCompletedAdjustment(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext
    ) {
        if (intakeDecision == null || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        if (hasText(explicitTaskId) || isForcedNewTask(intakeDecision)) {
            return false;
        }
        Optional<ConversationTaskState> conversationState = sessionResolver.conversationState(workspaceContext);
        if (conversationState.map(ConversationTaskState::getExecutingTaskId).filter(this::hasText).isPresent()) {
            return false;
        }
        if (intakeDecision.adjustmentTarget() == AdjustmentTargetEnum.COMPLETED_ARTIFACT) {
            if (resolution != null && resolution.existingSession() && isCompleted(session)) {
                if (sessionResolver.isTaskCurrentInConversation(session.getTaskId(), workspaceContext)) {
                    return false;
                }
            }
            return !sessionResolver.resolveCompletedCandidates(workspaceContext).isEmpty();
        }
        if (resolution != null && resolution.existingSession() && isCompleted(session)) {
            if (sessionResolver.isTaskCurrentInConversation(session.getTaskId(), workspaceContext)) {
                return false;
            }
            return !sessionResolver.resolveCompletedCandidates(workspaceContext).isEmpty();
        }
        if (resolution == null || !resolution.existingSession()) {
            return !sessionResolver.resolveCompletedCandidates(workspaceContext).isEmpty();
        }
        return false;
    }

    private PlanTaskSession tryRouteCurrentCompletedArtifactAdjustment(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext,
            String instruction
    ) {
        if (hasText(explicitTaskId)
                || intakeDecision == null
                || resolution == null
                || !resolution.existingSession()
                || !isCompleted(session)
                || isForcedNewTask(intakeDecision)) {
            return null;
        }
        if (!sessionResolver.isTaskCurrentInConversation(session.getTaskId(), workspaceContext)) {
            return null;
        }
        if (!shouldDirectCurrentCompletedArtifactAdjustment(session, intakeDecision, instruction)) {
            return null;
        }
        refreshSelectedTaskContext(
                session.getTaskId(),
                workspaceContext,
                resolution,
                instruction,
                TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                "current completed task artifact adjustment from IM",
                null,
                null,
                AdjustmentTargetEnum.COMPLETED_ARTIFACT
        );
        String routedInstruction = routeInstructionToEditableArtifact(session.getTaskId(), instruction);
        PlanTaskSession result = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "current completed task adjusted from conversation"),
                session.getTaskId(),
                routedInstruction,
                workspaceContext,
                null
        );
        taskBridgeService.ensureTask(result);
        return result;
    }

    private boolean shouldDirectCurrentCompletedArtifactAdjustment(
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            String instruction
    ) {
        if (session == null || !hasText(session.getTaskId()) || !sessionResolver.hasEditableArtifacts(session.getTaskId())) {
            return false;
        }
        if (intakeDecision == null) {
            return false;
        }
        if (intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        if (looksLikeAmbiguousMaterialOrganizationRequest(instruction)) {
            return false;
        }
        if (looksLikeNewCompletedDeliverableRequest(instruction)) {
            return false;
        }
        if (intakeDecision.adjustmentTarget() == AdjustmentTargetEnum.COMPLETED_ARTIFACT) {
            return true;
        }
        return sessionResolver.inferEditableArtifact(session.getTaskId(), instruction).isPresent();
    }

    private String routeInstructionToEditableArtifact(String taskId, String instruction) {
        if (!hasText(taskId) || !hasText(instruction) || instruction.contains("目标产物ID：")) {
            return instruction;
        }
        return sessionResolver.inferEditableArtifact(taskId, instruction)
                .map(ArtifactRecord::getArtifactId)
                .filter(this::hasText)
                .map(artifactId -> appendTargetArtifact(instruction, artifactId))
                .orElse(instruction);
    }

    private PlanTaskSession startRecoveredArtifactSelection(
            PlanTaskSession session,
            String instruction,
            WorkspaceContext workspaceContext,
            List<ArtifactRecord> candidates
    ) {
        if (session == null || candidates == null || candidates.isEmpty()) {
            return session;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(buildRecoveredArtifactSelectionQuestion(candidates));
        intakeState.setPendingAdjustmentInstruction(instruction);
        intakeState.setPendingArtifactSelection(PendingArtifactSelection.builder()
                .conversationKey(sessionResolver.conversationKey(workspaceContext))
                .taskId(session.getTaskId())
                .originalInstruction(instruction)
                .candidates(candidates.stream().map(this::toPendingArtifactCandidate).toList())
                .expiresAt(Instant.now().plusSeconds(600))
                .build());
        intakeState.setPendingInteractionType(PendingInteractionTypeEnum.COMPLETED_ARTIFACT_SELECTION);
        intakeState.setLastUserMessage(instruction);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeRetrying(session, current -> { });
        return session;
    }

    private String buildRecoveredArtifactSelectionQuestion(List<ArtifactRecord> candidates) {
        StringBuilder builder = new StringBuilder("这个任务下有多个可修改产物，你想修改哪一个？");
        for (int i = 0; i < candidates.size(); i++) {
            ArtifactRecord artifact = candidates.get(i);
            builder.append("\n").append(i + 1).append(". ");
            if (artifact.getType() != null) {
                builder.append("[").append(artifact.getType().name()).append("] ");
            }
            builder.append(firstText(artifact.getTitle(), artifact.getArtifactId()));
        }
        builder.append("\n回复编号即可。");
        return builder.toString();
    }

    private PendingArtifactCandidate toPendingArtifactCandidate(ArtifactRecord artifact) {
        return PendingArtifactCandidate.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .type(artifact.getType())
                .title(artifact.getTitle())
                .url(artifact.getUrl())
                .status(artifact.getStatus())
                .version(artifact.getVersion())
                .updatedAt(artifact.getUpdatedAt())
                .build();
    }

    private boolean looksLikeNewCompletedDeliverableRequest(String instruction) {
        return routingEvidenceExtractor.looksLikeNewCompletedDeliverableRequest(instruction);
    }

    private boolean looksLikeAmbiguousMaterialOrganizationRequest(String instruction) {
        return routingEvidenceExtractor.looksLikeAmbiguousMaterialOrganizationRequest(instruction);
    }

    private boolean isCompleted(PlanTaskSession session) {
        return session != null && session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED;
    }

    private boolean shouldRouteCompletedTaskList(TaskIntakeDecision intakeDecision, WorkspaceContext workspaceContext) {
        return intakeDecision != null
                && intakeDecision.intakeType() == TaskIntakeTypeEnum.STATUS_QUERY
                && "COMPLETED_TASKS".equalsIgnoreCase(intakeDecision.readOnlyView())
                && !sessionResolver.resolveCompletedCandidates(workspaceContext).isEmpty();
    }

    private Integer parseCandidateIndex(String input) {
        if (!hasText(input)) {
            return null;
        }
        String normalized = FEISHU_AT_TAG.matcher(input).replaceAll(" ");
        normalized = FEISHU_MENTION_TOKEN.matcher(normalized).replaceAll(" ").trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        Matcher matcher = SINGLE_DIGIT_SELECTION.matcher(normalized);
        Integer selection = null;
        while (matcher.find()) {
            if (selection != null) {
                return null;
            }
            selection = Integer.parseInt(matcher.group(1));
        }
        if (selection != null) {
            return selection;
        }
        return switch (normalized) {
            case "一", "第一个" -> 1;
            case "二", "第二个" -> 2;
            case "三", "第三个" -> 3;
            case "四", "第四个" -> 4;
            case "五", "第五个" -> 5;
            default -> null;
        };
    }

    private String buildCandidateReply(List<PendingTaskCandidate> candidates, String selectionPurpose) {
        StringBuilder builder = new StringBuilder(SELECTION_PURPOSE_COMPLETED_TASK_LIST.equals(selectionPurpose)
                ? "我找到这些已完成任务，你想先看哪一个？"
                : "我找到多个已完成任务，你想修改哪一个？");
        for (int i = 0; i < candidates.size(); i++) {
            PendingTaskCandidate candidate = candidates.get(i);
            builder.append("\n").append(i + 1).append(". ")
                    .append(firstText(candidate.getTitle(), candidate.getGoal()));
            if (candidate.getArtifactTypes() != null && !candidate.getArtifactTypes().isEmpty()) {
                builder.append("（")
                        .append(candidate.getArtifactTypes().stream().map(Enum::name).distinct().reduce((a, b) -> a + "、" + b).orElse(""))
                        .append("）");
            }
            if (candidate.getCreatedAt() != null) {
                builder.append(" | 创建于 ").append(formatCompletedTaskTime(candidate.getCreatedAt()));
            }
            if (candidate.getUpdatedAt() != null) {
                builder.append(" | 更新于 ").append(formatCompletedTaskTime(candidate.getUpdatedAt()));
            }
        }
        builder.append("\n回复编号即可。");
        return builder.toString();
    }

    private String selectedCompletedTaskReply(PendingTaskCandidate candidate) {
        StringBuilder builder = new StringBuilder("已切换到这个已完成任务：");
        builder.append(firstText(candidate.getTitle(), candidate.getGoal()));
        if (candidate.getArtifactTypes() != null && !candidate.getArtifactTypes().isEmpty()) {
            builder.append("\n可修改产物类型：")
                    .append(candidate.getArtifactTypes().stream().map(Enum::name).distinct().reduce((a, b) -> a + "、" + b).orElse(""));
        }
        builder.append("\n你可以继续说要修改哪一页、哪一段，或者先查看现有产物。");
        return builder.toString();
    }

    private String formatCompletedTaskTime(Instant instant) {
        return COMPLETED_TASK_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()));
    }

    private boolean shouldShortCircuitWithoutTask(TaskSessionResolution resolution, TaskIntakeDecision intakeDecision) {
        if (resolution == null || resolution.existingSession() || intakeDecision == null) {
            return false;
        }
        TaskIntakeTypeEnum type = intakeDecision.intakeType();
        return type == TaskIntakeTypeEnum.UNKNOWN
                || type == TaskIntakeTypeEnum.STATUS_QUERY
                || type == TaskIntakeTypeEnum.CANCEL_TASK
                || type == TaskIntakeTypeEnum.CONFIRM_ACTION;
    }

    private PlanTaskSession tryHandlePureReadOnlyRequest(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            String graphInstruction
    ) {
        if (!isPureReadOnlyRequest(session, resolution, intakeDecision)) {
            return null;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.STATUS_QUERY);
        intakeState.setContinuedConversation(true);
        intakeState.setContinuationKey(resolution == null ? null : resolution.continuationKey());
        intakeState.setLastUserMessage(graphInstruction);
        intakeState.setRoutingReason(intakeDecision.routingReason());
        intakeState.setAssistantReply(intakeDecision.assistantReply());
        intakeState.setReadOnlyView(intakeDecision.readOnlyView());
        intakeState.setAdjustmentTarget(intakeDecision.adjustmentTarget());
        session.setIntakeState(intakeState);
        if (hasText(intakeState.getPendingAdjustmentInstruction())
                || hasText(intakeState.getPendingDocumentIterationTaskId())) {
            log.info("read_only_attempt_during_completed_artifact_iteration taskId={} planningPhase={} intakeType={} readOnlyView={} conversationKey={}",
                    session.getTaskId(),
                    session.getPlanningPhase(),
                    intakeState.getIntakeType(),
                    intakeState.getReadOnlyView(),
                    resolution == null ? null : resolution.continuationKey());
        }
        log.info("read_only_skipped_session_persist taskId={} planningPhase={} intakeType={} readOnlyView={} conversationKey={}",
                session.getTaskId(),
                session.getPlanningPhase(),
                intakeState.getIntakeType(),
                intakeState.getReadOnlyView(),
                resolution == null ? null : resolution.continuationKey());
        if (readOnlyNodeService == null) {
            return session;
        }
        return readOnlyNodeService.readOnly(
                session,
                graphInstruction,
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.QUERY_STATUS)
                        .confidence(1.0d)
                        .reason(intakeDecision.routingReason())
                        .userFacingReply(intakeDecision.assistantReply())
                        .build()
        );
    }

    private boolean isPureReadOnlyRequest(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            TaskIntakeDecision intakeDecision
    ) {
        if (session == null
                || resolution == null
                || !resolution.existingSession()
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.STATUS_QUERY) {
            return false;
        }
        return !"COMPLETED_TASKS".equalsIgnoreCase(intakeDecision.readOnlyView());
    }

    private boolean shouldRejectPrematureExecutionConfirmation(
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision
    ) {
        if (session == null
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.CONFIRM_ACTION) {
            return false;
        }
        PlanningPhaseEnum phase = session.getPlanningPhase();
        return phase != PlanningPhaseEnum.PLAN_READY
                && phase != PlanningPhaseEnum.EXECUTING;
    }

    private PlanTaskSession tryResumeExecutingAfterInterruptedAdjustmentClarification(
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            String userInput
    ) {
        if (session == null
                || session.getIntakeState() == null
                || (session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER
                && session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING)
                || session.getIntakeState().getPendingInteractionType() != PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT
                || intakeDecision == null
                || intakeDecision.intakeType() == TaskIntakeTypeEnum.STATUS_QUERY
                || intakeDecision.intakeType() == TaskIntakeTypeEnum.CANCEL_TASK
                || !ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            return null;
        }
        saveWithoutVersionChangeRetrying(session, current -> {
            current.setTransitionReason("User resumed original execution after interrupt clarification");
            TaskIntakeState intakeState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            intakeState.setPendingInteractionType(null);
            intakeState.setPendingAdjustmentInstruction(null);
            intakeState.setResumeOriginalExecutionAvailable(false);
            current.setIntakeState(intakeState);
            if (replanScopeService != null) {
                replanScopeService.clear(current);
            }
        });
        if (executionTool == null) {
            PlanTaskSession resumed = saveWithoutVersionChangeRetrying(sessionService.get(session.getTaskId()), current -> {
                current.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
                TaskIntakeState intakeState = current.getIntakeState() == null
                        ? TaskIntakeState.builder().build()
                        : current.getIntakeState();
                intakeState.setIntakeType(TaskIntakeTypeEnum.CONFIRM_ACTION);
                intakeState.setAssistantReply("好的，继续按原执行流程推进。");
                current.setIntakeState(intakeState);
            });
            if (taskRuntimeService != null) {
                taskRuntimeService.projectPhaseTransition(
                        resumed.getTaskId(),
                        PlanningPhaseEnum.EXECUTING,
                        com.lark.imcollab.common.model.enums.TaskEventTypeEnum.USER_INTERVENTION
                );
            }
            sessionService.publishEvent(resumed.getTaskId(), PlanningPhaseEnum.EXECUTING.name());
            memoryService.appendAssistantTurn(resumed, resumed.getIntakeState().getAssistantReply());
            return resumed;
        }
        executionTool.confirmExecution(session.getTaskId());
        PlanTaskSession resumed = sessionService.get(session.getTaskId());
        TaskIntakeState intakeState = resumed.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : resumed.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.CONFIRM_ACTION);
        intakeState.setAssistantReply("好的，继续按原执行流程推进。");
        resumed.setIntakeState(intakeState);
        resumed.setTransitionReason("User resumed original execution after interrupt clarification");
        sessionService.saveWithoutVersionChange(resumed);
        memoryService.appendAssistantTurn(resumed, intakeState.getAssistantReply());
        return resumed;
    }

    private boolean shouldStartFreshTask(String explicitTaskId, TaskSessionResolution resolution, TaskIntakeDecision intakeDecision) {
        return resolution != null
                && !hasText(explicitTaskId)
                && resolution.existingSession()
                && intakeDecision != null
                && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK;
    }

    private boolean isForcedNewTask(TaskIntakeDecision intakeDecision) {
        return intakeService.isForcedNewTaskDecision(intakeDecision);
    }

    private void clearPendingFollowUpRecommendationsIfExplicitNewTask(
            TaskSessionResolution resolution,
            TaskIntakeDecision intakeDecision
    ) {
        if (conversationTaskStateService == null
                || resolution == null
                || !hasText(resolution.continuationKey())
                || intakeDecision == null
                || !isForcedNewTask(intakeDecision)) {
            return;
        }
        conversationTaskStateService.clearPendingFollowUpRecommendations(resolution.continuationKey());
    }

    private CurrentTaskContinuationArbiter.Decision evaluatePendingFollowUpConflict(
            PlanTaskSession currentSession,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            String userInput,
            CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation
    ) {
        if (pendingFollowUpConflictArbiter == null
                || conversationTaskStateService == null
                || resolution == null
                || !hasText(resolution.continuationKey())
                || !hasText(userInput)) {
            return null;
        }
        Optional<ConversationTaskState> stateOptional = conversationTaskStateService.find(resolution.continuationKey());
        if (stateOptional.isEmpty() || stateOptional.get().getPendingFollowUpRecommendations() == null
                || stateOptional.get().getPendingFollowUpRecommendations().isEmpty()) {
            return null;
        }
        CurrentTaskContinuationArbiter.Decision decision = pendingFollowUpConflictArbiter.arbitrateExecution(
                intakeDecision == null ? null : intakeDecision.intakeType(),
                userInput,
                defaultList(stateOptional.get().getPendingFollowUpRecommendations()),
                stateOptional.get().isPendingFollowUpAwaitingSelection(),
                directRouteEvaluation
        );
        RoutingEvidence evidence = routingEvidenceExtractor.extract(userInput);
        log.info(
                "current_task_arbiter_decision taskId={} userInput='{}' upstreamType={} decision={} currentReference={} carryForwardHint={} recommendationCount={} selectedRecommendationId={} topRecommendationId={} topRecommendationScore={} secondRecommendationId={} secondRecommendationScore={} freshTaskScore={} currentTaskReferenceScore={} continuationIntentScore={} artifactEditScore={} newDeliverableScore={} ambiguousMaterialOrganizationScore={} reason={}",
                currentSession == null ? null : currentSession.getTaskId(),
                userInput,
                intakeDecision == null ? null : intakeDecision.intakeType(),
                decision == null ? null : decision.type(),
                decision != null && decision.currentReference(),
                decision == null ? null : decision.hint(),
                stateOptional.get().getPendingFollowUpRecommendations().size(),
                decision == null || decision.selectedRecommendation() == null ? null : decision.selectedRecommendation().getRecommendationId(),
                decision == null ? null : decision.topRecommendationId(),
                decision == null ? 0 : decision.topRecommendationScore(),
                decision == null ? null : decision.secondRecommendationId(),
                decision == null ? 0 : decision.secondRecommendationScore(),
                evidence.freshTaskScore(),
                evidence.currentTaskReferenceScore(),
                evidence.continuationIntentScore(),
                evidence.artifactEditScore(),
                evidence.newDeliverableScore(),
                evidence.ambiguousMaterialOrganizationScore(),
                decision == null ? null : decision.reason()
        );
        return decision;
    }

    private PlanTaskSession tryResumePendingFollowUpRecommendation(
            PlanTaskSession currentSession,
            TaskSessionResolution resolution,
            String userInput,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision
    ) {
        if (conversationTaskStateService == null
                || pendingFollowUpRecommendationMatcher == null
                || resolution == null
                || !hasText(resolution.continuationKey())
                || !hasText(userInput)) {
            return null;
        }
        if (hasPendingSelection(currentSession)) {
            return null;
        }
        if (shouldPreferCurrentTaskExecutionConfirmation(currentSession, userInput)) {
            return null;
        }
        Optional<ConversationTaskState> stateOptional = conversationTaskStateService.find(resolution.continuationKey());
        if (stateOptional.isEmpty() || stateOptional.get().getPendingFollowUpRecommendations() == null
                || stateOptional.get().getPendingFollowUpRecommendations().isEmpty()) {
            return null;
        }
        ConversationTaskState state = stateOptional.get();
        List<PendingFollowUpRecommendation> recommendations = defaultList(state.getPendingFollowUpRecommendations());
        PendingFollowUpRecommendationMatcher.CarryForwardHint carryForwardHint =
                pendingFollowUpRecommendationMatcher == null
                        ? PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED
                        : pendingFollowUpRecommendationMatcher.classifyCarryForwardCandidate(userInput, recommendations);
        if (carryForwardHint == null) {
            carryForwardHint = PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED;
        }
        log.info(
                "pending_followup_execution_hint taskId={} userInput='{}' routingType={} carryForwardHint={} recommendationCount={} upstreamSuggestsStandaloneTask={}",
                currentSession == null ? null : currentSession.getTaskId(),
                userInput,
                intakeDecision == null ? null : intakeDecision.intakeType(),
                carryForwardHint,
                recommendations.size(),
                intakeDecision != null && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK
        );
        if (!shouldAttemptPendingFollowUpRecommendation(
                intakeDecision,
                state.isPendingFollowUpAwaitingSelection(),
                userInput,
                recommendations,
                carryForwardHint
        )) {
            if (intakeDecision != null
                    && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK
                    && carryForwardHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXPLICIT_NEW_TASK) {
                log.info(
                        "pending_followup_explicit_new_task_bypass taskId={} userInput='{}' routingType={} recommendationCount={}",
                        currentSession == null ? null : currentSession.getTaskId(),
                        userInput,
                        intakeDecision.intakeType(),
                        recommendations.size()
                );
            }
            return null;
        }
        if (intakeDecision != null
                && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK
                && carryForwardHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM) {
            log.info(
                    "pending_followup_llm_attempt taskId={} userInput='{}' routingType={} recommendationCount={} upstreamSuggestsStandaloneTask=true",
                    currentSession == null ? null : currentSession.getTaskId(),
                    userInput,
                    intakeDecision.intakeType(),
                    recommendations.size()
            );
        }
        PendingFollowUpRecommendationMatcher.MatchResult match = pendingFollowUpRecommendationMatcher.match(
                userInput,
                recommendations,
                state.isPendingFollowUpAwaitingSelection(),
                intakeDecision != null && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK
        );
        log.info(
                "pending_followup_execution_hint taskId={} userInput='{}' routingType={} carryForwardHint={} recommendationCount={} upstreamSuggestsStandaloneTask={} selectedRecommendationId={}",
                currentSession == null ? null : currentSession.getTaskId(),
                userInput,
                intakeDecision == null ? null : intakeDecision.intakeType(),
                carryForwardHint,
                recommendations.size(),
                intakeDecision != null && intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK,
                match == null || match.recommendation() == null ? null : match.recommendation().getRecommendationId()
        );
        if (match == null) {
            return null;
        }
        if (match.type() == PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION) {
            conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), true);
            return followUpSelectionReply(currentSession, recommendations);
        }
        if (match.type() != PendingFollowUpRecommendationMatcher.Type.SELECTED || match.recommendation() == null) {
            return null;
        }
        PendingFollowUpRecommendation recommendation = match.recommendation();
        if (recommendation.getFollowUpMode() != com.lark.imcollab.common.model.enums.FollowUpModeEnum.CONTINUE_CURRENT_TASK
                && recommendation.getFollowUpMode() != com.lark.imcollab.common.model.enums.FollowUpModeEnum.START_NEW_TASK) {
            return null;
        }
        if (followUpRecommendationExecutionService != null) {
            return followUpRecommendationExecutionService.executePendingRecommendation(
                    recommendation,
                    workspaceContext,
                    userInput,
                    resolution.continuationKey()
            );
        }
        if (!hasText(recommendation.getTargetTaskId())) {
            return null;
        }
        PlanTaskSession targetSession = sessionService.get(recommendation.getTargetTaskId());
        WorkspaceContext followUpContext = appendFollowUpSourceArtifact(
                workspaceContext,
                recommendation,
                targetSession
        );
        String plannerInstruction = appendFollowUpPlannerHints(recommendation, userInput);
        conversationTaskStateService.clearPendingFollowUpRecommendations(resolution.continuationKey());
        PlanTaskSession result = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "resume pending follow-up recommendation"),
                recommendation.getTargetTaskId(),
                plannerInstruction,
                followUpContext,
                userInput
        );
        normalizeFollowUpContinuationResult(result, resolution, userInput);
        markPreserveExistingArtifactsOnExecution(result);
        return result;
    }

    private boolean shouldAttemptPendingFollowUpRecommendation(
            TaskIntakeDecision intakeDecision,
            boolean awaitingSelection,
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            PendingFollowUpRecommendationMatcher.CarryForwardHint carryForwardHint
    ) {
        if (intakeDecision == null) {
            return awaitingSelection;
        }
        if (isForcedNewTask(intakeDecision)) {
            return false;
        }
        if (awaitingSelection) {
            return intakeDecision.intakeType() != TaskIntakeTypeEnum.CANCEL_TASK
                    && intakeDecision.intakeType() != TaskIntakeTypeEnum.STATUS_QUERY;
        }
        if (intakeDecision.intakeType() == TaskIntakeTypeEnum.NEW_TASK) {
            return carryForwardHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXACT_OR_PREFIX_MATCH
                    || carryForwardHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM
                    || carryForwardHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.RELATED_BUT_AMBIGUOUS;
        }
        return intakeDecision.intakeType() == TaskIntakeTypeEnum.PLAN_ADJUSTMENT
                || intakeDecision.intakeType() == TaskIntakeTypeEnum.CONFIRM_ACTION;
    }

    private PlanTaskSession followUpSelectionReply(
            PlanTaskSession session,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        PlanTaskSession response = session == null ? PlanTaskSession.builder().taskId(UUID.randomUUID().toString()).build() : session;
        TaskIntakeState intakeState = response.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : response.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(buildFollowUpSelectionReply(recommendations));
        response.setIntakeState(intakeState);
        response.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        return response;
    }

    private String buildFollowUpSelectionReply(List<PendingFollowUpRecommendation> recommendations) {
        StringBuilder builder = new StringBuilder("我这边有多个后续动作，你想继续哪一个？");
        for (int index = 0; index < recommendations.size(); index++) {
            PendingFollowUpRecommendation recommendation = recommendations.get(index);
            builder.append("\n").append(index + 1).append(". ")
                    .append(firstText(recommendation.getSuggestedUserInstruction(), recommendation.getPlannerInstruction()));
        }
        builder.append("\n回复编号即可。");
        return builder.toString();
    }

    private boolean shouldPreferCurrentTaskExecutionConfirmation(PlanTaskSession session, String userInput) {
        if (session == null || !ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            return false;
        }
        return session.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY
                || session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING;
    }

    private boolean hasPendingSelection(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && (session.getIntakeState().getPendingTaskSelection() != null
                || session.getIntakeState().getPendingArtifactSelection() != null
                || session.getIntakeState().getPendingFollowUpConflictChoice() != null
                || session.getIntakeState().getPendingCurrentTaskContinuationChoice() != null);
    }

    private boolean shouldReplayCompletedTaskList(
            PendingTaskSelection selection,
            TaskIntakeDecision intakeDecision
    ) {
        return selection != null
                && intakeDecision != null
                && intakeDecision.intakeType() == TaskIntakeTypeEnum.STATUS_QUERY
                && "COMPLETED_TASKS".equalsIgnoreCase(intakeDecision.readOnlyView());
    }

    private WorkspaceContext appendFollowUpSourceArtifact(
            WorkspaceContext workspaceContext,
            PendingFollowUpRecommendation recommendation,
            PlanTaskSession targetSession
    ) {
        if (!hasText(recommendation.getTargetTaskId())) {
            return workspaceContext;
        }
        ArtifactRecord artifact = resolveFollowUpSourceArtifact(recommendation);
        if (artifact == null) {
            return workspaceContext;
        }
        WorkspaceContext merged = copyWorkspaceContextWithSourceArtifact(workspaceContext, artifact);
        if (targetSession != null
                && targetSession.getIntakeState() != null
                && !hasText(merged.getContinuationMode())
                && hasText(targetSession.getIntakeState().getContinuationKey())) {
            merged.setContinuationMode(targetSession.getIntakeState().getContinuationKey());
        }
        return merged;
    }

    private ArtifactRecord resolveFollowUpSourceArtifact(PendingFollowUpRecommendation recommendation) {
        if (recommendation == null || !hasText(recommendation.getTargetTaskId())) {
            return null;
        }
        if (hasText(recommendation.getSourceArtifactId())) {
            Optional<ArtifactRecord> exact = sessionResolver.findArtifactById(
                    recommendation.getTargetTaskId(),
                    recommendation.getSourceArtifactId()
            );
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return sessionResolver.findLatestShareableArtifact(
                recommendation.getTargetTaskId(),
                recommendation.getSourceArtifactType()
        ).orElse(null);
    }

    private String appendFollowUpPlannerHints(PendingFollowUpRecommendation recommendation, String userInput) {
        StringBuilder builder = new StringBuilder(firstText(
                recommendation == null ? null : recommendation.getPlannerInstruction(),
                userInput
        ));
        if (recommendation != null && hasText(recommendation.getArtifactPolicy())) {
            builder.append("\n产物策略：").append(recommendation.getArtifactPolicy().trim());
        }
        if (recommendation != null && hasText(recommendation.getSourceArtifactId())) {
            builder.append("\n来源产物ID：").append(recommendation.getSourceArtifactId().trim());
        }
        if (hasText(userInput)
                && !compact(userInput).equals(compact(recommendation == null ? null : recommendation.getSuggestedUserInstruction()))
                && !ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            builder.append("\n用户补充：").append(userInput.trim());
        }
        return builder.toString();
    }

    private void markPreserveExistingArtifactsOnExecution(PlanTaskSession session) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setPreserveExistingArtifactsOnExecution(true);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setPreserveExistingArtifactsOnExecution(true);
            current.setIntakeState(currentState);
        }, "mark_preserve_existing_artifacts");
    }

    private void normalizeFollowUpContinuationResult(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String userInput
    ) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setContinuedConversation(resolution != null && resolution.existingSession());
        intakeState.setContinuationKey(resolution == null ? null : resolution.continuationKey());
        intakeState.setLastUserMessage(userInput);
        intakeState.setRoutingReason("resume pending follow-up recommendation");
        intakeState.setAssistantReply(null);
        intakeState.setReadOnlyView(null);
        intakeState.setAdjustmentTarget(AdjustmentTargetEnum.READY_PLAN);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
            currentState.setContinuedConversation(resolution != null && resolution.existingSession());
            currentState.setContinuationKey(resolution == null ? null : resolution.continuationKey());
            currentState.setLastUserMessage(userInput);
            currentState.setRoutingReason("resume pending follow-up recommendation");
            currentState.setAssistantReply(null);
            currentState.setReadOnlyView(null);
            currentState.setAdjustmentTarget(AdjustmentTargetEnum.READY_PLAN);
            current.setIntakeState(currentState);
        }, "normalize_followup_continuation_result");
    }

    private WorkspaceContext carryForwardCompletedArtifactContext(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            String userInput
    ) {
        if (session == null
                || intakeDecision == null
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.NEW_TASK
                || session.getPlanningPhase() != PlanningPhaseEnum.COMPLETED
                || hasExplicitSourceContext(workspaceContext)
                || !hasText(userInput)
                || followUpArtifactContextResolver == null) {
            return workspaceContext;
        }
        ArtifactTypeEnum preferredType = followUpArtifactContextResolver
                .resolvePreferredArtifactType(session, userInput, workspaceContext)
                .orElse(null);
        if (preferredType == null) {
            return workspaceContext;
        }
        Optional<ArtifactRecord> latest = sessionResolver.findLatestShareableArtifact(session.getTaskId(), preferredType);
        if (latest.isEmpty()) {
            return workspaceContext;
        }
        return copyWorkspaceContextWithSourceArtifact(workspaceContext, latest.get());
    }

    private boolean hasExplicitSourceContext(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return false;
        }
        return (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty())
                || (workspaceContext.getSourceArtifacts() != null && !workspaceContext.getSourceArtifacts().isEmpty())
                || (workspaceContext.getAttachmentRefs() != null && !workspaceContext.getAttachmentRefs().isEmpty())
                || (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty())
                || (workspaceContext.getSelectedMessageIds() != null && !workspaceContext.getSelectedMessageIds().isEmpty());
    }

    private WorkspaceContext copyWorkspaceContextWithSourceArtifact(WorkspaceContext original, ArtifactRecord artifact) {
        String artifactUrl = hasText(artifact.getUrl()) ? artifact.getUrl().trim() : null;
        List<String> docRefs = artifact.getType() == ArtifactTypeEnum.DOC && hasText(artifactUrl)
                ? List.of(artifactUrl)
                : List.of();
        List<SourceArtifactRef> sourceArtifacts = List.of(SourceArtifactRef.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .artifactType(artifact.getType())
                .title(artifact.getTitle())
                .url(artifactUrl)
                .preview(artifact.getPreview())
                .usage("PRIMARY_SOURCE")
                .build());
        if (original == null) {
            return WorkspaceContext.builder()
                    .selectionType("ARTIFACT")
                    .docRefs(docRefs)
                    .sourceArtifacts(sourceArtifacts)
                    .build();
        }
        return WorkspaceContext.builder()
                .selectionType(hasText(original.getSelectionType()) ? original.getSelectionType() : "ARTIFACT")
                .timeRange(original.getTimeRange())
                .selectedMessages(original.getSelectedMessages())
                .selectedMessageIds(original.getSelectedMessageIds())
                .attachmentRefs(original.getAttachmentRefs())
                .docRefs(docRefs)
                .sourceArtifacts(sourceArtifacts)
                .chatId(original.getChatId())
                .threadId(original.getThreadId())
                .messageId(original.getMessageId())
                .senderOpenId(original.getSenderOpenId())
                .chatType(original.getChatType())
                .inputSource(original.getInputSource())
                .continuationMode(original.getContinuationMode())
                .profession(original.getProfession())
                .industry(original.getIndustry())
                .audience(original.getAudience())
                .tone(original.getTone())
                .language(original.getLanguage())
                .promptProfile(original.getPromptProfile())
                .promptVersion(original.getPromptVersion())
                .build();
    }

    private boolean shouldBindConversation(TaskSessionResolution resolution, TaskIntakeDecision intakeDecision) {
        if (resolution == null || intakeDecision == null) {
            return false;
        }
        if (resolution.existingSession()) {
            return true;
        }
        return switch (intakeDecision.intakeType()) {
            case STATUS_QUERY, UNKNOWN, CANCEL_TASK, CONFIRM_ACTION -> false;
            default -> true;
        };
    }

    private PlanTaskSession transientSession(String taskId, WorkspaceContext workspaceContext) {
        return PlanTaskSession.builder()
                .taskId(taskId)
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .planScore(0)
                .aborted(false)
                .turnCount(0)
                .scenarioPath(List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING))
                .build();
    }

    private TaskIntakeDecision absorbDocLinksDuringClarification(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision current,
            String userFeedback,
            String rawInstruction
    ) {
        if (session == null
                || resolution == null
                || !resolution.existingSession()
                || session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER
                || workspaceContext == null
                || workspaceContext.getDocRefs() == null
                || workspaceContext.getDocRefs().isEmpty()) {
            return current;
        }
        String effectiveInput = firstText(userFeedback, rawInstruction);
        return new TaskIntakeDecision(
                TaskIntakeTypeEnum.CLARIFICATION_REPLY,
                effectiveInput,
                "guard clarification reply from extracted doc refs",
                null,
                null
        );
    }

    private TaskIntakeDecision absorbSourceContextSupplementForReadyPlan(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision current,
            String userFeedback,
            String rawInstruction
    ) {
        if (session == null
                || resolution == null
                || !resolution.existingSession()
                || session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY
                || current == null
                || (current.intakeType() != TaskIntakeTypeEnum.NEW_TASK
                && current.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT)
                || hasExplicitSourceContext(workspaceContext)) {
            return current;
        }
        String effectiveInput = firstText(userFeedback, rawInstruction);
        if (!looksLikeCurrentTaskSourceContextSupplement(effectiveInput)) {
            return current;
        }
        return new TaskIntakeDecision(
                TaskIntakeTypeEnum.CLARIFICATION_REPLY,
                effectiveInput,
                "guard source context supplement for ready plan",
                null,
                null
        );
    }

    private boolean looksLikeCurrentTaskSourceContextSupplement(String input) {
        if (!hasText(input)) {
            return false;
        }
        String normalized = compact(input);
        boolean mentionsSourceEntity = containsAny(normalized,
                "消息", "聊天记录", "群聊记录", "文档材料", "内容来源", "来源", "材料");
        boolean mentionsSupplementPattern = containsAny(normalized,
                "作为文档内容来源", "作为内容来源", "作为来源", "改成基于", "不要直接编造", "用刚才聊天记录",
                "用聊天记录", "做材料", "取前10分钟", "取最近消息", "拉取前10分钟", "拉取最近");
        if (!mentionsSourceEntity || !mentionsSupplementPattern) {
            return false;
        }
        return !containsAny(normalized,
                "新建一个任务", "新开一个任务", "另起一个任务", "重新开始一个新任务",
                "新增一页", "加一页", "补一页", "加一小节", "补一小节",
                "删一步", "删除步骤", "重排步骤", "调整计划顺序");
    }

    private boolean containsAny(String value, String... needles) {
        if (!hasText(value) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (hasText(needle) && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private TaskIntakeDecision absorbExecutingPlanAdjustmentClarification(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            TaskIntakeDecision current,
            String userFeedback,
            String rawInstruction
    ) {
        if (session == null
                || resolution == null
                || !resolution.existingSession()
                || session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER
                || session.getIntakeState() == null
                || session.getIntakeState().getPendingInteractionType() != PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT
                || current == null) {
            return null;
        }
        if (current.intakeType() == TaskIntakeTypeEnum.STATUS_QUERY
                || current.intakeType() == TaskIntakeTypeEnum.CANCEL_TASK
                || current.intakeType() == TaskIntakeTypeEnum.CONFIRM_ACTION) {
            return null;
        }
        String effectiveInput = firstText(userFeedback, rawInstruction);
        if (!hasText(effectiveInput)) {
            return null;
        }
        return new TaskIntakeDecision(
                TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                effectiveInput,
                "guard executing plan adjustment clarification reply",
                null,
                null
        );
    }

    private void updateSessionEnvelope(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            TaskSessionResolution resolution,
            String userInput
    ) {
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        if (!resolution.existingSession() && session.getRawInstruction() == null) {
            session.setRawInstruction(firstText(userInput, intakeDecision.effectiveInput()));
        }
        session.setInputContext(TaskInputContext.builder()
                .inputSource(workspaceContext == null ? null : workspaceContext.getInputSource())
                .chatId(workspaceContext == null ? null : workspaceContext.getChatId())
                .threadId(workspaceContext == null ? null : workspaceContext.getThreadId())
                .messageId(workspaceContext == null ? null : workspaceContext.getMessageId())
                .senderOpenId(workspaceContext == null ? null : workspaceContext.getSenderOpenId())
                .chatType(workspaceContext == null ? null : workspaceContext.getChatType())
                .build());
        intakeState.setIntakeType(intakeDecision.intakeType());
        intakeState.setContinuedConversation(resolution.existingSession());
        intakeState.setContinuationKey(resolution.continuationKey());
        intakeState.setLastUserMessage(firstText(userInput, intakeDecision.effectiveInput()));
        intakeState.setRoutingReason(intakeDecision.routingReason());
        intakeState.setAssistantReply(intakeDecision.assistantReply());
        intakeState.setReadOnlyView(intakeDecision.readOnlyView());
        intakeState.setAdjustmentTarget(intakeDecision.adjustmentTarget());
        intakeState.setLastInputAt(workspaceContext == null ? null : workspaceContext.getTimeRange());
        session.setIntakeState(intakeState);
        if (session.getScenarioPath() == null || session.getScenarioPath().isEmpty()) {
            session.setScenarioPath(List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING));
        }
        if (session.getPlanningPhase() == null || !resolution.existingSession()) {
            session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        }
        session.setTransitionReason("Scenario A intake accepted");
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String compact(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("！", "")
                .replace("!", "");
    }

    private PlanTaskSession updateExecutingAdjustmentReply(
            PlanTaskSession session,
            String reply,
            PlanningPhaseEnum fallbackPhase
    ) {
        return updateExecutingAdjustmentReply(session, reply, fallbackPhase, null);
    }

    private PlanTaskSession updateExecutingAdjustmentReply(
            PlanTaskSession session,
            String reply,
            PlanningPhaseEnum fallbackPhase,
            AdjustmentTargetEnum adjustmentTarget
    ) {
        if (session == null) {
            return null;
        }
        return saveWithoutVersionChangeRetrying(session, current -> {
            TaskIntakeState intakeState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
            intakeState.setAssistantReply(reply);
            if (adjustmentTarget != null) {
                intakeState.setAdjustmentTarget(adjustmentTarget);
            }
            current.setIntakeState(intakeState);
            if (fallbackPhase != null) {
                current.setPlanningPhase(fallbackPhase);
            }
        });
    }

    private PlanTaskSession saveWithoutVersionChangeRetrying(
            PlanTaskSession session,
            Consumer<PlanTaskSession> mutator
    ) {
        if (session == null) {
            return null;
        }
        Consumer<PlanTaskSession> safeMutator = mutator == null ? current -> { } : mutator;
        PlanTaskSession current = session;
        safeMutator.accept(current);
        try {
            sessionService.saveWithoutVersionChange(current);
            return current;
        } catch (VersionConflictException conflict) {
            PlanTaskSession latest = sessionService.get(current.getTaskId());
            safeMutator.accept(latest);
            sessionService.saveWithoutVersionChange(latest);
            return latest;
        }
    }

    private PlanTaskSession saveWithoutVersionChangeBestEffort(
            PlanTaskSession session,
            Consumer<PlanTaskSession> mutator,
            String mutationKind
    ) {
        if (session == null) {
            return null;
        }
        Consumer<PlanTaskSession> safeMutator = mutator == null ? current -> { } : mutator;
        PlanTaskSession current = session;
        safeMutator.accept(current);
        try {
            sessionService.saveWithoutVersionChange(current);
            return current;
        } catch (VersionConflictException conflict) {
            PlanTaskSession latest = sessionService.get(current.getTaskId());
            safeMutator.accept(latest);
            try {
                sessionService.saveWithoutVersionChange(latest);
                return latest;
            } catch (VersionConflictException retryConflict) {
                log.warn(
                        "aux_session_write_conflict_best_effort taskId={} planningPhase={} intakeType={} stateRevision_expected={} stateRevision_actual={} mutationKind={}",
                        latest.getTaskId(),
                        latest.getPlanningPhase(),
                        latest.getIntakeState() == null ? null : latest.getIntakeState().getIntakeType(),
                        current.getStateRevision(),
                        latest.getStateRevision(),
                        mutationKind,
                        retryConflict
                );
                return latest;
            }
        }
    }

    private void markPendingInteractionType(
            PlanTaskSession session,
            PendingInteractionTypeEnum pendingInteractionType
    ) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setPendingInteractionType(pendingInteractionType);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setPendingInteractionType(pendingInteractionType);
            current.setIntakeState(currentState);
        }, "mark_pending_interaction_type");
    }

    private void markResumeOriginalExecutionAvailable(
            PlanTaskSession session,
            boolean resumeOriginalExecutionAvailable
    ) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setResumeOriginalExecutionAvailable(resumeOriginalExecutionAvailable);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setResumeOriginalExecutionAvailable(resumeOriginalExecutionAvailable);
            current.setIntakeState(currentState);
        }, resumeOriginalExecutionAvailable
                ? "mark_resume_original_execution_available"
                : "clear_resume_original_execution_available");
    }

    private void markAwaitingExecutionConfirmationIfNeeded(PlanTaskSession session) {
        if (session == null || session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        if (intakeState.getPendingInteractionType() == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT
                || intakeState.getPendingInteractionType() == PendingInteractionTypeEnum.COMPLETED_TASK_SELECTION
                || intakeState.getPendingInteractionType() == PendingInteractionTypeEnum.COMPLETED_ARTIFACT_SELECTION) {
            return;
        }
        intakeState.setPendingInteractionType(PendingInteractionTypeEnum.AWAITING_EXECUTION_CONFIRMATION);
        session.setIntakeState(intakeState);
        saveWithoutVersionChangeBestEffort(session, current -> {
            TaskIntakeState currentState = current.getIntakeState() == null
                    ? TaskIntakeState.builder().build()
                    : current.getIntakeState();
            currentState.setPendingInteractionType(PendingInteractionTypeEnum.AWAITING_EXECUTION_CONFIRMATION);
            current.setIntakeState(currentState);
        }, "mark_awaiting_execution_confirmation");
    }

    private void clearAssistantReply(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setAssistantReply(null);
        saveWithoutVersionChangeBestEffort(session, current -> {
            if (current.getIntakeState() != null) {
                current.getIntakeState().setAssistantReply(null);
            }
        }, "clear_assistant_reply");
    }

    private boolean isImConversation(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || workspaceContext.getInputSource() == null) {
            return false;
        }
        return workspaceContext.getInputSource().toUpperCase(Locale.ROOT).contains("LARK");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String stripLeadingMentionPlaceholders(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.trim();
        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceFirst("^@[_a-zA-Z0-9\\-]+\\s+", "").trim();
            normalized = normalized.replaceFirst("^<at\\b[^>]*>[^<]*</at>\\s*", "").trim();
        } while (!normalized.equals(previous));
        return normalized;
    }

}
