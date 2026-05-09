package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PendingTaskSelection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
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
        this(sessionResolver, intakeService, sessionService, taskBridgeService, memoryService, graphRunner, null, null);
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
            TaskRuntimeService taskRuntimeService
    ) {
        this.sessionResolver = sessionResolver;
        this.intakeService = intakeService;
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.memoryService = memoryService;
        this.graphRunner = graphRunner;
        this.executionTool = executionTool;
        this.taskRuntimeService = taskRuntimeService;
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

        TaskIntakeDecision intakeDecision = preliminaryIntakeDecision;
        intakeDecision = absorbDocLinksDuringClarification(session, resolution, workspaceContext, intakeDecision, userFeedback, rawInstruction);
        if (shouldStartFreshTask(taskId, resolution, intakeDecision)) {
            resolution = new TaskSessionResolution(UUID.randomUUID().toString(), false, resolution.continuationKey());
            session = transientSession(resolution.taskId(), workspaceContext);
        }
        String graphInstruction = stripLeadingMentionPlaceholders(userInput);
        if (graphInstruction.isBlank()) {
            graphInstruction = intakeDecision.effectiveInput();
        }
        if (shouldAutoInterruptExecutingTaskForReplan(taskId, resolution, session, intakeDecision, workspaceContext)) {
            return autoInterruptExecutingTaskForReplan(session, resolution, workspaceContext, intakeDecision, graphInstruction);
        }
        if (shouldRejectExecutingCompletedArtifactAdjustment(taskId, resolution, session, intakeDecision, workspaceContext)) {
            return rejectExecutingCompletedArtifactAdjustment(session);
        }
        if (shouldClarifyExecutingAdjustmentTarget(taskId, resolution, session, intakeDecision, workspaceContext)) {
            return clarifyExecutingAdjustmentTarget(session);
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
                    "COMPLETED_TASKS"
            );
            return sessionService.get(candidate.getTaskId());
        }
        refreshSelectedTaskContext(candidate.getTaskId(), workspaceContext, resolution, selection.getOriginalInstruction());
        PlanTaskSession result = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "completed task selected for adjustment"),
                candidate.getTaskId(),
                selection.getOriginalInstruction(),
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
            PlanTaskSession result = graphRunner.run(
                    new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "single completed task adjusted from conversation"),
                    candidate.getTaskId(),
                    instruction,
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
            String readOnlyView
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
                        readOnlyView),
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
        return adjustmentTarget == null
                || adjustmentTarget == AdjustmentTargetEnum.RUNNING_PLAN
                || adjustmentTarget == AdjustmentTargetEnum.UNKNOWN;
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
        return intakeDecision.adjustmentTarget() == AdjustmentTargetEnum.COMPLETED_ARTIFACT;
    }

    private boolean shouldClarifyExecutingAdjustmentTarget(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision,
            WorkspaceContext workspaceContext
    ) {
        return false;
    }

    private PlanTaskSession clarifyExecutingAdjustmentTarget(PlanTaskSession session) {
        return updateExecutingAdjustmentReply(
                session,
                "你是要中断当前执行并重规划，还是修改已经生成的文档或 PPT？",
                PlanningPhaseEnum.EXECUTING,
                AdjustmentTargetEnum.UNKNOWN
        );
    }

    private PlanTaskSession autoInterruptExecutingTaskForReplan(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            String graphInstruction
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
            return updateExecutingAdjustmentReply(
                    session,
                    "当前执行还没成功中断，先不进入重规划。"
                            + firstText(cancelResult == null ? null : cancelResult.message(), "请稍后再试一次。"),
                    PlanningPhaseEnum.EXECUTING
            );
        }

        session = sessionService.get(session.getTaskId());
        session = saveWithoutVersionChangeRetrying(session, current -> {
            current.setPlanningPhase(PlanningPhaseEnum.REPLANNING);
            current.setActiveExecutionAttemptId(null);
            current.setTransitionReason("Replanning after IM execution interrupt: " + graphInstruction);
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
                graphInstruction,
                workspaceContext,
                graphInstruction
        );
        taskBridgeService.ensureTask(replanned);
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
