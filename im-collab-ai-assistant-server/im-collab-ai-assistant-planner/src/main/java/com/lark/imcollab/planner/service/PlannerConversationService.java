package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PendingTaskSelection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlannerConversationService {

    private final TaskSessionResolver sessionResolver;
    private final TaskIntakeService intakeService;
    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerSupervisorGraphRunner graphRunner;
    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");
    private static final Pattern SINGLE_DIGIT_SELECTION = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");

    @Autowired
    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner
    ) {
        this.sessionResolver = sessionResolver;
        this.intakeService = intakeService;
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.memoryService = memoryService;
        this.graphRunner = graphRunner;
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
        PlanTaskSession artifactSelectionResult = tryResumePendingArtifactSelection(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext
        );
        if (artifactSelectionResult != null) {
            return artifactSelectionResult;
        }
        PlanTaskSession selectionResult = tryResumePendingTaskSelection(
                session,
                resolution,
                rawInstruction,
                userFeedback,
                workspaceContext
        );
        if (selectionResult != null) {
            return selectionResult;
        }
        String userInput = firstText(userFeedback, rawInstruction);

        TaskIntakeDecision intakeDecision = intakeService.decide(
                session,
                rawInstruction,
                userFeedback,
                resolution.existingSession()
        );
        intakeDecision = absorbDocLinksDuringClarification(session, resolution, workspaceContext, intakeDecision, userFeedback, rawInstruction);
        if (shouldDeferExecutingTaskModification(taskId, resolution, session, intakeDecision)) {
            return executingTaskModificationReply(workspaceContext, resolution, userInput);
        }
        if (shouldStartFreshTask(taskId, resolution, intakeDecision)) {
            resolution = new TaskSessionResolution(UUID.randomUUID().toString(), false, resolution.continuationKey());
            session = transientSession(resolution.taskId(), workspaceContext);
        }
        String graphInstruction = stripLeadingMentionPlaceholders(userInput);
        if (graphInstruction.isBlank()) {
            graphInstruction = intakeDecision.effectiveInput();
        }
        if (shouldRouteCompletedAdjustment(taskId, resolution, session, intakeDecision)) {
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
        return result;
    }

    private PlanTaskSession tryResumePendingArtifactSelection(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String rawInstruction,
            String userFeedback,
            WorkspaceContext workspaceContext
    ) {
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
            WorkspaceContext workspaceContext
    ) {
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
        sessionService.saveWithoutVersionChange(session);
        sessionResolver.bindConversation(new TaskSessionResolution(candidate.getTaskId(), true, resolution.continuationKey()));
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

    private void refreshSelectedTaskContext(
            String taskId,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction
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
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        instruction,
                        "completed task adjustment from current IM message",
                        null,
                        null),
                new TaskSessionResolution(taskId, true, resolution == null ? null : resolution.continuationKey()),
                instruction
        );
        sessionService.saveWithoutVersionChange(selected);
    }

    private PlanTaskSession pendingTaskSelectionReply(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskSessionResolution resolution,
            String instruction,
            List<PendingTaskCandidate> candidates
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
        String reply = buildCandidateReply(candidates);
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
                .candidates(candidates)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());
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

    private PlanTaskSession executingTaskModificationReply(
            WorkspaceContext workspaceContext,
            TaskSessionResolution currentResolution,
            String instruction
    ) {
        String reply = "当前任务还在执行中，我先不直接修改或重跑，避免旧执行结果和新计划互相覆盖。"
                + "请等它完成后再说要修改的内容；如果你确实要中断，请先回复“取消当前任务”，取消完成后再发新的调整要求。";
        PlanTaskSession replySession = transientSession(UUID.randomUUID().toString(), workspaceContext);
        TaskSessionResolution replyResolution = new TaskSessionResolution(
                replySession.getTaskId(),
                false,
                currentResolution == null ? null : currentResolution.continuationKey()
        );
        updateSessionEnvelope(
                replySession,
                workspaceContext,
                new TaskIntakeDecision(TaskIntakeTypeEnum.UNKNOWN, instruction, "executing task modification deferred", reply, null),
                replyResolution,
                instruction
        );
        replySession.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        sessionService.saveWithoutVersionChange(replySession);
        return replySession;
    }

    private boolean shouldDeferExecutingTaskModification(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision
    ) {
        if (hasText(explicitTaskId)
                || resolution == null
                || !resolution.existingSession()
                || session == null
                || session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING) {
            return false;
        }
        return intakeDecision != null && intakeDecision.intakeType() == TaskIntakeTypeEnum.PLAN_ADJUSTMENT;
    }

    private boolean shouldRouteCompletedAdjustment(
            String explicitTaskId,
            TaskSessionResolution resolution,
            PlanTaskSession session,
            TaskIntakeDecision intakeDecision
    ) {
        if (intakeDecision == null || intakeDecision.intakeType() != TaskIntakeTypeEnum.PLAN_ADJUSTMENT) {
            return false;
        }
        return !hasText(explicitTaskId) && (!resolution.existingSession() || isCompleted(session));
    }

    private boolean isCompleted(PlanTaskSession session) {
        return session != null && session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED;
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

    private String buildCandidateReply(List<PendingTaskCandidate> candidates) {
        StringBuilder builder = new StringBuilder("我找到多个已完成任务，你想修改哪一个？");
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
