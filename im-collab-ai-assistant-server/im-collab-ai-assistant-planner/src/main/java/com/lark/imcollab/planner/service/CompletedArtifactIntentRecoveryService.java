package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CompletedArtifactIntentRecoveryService {

    private final TaskSessionResolver sessionResolver;
    private final ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider;
    private final ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider;

    @Autowired
    public CompletedArtifactIntentRecoveryService(
            TaskSessionResolver sessionResolver,
            ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider,
            ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider
    ) {
        this.sessionResolver = sessionResolver;
        this.documentEditIntentFacadeProvider = documentEditIntentFacadeProvider;
        this.presentationEditIntentFacadeProvider = presentationEditIntentFacadeProvider;
    }

    public CompletedArtifactIntentRecoveryService(TaskSessionResolver sessionResolver) {
        this(sessionResolver, null, null);
    }

    public RecoveryResult recoverTaskIntake(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision originalDecision,
            String instruction
    ) {
        RecoveryEvaluation evaluation = evaluate(session, resolution, workspaceContext, intakeType(originalDecision), instruction);
        if (evaluation.type() == RecoveryType.NONE) {
            return RecoveryResult.none();
        }
        return new RecoveryResult(
                evaluation.type(),
                new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        originalDecision == null ? instruction : originalDecision.effectiveInput(),
                        "completed artifact recovered from new-task classification",
                        originalDecision == null ? null : originalDecision.assistantReply(),
                        originalDecision == null ? null : originalDecision.readOnlyView(),
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ),
                null,
                evaluation.candidates(),
                evaluation.recoveredArtifactType(),
                evaluation.reason()
        );
    }

    public RecoveryResult recoverIntentRouting(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            IntentRoutingResult originalResult,
            String instruction
    ) {
        RecoveryEvaluation evaluation = evaluate(session, resolution, workspaceContext, commandType(originalResult), instruction);
        if (evaluation.type() == RecoveryType.NONE) {
            return RecoveryResult.none();
        }
        return new RecoveryResult(
                evaluation.type(),
                null,
                new IntentRoutingResult(
                        TaskCommandTypeEnum.ADJUST_PLAN,
                        originalResult == null ? 1.0d : originalResult.confidence(),
                        "completed artifact recovered from new-task classification",
                        originalResult == null ? instruction : originalResult.normalizedInput(),
                        false,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ),
                evaluation.candidates(),
                evaluation.recoveredArtifactType(),
                evaluation.reason()
        );
    }

    private RecoveryEvaluation evaluate(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            String originalType,
            String instruction
    ) {
        if (!"NEW_TASK".equals(originalType) && !"START_TASK".equals(originalType)) {
            return RecoveryEvaluation.none("original intent is not recoverable");
        }
        if (session == null || resolution == null || !resolution.existingSession() || session.getPlanningPhase() != com.lark.imcollab.common.model.enums.PlanningPhaseEnum.COMPLETED) {
            return RecoveryEvaluation.none("session is not active completed task");
        }
        if (!isCurrentCompletedTaskActive(session, workspaceContext)) {
            return RecoveryEvaluation.none("current completed task is not active");
        }
        if (looksLikeExplicitFreshTaskRequest(instruction)) {
            return RecoveryEvaluation.none("explicit fresh-task request");
        }
        if (looksLikeNewCompletedDeliverableRequest(instruction)) {
            return RecoveryEvaluation.none("looks like new deliverable request");
        }
        List<ArtifactRecord> editableArtifacts = sessionResolver.resolveEditableArtifacts(session.getTaskId());
        if (editableArtifacts.isEmpty()) {
            return RecoveryEvaluation.none("no editable artifacts");
        }
        ArtifactTypeEnum explicitType = explicitArtifactType(instruction);
        List<ArtifactRecord> docCandidates = editableArtifacts.stream().filter(artifact -> artifact.getType() == ArtifactTypeEnum.DOC).toList();
        List<ArtifactRecord> pptCandidates = editableArtifacts.stream().filter(artifact -> artifact.getType() == ArtifactTypeEnum.PPT).toList();

        boolean docRecoverable = explicitType != ArtifactTypeEnum.PPT && hasConcreteDocEditIntent(instruction, workspaceContext) && !docCandidates.isEmpty();
        boolean pptRecoverable = explicitType != ArtifactTypeEnum.DOC && hasConcretePptEditIntent(instruction, workspaceContext) && !pptCandidates.isEmpty();

        if (explicitType == ArtifactTypeEnum.DOC) {
            return resolveByType(ArtifactTypeEnum.DOC, docRecoverable, docCandidates, "explicit DOC edit");
        }
        if (explicitType == ArtifactTypeEnum.PPT) {
            return resolveByType(ArtifactTypeEnum.PPT, pptRecoverable, pptCandidates, "explicit PPT edit");
        }
        if (docRecoverable == pptRecoverable) {
            return RecoveryEvaluation.none(docRecoverable ? "both DOC and PPT semantics matched" : "no concrete edit semantics matched");
        }
        if (docRecoverable) {
            return resolveByType(ArtifactTypeEnum.DOC, true, docCandidates, "implicit DOC edit");
        }
        return resolveByType(ArtifactTypeEnum.PPT, true, pptCandidates, "implicit PPT edit");
    }

    private RecoveryEvaluation resolveByType(
            ArtifactTypeEnum artifactType,
            boolean recoverable,
            List<ArtifactRecord> candidates,
            String reason
    ) {
        if (!recoverable || candidates == null || candidates.isEmpty()) {
            return RecoveryEvaluation.none(reason + ": not recoverable");
        }
        if (candidates.size() == 1) {
            return new RecoveryEvaluation(RecoveryType.RECOVERED, candidates, artifactType, reason);
        }
        return new RecoveryEvaluation(RecoveryType.SELECTION_REQUIRED, candidates, artifactType, reason + ": multiple candidates");
    }

    private boolean isCurrentCompletedTaskActive(PlanTaskSession session, WorkspaceContext workspaceContext) {
        if (session == null || workspaceContext == null) {
            return false;
        }
        Optional<ConversationTaskState> state = sessionResolver.conversationState(workspaceContext);
        return state.map(ConversationTaskState::getActiveTaskId)
                .filter(this::hasText)
                .map(activeTaskId -> activeTaskId.equals(session.getTaskId()))
                .orElse(false);
    }

    private boolean hasConcreteDocEditIntent(String instruction, WorkspaceContext workspaceContext) {
        DocumentEditIntentFacade facade = documentEditIntentFacadeProvider == null ? null : documentEditIntentFacadeProvider.getIfAvailable();
        if (facade == null || !hasText(instruction)) {
            return false;
        }
        DocumentEditIntent intent = facade.resolve(instruction, workspaceContext);
        return intent != null && !intent.isClarificationNeeded();
    }

    private boolean hasConcretePptEditIntent(String instruction, WorkspaceContext workspaceContext) {
        PresentationEditIntentFacade facade = presentationEditIntentFacadeProvider == null ? null : presentationEditIntentFacadeProvider.getIfAvailable();
        if (facade == null || !hasText(instruction)) {
            return false;
        }
        PresentationEditIntent intent = facade.resolve(instruction, workspaceContext);
        return intent != null && !intent.isClarificationNeeded();
    }

    private ArtifactTypeEnum explicitArtifactType(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        String normalized = instruction.toLowerCase(Locale.ROOT);
        if (normalized.contains("ppt") || normalized.contains("演示稿") || normalized.contains("幻灯片")) {
            return ArtifactTypeEnum.PPT;
        }
        if (normalized.contains("文档") || normalized.contains("doc")) {
            return ArtifactTypeEnum.DOC;
        }
        return null;
    }

    private boolean looksLikeExplicitFreshTaskRequest(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        String normalized = instruction.toLowerCase(Locale.ROOT);
        return normalized.contains("新建一个任务")
                || normalized.contains("新开一个任务")
                || normalized.contains("另起一个任务")
                || normalized.contains("重新来一份")
                || normalized.contains("重新生成一版")
                || normalized.contains("忽略上一个任务");
    }

    private boolean looksLikeNewCompletedDeliverableRequest(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        return (lower.contains("生成") || lower.contains("整理") || lower.contains("做一版") || lower.contains("输出") || lower.contains("补一份"))
                && (lower.contains("ppt") || lower.contains("演示稿") || lower.contains("幻灯片")
                || lower.contains("摘要") || lower.contains("总结") || lower.contains("文档") || lower.contains("报告"));
    }

    private String intakeType(TaskIntakeDecision decision) {
        return decision == null || decision.intakeType() == null ? null : decision.intakeType().name();
    }

    private String commandType(IntentRoutingResult result) {
        return result == null || result.type() == null ? null : result.type().name();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum RecoveryType {
        NONE,
        RECOVERED,
        SELECTION_REQUIRED
    }

    public record RecoveryResult(
            RecoveryType type,
            TaskIntakeDecision recoveredDecision,
            IntentRoutingResult recoveredIntent,
            List<ArtifactRecord> candidates,
            ArtifactTypeEnum recoveredArtifactType,
            String reason
    ) {
        public static RecoveryResult none() {
            return new RecoveryResult(RecoveryType.NONE, null, null, List.of(), null, null);
        }
    }

    private record RecoveryEvaluation(
            RecoveryType type,
            List<ArtifactRecord> candidates,
            ArtifactTypeEnum recoveredArtifactType,
            String reason
    ) {
        private static RecoveryEvaluation none(String reason) {
            return new RecoveryEvaluation(RecoveryType.NONE, List.of(), null, reason);
        }
    }
}
