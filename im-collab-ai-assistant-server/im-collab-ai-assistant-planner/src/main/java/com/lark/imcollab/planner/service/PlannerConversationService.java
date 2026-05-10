package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
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
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.planner.supervisor.PlannerExecutionTool;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.planner.supervisor.PlannerToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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
    private final FollowUpArtifactContextResolver followUpArtifactContextResolver;
    private final ConversationTaskStateService conversationTaskStateService;
    private final PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher;
    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");
    private static final Pattern SINGLE_DIGIT_SELECTION = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");
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
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner, null, null, null, null, null);
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
            FollowUpArtifactContextResolver followUpArtifactContextResolver,
            ConversationTaskStateService conversationTaskStateService,
            PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher
    ) {
        this.sessionResolver = sessionResolver;
        this.intakeService = intakeService;
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.memoryService = memoryService;
        this.graphRunner = graphRunner;
        this.executionTool = executionTool;
        this.taskRuntimeService = taskRuntimeService;
        this.followUpArtifactContextResolver = followUpArtifactContextResolver;
        this.conversationTaskStateService = conversationTaskStateService;
        this.pendingFollowUpRecommendationMatcher = pendingFollowUpRecommendationMatcher;
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
                executionTool, taskRuntimeService, null, null, null);
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
        PlanTaskSession followUpResult = tryResumePendingFollowUpRecommendation(
                session,
                resolution,
                firstText(userFeedback, rawInstruction),
                workspaceContext
        );
        if (followUpResult != null) {
            return finalizePlanAdjustmentResult(followUpResult);
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
                bypassPendingSelections
        );
        if (selectionResult != null) {
            return selectionResult;
        }
        String userInput = firstText(userFeedback, rawInstruction);
        String graphInstruction = stripLeadingMentionPlaceholders(userInput);

        TaskIntakeDecision intakeDecision = preliminaryIntakeDecision;
        intakeDecision = absorbDocLinksDuringClarification(session, resolution, workspaceContext, intakeDecision, userFeedback, rawInstruction);
        clearPendingFollowUpRecommendationsIfExplicitNewTask(resolution, intakeDecision);
        if (shouldStartFreshTask(taskId, resolution, intakeDecision)) {
            workspaceContext = carryForwardCompletedArtifactContext(session, workspaceContext, intakeDecision, graphInstruction);
            resolution = new TaskSessionResolution(UUID.randomUUID().toString(), false, resolution.continuationKey());
            session = transientSession(resolution.taskId(), workspaceContext);
        }
        if (graphInstruction.isBlank()) {
            graphInstruction = intakeDecision.effectiveInput();
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
                sessionService.saveWithoutVersionChange(rejected);
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
        if (resumedExecutingPlanAdjustmentClarification
                && result != null
                && result.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
            markPendingInteractionType(result, PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
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
        sessionService.saveWithoutVersionChange(session);
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
        sessionService.saveWithoutVersionChange(session);
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
            boolean bypassPendingSelection
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
                sessionService.saveWithoutVersionChange(replySession);
            }
            return replySession;
        }
        PendingTaskCandidate candidate = candidates.get(index - 1);
        session.getIntakeState().setPendingTaskSelection(null);
        session.getIntakeState().setPendingInteractionType(null);
        sessionService.saveWithoutVersionChange(session);
        sessionResolver.bindConversation(new TaskSessionResolution(candidate.getTaskId(), true, resolution.continuationKey()));
        if (SELECTION_PURPOSE_COMPLETED_TASK_LIST.equals(selection.getSelectionPurpose())) {
            refreshSelectedTaskContext(
                    candidate.getTaskId(),
                    workspaceContext,
                    resolution,
                    selection.getOriginalInstruction(),
                    TaskIntakeTypeEnum.STATUS_QUERY,
                    "completed task selected from list",
                    selectedCompletedTaskReply(candidate),
                    "COMPLETED_TASKS",
                    null
            );
            return sessionService.get(candidate.getTaskId());
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
        sessionService.saveWithoutVersionChange(selected);
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
        sessionService.saveWithoutVersionChange(selectionSession);
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
        sessionService.saveWithoutVersionChange(session);
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
        session = saveWithoutVersionChangeRetrying(session, current -> {
            updateSessionEnvelope(current, workspaceContext, intakeDecision, resolution, graphInstruction);
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
        // 优先用调用前取出的原始指令（updateSessionEnvelope 已清除 intakeState 中的该字段）
        String replanInstruction = hasText(pendingAdjustmentInstruction) ? pendingAdjustmentInstruction : graphInstruction;
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
            }
            return replanned;
        }
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
                boolean currentCompletedTaskIsActive = conversationState
                        .map(ConversationTaskState::getActiveTaskId)
                        .filter(this::hasText)
                        .map(activeTaskId -> activeTaskId.equals(session.getTaskId()))
                        .orElse(false);
                if (currentCompletedTaskIsActive) {
                    return false;
                }
            }
            return !sessionResolver.resolveCompletedCandidates(workspaceContext).isEmpty();
        }
        if (resolution != null
                && resolution.existingSession()
                && isCompleted(session)
                && conversationState
                .map(ConversationTaskState::getActiveTaskId)
                .filter(this::hasText)
                .map(activeTaskId -> activeTaskId.equals(session.getTaskId()))
                .orElse(false)) {
            return false;
        }
        if (resolution == null || !resolution.existingSession() || isCompleted(session)) {
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
                || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT
                || resolution == null
                || !resolution.existingSession()
                || !isCompleted(session)
                || isForcedNewTask(intakeDecision)) {
            return null;
        }
        Optional<ConversationTaskState> conversationState = sessionResolver.conversationState(workspaceContext);
        boolean currentCompletedTaskIsActive = conversationState
                .map(ConversationTaskState::getActiveTaskId)
                .filter(this::hasText)
                .map(activeTaskId -> activeTaskId.equals(session.getTaskId()))
                .orElse(false);
        if (!currentCompletedTaskIsActive) {
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
        if (intakeDecision.adjustmentTarget() == AdjustmentTargetEnum.COMPLETED_ARTIFACT) {
            return true;
        }
        if (looksLikeNewCompletedDeliverableRequest(instruction)) {
            return false;
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

    private boolean looksLikeNewCompletedDeliverableRequest(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        return (lower.contains("生成") || lower.contains("整理") || lower.contains("做一版") || lower.contains("输出"))
                && (lower.contains("ppt") || lower.contains("演示稿") || lower.contains("幻灯片")
                || lower.contains("摘要") || lower.contains("总结") || lower.contains("文档") || lower.contains("报告"));
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
            if (hasText(candidate.getTaskId())) {
                builder.append(" ").append(shortTaskId(candidate.getTaskId()));
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

    private String shortTaskId(String taskId) {
        return taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
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

    private PlanTaskSession tryResumePendingFollowUpRecommendation(
            PlanTaskSession currentSession,
            TaskSessionResolution resolution,
            String userInput,
            WorkspaceContext workspaceContext
    ) {
        if (conversationTaskStateService == null
                || pendingFollowUpRecommendationMatcher == null
                || resolution == null
                || !hasText(resolution.continuationKey())
                || !hasText(userInput)) {
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
        PendingFollowUpRecommendationMatcher.MatchResult match = pendingFollowUpRecommendationMatcher.match(
                userInput,
                recommendations,
                state.isPendingFollowUpAwaitingSelection()
        );
        if (match.type() == PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION) {
            conversationTaskStateService.markPendingFollowUpAwaitingSelection(resolution.continuationKey(), true);
            return followUpSelectionReply(currentSession, recommendations);
        }
        if (match.type() != PendingFollowUpRecommendationMatcher.Type.SELECTED || match.recommendation() == null) {
            return null;
        }
        PendingFollowUpRecommendation recommendation = match.recommendation();
        if (recommendation.getFollowUpMode() != com.lark.imcollab.common.model.enums.FollowUpModeEnum.CONTINUE_CURRENT_TASK
                || !hasText(recommendation.getTargetTaskId())) {
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
        sessionService.saveWithoutVersionChange(session);
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
        sessionService.saveWithoutVersionChange(session);
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
                TaskIntakeTypeEnum.CLARIFICATION_REPLY,
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
        TaskIntakeState previousIntakeState = session.getIntakeState();
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
        session.setIntakeState(TaskIntakeState.builder()
                .intakeType(intakeDecision.intakeType())
                .continuedConversation(resolution.existingSession())
                .continuationKey(resolution.continuationKey())
                .lastUserMessage(firstText(userInput, intakeDecision.effectiveInput()))
                .routingReason(intakeDecision.routingReason())
                .assistantReply(intakeDecision.assistantReply())
                .readOnlyView(intakeDecision.readOnlyView())
                .adjustmentTarget(intakeDecision.adjustmentTarget())
                .lastInputAt(workspaceContext == null ? null : workspaceContext.getTimeRange())
                .preserveExistingArtifactsOnExecution(previousIntakeState != null
                        && previousIntakeState.isPreserveExistingArtifactsOnExecution())
                .build());
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
        sessionService.saveWithoutVersionChange(session);
    }

    private void clearAssistantReply(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setAssistantReply(null);
        sessionService.saveWithoutVersionChange(session);
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
