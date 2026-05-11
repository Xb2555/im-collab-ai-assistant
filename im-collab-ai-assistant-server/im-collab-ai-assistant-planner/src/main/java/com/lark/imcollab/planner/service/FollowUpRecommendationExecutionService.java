package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.SourceArtifactRef;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.replan.PlanPatchCardDraft;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.replan.PlanPatchOperation;
import com.lark.imcollab.planner.supervisor.PlannerPatchTool;
import com.lark.imcollab.planner.supervisor.PlanningNodeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FollowUpRecommendationExecutionService {

    private final PlannerStateStore stateStore;
    private final TaskSessionResolver sessionResolver;
    private final PlannerSessionService sessionService;
    private final PlannerSupervisorGraphRunner graphRunner;
    private final PlanningNodeService planningNodeService;
    private final ConversationTaskStateService conversationTaskStateService;
    private final PlannerPatchTool patchTool;
    private final PlanQualityService qualityService;
    private final ReplanScopeService replanScopeService;

    @Autowired
    public FollowUpRecommendationExecutionService(
            PlannerStateStore stateStore,
            TaskSessionResolver sessionResolver,
            PlannerSessionService sessionService,
            PlannerSupervisorGraphRunner graphRunner,
            PlanningNodeService planningNodeService,
            ConversationTaskStateService conversationTaskStateService,
            PlannerPatchTool patchTool,
            PlanQualityService qualityService
    ) {
        this(stateStore, sessionResolver, sessionService, graphRunner, planningNodeService,
                conversationTaskStateService, patchTool, qualityService, null);
    }

    public FollowUpRecommendationExecutionService(
            PlannerStateStore stateStore,
            TaskSessionResolver sessionResolver,
            PlannerSessionService sessionService,
            PlannerSupervisorGraphRunner graphRunner,
            PlanningNodeService planningNodeService,
            ConversationTaskStateService conversationTaskStateService,
            PlannerPatchTool patchTool,
            PlanQualityService qualityService,
            ReplanScopeService replanScopeService
    ) {
        this.stateStore = stateStore;
        this.sessionResolver = sessionResolver;
        this.sessionService = sessionService;
        this.graphRunner = graphRunner;
        this.planningNodeService = planningNodeService;
        this.conversationTaskStateService = conversationTaskStateService;
        this.patchTool = patchTool;
        this.qualityService = qualityService;
        this.replanScopeService = replanScopeService;
    }

    public Optional<NextStepRecommendation> findExecutableRecommendation(String taskId, String recommendationId) {
        if (!hasText(taskId) || !hasText(recommendationId) || !isCompletedTask(taskId)) {
            return Optional.empty();
        }
        return stateStore.findLatestEvaluation(taskId)
                .filter(evaluation -> evaluation.getVerdict() == ResultVerdictEnum.PASS)
                .map(TaskResultEvaluation::getNextStepRecommendations)
                .stream()
                .flatMap(List::stream)
                .filter(recommendation -> recommendation != null
                        && hasText(recommendation.getRecommendationId())
                        && recommendationId.trim().equalsIgnoreCase(recommendation.getRecommendationId().trim()))
                .findFirst();
    }

    public PlanTaskSession executeGuiRecommendation(
            String taskId,
            String recommendationId,
            WorkspaceContext workspaceContext
    ) {
        NextStepRecommendation recommendation = findExecutableRecommendation(taskId, recommendationId)
                .orElseThrow(() -> new IllegalArgumentException("Recommendation not found or not executable: " + recommendationId));
        return execute(descriptor(recommendation), workspaceContext, null, null);
    }

    public PlanTaskSession executePendingRecommendation(
            PendingFollowUpRecommendation recommendation,
            WorkspaceContext workspaceContext,
            String userInput,
            String continuationKey
    ) {
        if (recommendation == null || !hasText(recommendation.getRecommendationId())) {
            throw new IllegalArgumentException("Pending recommendation is missing");
        }
        return execute(descriptor(recommendation), workspaceContext, userInput, continuationKey);
    }

    private PlanTaskSession execute(
            RecommendationDescriptor recommendation,
            WorkspaceContext workspaceContext,
            String userInput,
            String continuationKey
    ) {
        if (recommendation.followUpMode() == FollowUpModeEnum.START_NEW_TASK) {
            return startNewTask(recommendation, workspaceContext, userInput, continuationKey);
        }
        return continueCurrentTask(recommendation, workspaceContext, userInput, continuationKey);
    }

    private PlanTaskSession continueCurrentTask(
            RecommendationDescriptor recommendation,
            WorkspaceContext workspaceContext,
            String userInput,
            String continuationKey
    ) {
        if (!hasText(recommendation.targetTaskId())) {
            throw new IllegalArgumentException("Recommendation target task is missing");
        }
        PlanTaskSession targetSession = sessionService.get(recommendation.targetTaskId());
        WorkspaceContext followUpContext = appendFollowUpSourceArtifact(workspaceContext, recommendation, targetSession);
        String effectiveUserInput = firstText(userInput, recommendation.suggestedUserInstruction(), recommendation.plannerInstruction());
        String plannerInstruction = appendFollowUpPlannerHints(recommendation, userInput);
        PlanTaskSession result = tryStructuredContinuation(targetSession, recommendation, effectiveUserInput, userInput);
        if (result == null) {
            result = graphRunner.run(
                    new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "resume pending follow-up recommendation"),
                    recommendation.targetTaskId(),
                    plannerInstruction,
                    followUpContext,
                    effectiveUserInput
            );
        }
        clearPendingFollowUpRecommendationsIfSucceeded(continuationKey, result);
        normalizeContinuationResult(result, continuationKey, effectiveUserInput);
        markPreserveExistingArtifactsOnExecution(result);
        return result;
    }

    private PlanTaskSession startNewTask(
            RecommendationDescriptor recommendation,
            WorkspaceContext workspaceContext,
            String userInput,
            String continuationKey
    ) {
        String newTaskId = UUID.randomUUID().toString();
        WorkspaceContext followUpContext = appendFollowUpSourceArtifact(
                workspaceContext,
                recommendation,
                safeGet(recommendation.targetTaskId())
        );
        String effectiveUserInput = firstText(userInput, recommendation.suggestedUserInstruction(), recommendation.plannerInstruction());
        String plannerInstruction = appendFollowUpPlannerHints(recommendation, userInput);
        PlanTaskSession result = planningNodeService.plan(newTaskId, plannerInstruction, followUpContext, effectiveUserInput);
        clearPendingFollowUpRecommendationsIfSucceeded(continuationKey, result);
        if (hasText(continuationKey)) {
            sessionResolver.bindConversation(new TaskSessionResolution(newTaskId, true, continuationKey));
            normalizeNewTaskResult(result, continuationKey, effectiveUserInput);
        }
        return result;
    }

    private PlanTaskSession tryStructuredContinuation(
            PlanTaskSession targetSession,
            RecommendationDescriptor recommendation,
            String effectiveUserInput,
            String userInput
    ) {
        if (targetSession == null
                || targetSession.getPlanBlueprint() == null
                || patchTool == null
                || qualityService == null) {
            return null;
        }
        PlanCardTypeEnum cardType = toPlanCardType(recommendation.targetDeliverable());
        if (cardType == null) {
            return null;
        }
        PlanPatchIntent patchIntent = PlanPatchIntent.builder()
                .operation(PlanPatchOperation.ADD_STEP)
                .targetCardIds(List.of())
                .orderedCardIds(List.of())
                .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                        .title(buildFollowUpCardTitle(recommendation, cardType))
                        .description(buildFollowUpCardDescription(recommendation, effectiveUserInput, userInput))
                        .type(cardType)
                        .build()))
                .confidence(1.0d)
                .reason(firstText(recommendation.plannerInstruction(), "resume pending follow-up recommendation"))
                .build();
        qualityService.applyMergedPlanAdjustment(
                targetSession,
                patchTool.merge(targetSession.getPlanBlueprint(), patchIntent, targetSession.getTaskId()),
                firstText(recommendation.plannerInstruction(), "resume pending follow-up recommendation")
        );
        sessionService.saveWithoutVersionChange(targetSession);
        return targetSession;
    }

    private void normalizeContinuationResult(PlanTaskSession session, String continuationKey, String userInput) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setContinuedConversation(hasText(continuationKey));
        intakeState.setContinuationKey(continuationKey);
        intakeState.setLastUserMessage(userInput);
        intakeState.setRoutingReason("resume pending follow-up recommendation");
        intakeState.setAssistantReply(null);
        intakeState.setReadOnlyView(null);
        intakeState.setAdjustmentTarget(AdjustmentTargetEnum.READY_PLAN);
        session.setIntakeState(intakeState);
        if (replanScopeService != null) {
            replanScopeService.markTailContinuationState(session);
        }
        sessionService.saveWithoutVersionChange(session);
    }

    private void normalizeNewTaskResult(PlanTaskSession session, String continuationKey, String userInput) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.NEW_TASK);
        intakeState.setContinuedConversation(hasText(continuationKey));
        intakeState.setContinuationKey(continuationKey);
        intakeState.setLastUserMessage(userInput);
        intakeState.setRoutingReason("execute structured follow-up recommendation");
        session.setIntakeState(intakeState);
        sessionService.saveWithoutVersionChange(session);
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
        if (replanScopeService != null) {
            replanScopeService.markTailContinuationState(session);
        }
        sessionService.saveWithoutVersionChange(session);
    }

    private void clearPendingFollowUpRecommendationsIfSucceeded(String continuationKey, PlanTaskSession result) {
        if (conversationTaskStateService == null
                || !hasText(continuationKey)
                || result == null
                || (result.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY
                && result.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                && result.getPlanningPhase() != PlanningPhaseEnum.COMPLETED)) {
            return;
        }
        conversationTaskStateService.clearPendingFollowUpRecommendations(continuationKey);
    }

    private PlanCardTypeEnum toPlanCardType(ArtifactTypeEnum targetDeliverable) {
        if (targetDeliverable == null) {
            return null;
        }
        return switch (targetDeliverable) {
            case DOC -> PlanCardTypeEnum.DOC;
            case PPT -> PlanCardTypeEnum.PPT;
            case SUMMARY -> PlanCardTypeEnum.SUMMARY;
            default -> null;
        };
    }

    private String buildFollowUpCardTitle(RecommendationDescriptor recommendation, PlanCardTypeEnum cardType) {
        if (hasText(recommendation.title())) {
            return recommendation.title().trim();
        }
        return switch (cardType) {
            case DOC -> "生成配套文档";
            case PPT -> "生成汇报PPT初稿";
            case SUMMARY -> "生成任务摘要";
        };
    }

    private String buildFollowUpCardDescription(
            RecommendationDescriptor recommendation,
            String effectiveUserInput,
            String userInput
    ) {
        StringBuilder builder = new StringBuilder(firstText(
                recommendation.plannerInstruction(),
                effectiveUserInput,
                recommendation.suggestedUserInstruction()
        ));
        if (hasText(userInput)
                && !compact(userInput).equals(compact(recommendation.suggestedUserInstruction()))
                && !ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            builder.append(" 用户补充要求：").append(userInput.trim());
        }
        return builder.toString().trim();
    }

    private WorkspaceContext appendFollowUpSourceArtifact(
            WorkspaceContext workspaceContext,
            RecommendationDescriptor recommendation,
            PlanTaskSession targetSession
    ) {
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

    private ArtifactRecord resolveFollowUpSourceArtifact(RecommendationDescriptor recommendation) {
        if (recommendation == null || !hasText(recommendation.targetTaskId())) {
            return null;
        }
        if (hasText(recommendation.sourceArtifactId())) {
            Optional<ArtifactRecord> exact = sessionResolver.findArtifactById(
                    recommendation.targetTaskId(),
                    recommendation.sourceArtifactId()
            );
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return sessionResolver.findLatestShareableArtifact(
                recommendation.targetTaskId(),
                recommendation.sourceArtifactType()
        ).orElse(null);
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

    private String appendFollowUpPlannerHints(RecommendationDescriptor recommendation, String userInput) {
        StringBuilder builder = new StringBuilder(firstText(
                recommendation.plannerInstruction(),
                recommendation.suggestedUserInstruction(),
                userInput
        ));
        if (hasText(recommendation.artifactPolicy())) {
            builder.append("\n产物策略：").append(recommendation.artifactPolicy().trim());
        }
        if (hasText(recommendation.sourceArtifactId())) {
            builder.append("\n来源产物ID：").append(recommendation.sourceArtifactId().trim());
        }
        if (hasText(userInput)
                && !compact(userInput).equals(compact(recommendation.suggestedUserInstruction()))
                && !ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            builder.append("\n用户补充：").append(userInput.trim());
        }
        return builder.toString();
    }

    private boolean isCompletedTask(String taskId) {
        if (!hasText(taskId)) {
            return false;
        }
        Optional<TaskRecord> task = stateStore.findTask(taskId);
        if (task.isPresent() && task.get().getStatus() != null) {
            return task.get().getStatus() == TaskStatusEnum.COMPLETED;
        }
        PlanTaskSession session = safeGet(taskId);
        return session != null && session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED;
    }

    private PlanTaskSession safeGet(String taskId) {
        if (!hasText(taskId)) {
            return null;
        }
        try {
            return sessionService.get(taskId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private RecommendationDescriptor descriptor(NextStepRecommendation recommendation) {
        return new RecommendationDescriptor(
                recommendation == null ? null : recommendation.getRecommendationId(),
                recommendation == null ? null : recommendation.getTitle(),
                recommendation == null ? null : recommendation.getTargetTaskId(),
                recommendation == null ? null : recommendation.getFollowUpMode(),
                recommendation == null ? null : recommendation.getTargetDeliverable(),
                recommendation == null ? null : recommendation.getSourceArtifactId(),
                recommendation == null ? null : recommendation.getSourceArtifactType(),
                recommendation == null ? null : recommendation.getPlannerInstruction(),
                recommendation == null ? null : recommendation.getArtifactPolicy(),
                recommendation == null ? null : recommendation.getSuggestedUserInstruction()
        );
    }

    private RecommendationDescriptor descriptor(PendingFollowUpRecommendation recommendation) {
        return new RecommendationDescriptor(
                recommendation == null ? null : recommendation.getRecommendationId(),
                null,
                recommendation == null ? null : recommendation.getTargetTaskId(),
                recommendation == null ? null : recommendation.getFollowUpMode(),
                recommendation == null ? null : recommendation.getTargetDeliverable(),
                recommendation == null ? null : recommendation.getSourceArtifactId(),
                recommendation == null ? null : recommendation.getSourceArtifactType(),
                recommendation == null ? null : recommendation.getPlannerInstruction(),
                recommendation == null ? null : recommendation.getArtifactPolicy(),
                recommendation == null ? null : recommendation.getSuggestedUserInstruction()
        );
    }

    private String compact(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim()
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

    private String firstText(String... values) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RecommendationDescriptor(
            String recommendationId,
            String title,
            String targetTaskId,
            FollowUpModeEnum followUpMode,
            ArtifactTypeEnum targetDeliverable,
            String sourceArtifactId,
            ArtifactTypeEnum sourceArtifactType,
            String plannerInstruction,
            String artifactPolicy,
            String suggestedUserInstruction
    ) {
    }
}
