package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
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
    private final RoutingEvidenceExtractor routingEvidenceExtractor;

    @Autowired
    public CompletedArtifactIntentRecoveryService(
            TaskSessionResolver sessionResolver,
            ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider,
            ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider,
            PlannerProperties plannerProperties
    ) {
        this.sessionResolver = sessionResolver;
        this.documentEditIntentFacadeProvider = documentEditIntentFacadeProvider;
        this.presentationEditIntentFacadeProvider = presentationEditIntentFacadeProvider;
        this.routingEvidenceExtractor = new RoutingEvidenceExtractor(plannerProperties);
    }

    public CompletedArtifactIntentRecoveryService(
            TaskSessionResolver sessionResolver,
            ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider,
            ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider
    ) {
        this(sessionResolver, documentEditIntentFacadeProvider, presentationEditIntentFacadeProvider, new PlannerProperties());
    }

    public CompletedArtifactIntentRecoveryService(TaskSessionResolver sessionResolver) {
        this(sessionResolver, null, null, new PlannerProperties());
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

    public DirectRouteEvaluation evaluateCurrentCompletedArtifactRoute(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            WorkspaceContext workspaceContext,
            String instruction
    ) {
        if (session == null
                || resolution == null
                || !resolution.existingSession()
                || session.getPlanningPhase() != com.lark.imcollab.common.model.enums.PlanningPhaseEnum.COMPLETED) {
            return DirectRouteEvaluation.none("session is not current completed task");
        }
        if (!isCurrentCompletedTaskActive(session, workspaceContext)) {
            return DirectRouteEvaluation.none("current completed task is not active");
        }
        String rejectionReason = softCompletedArtifactEditRejectionReason(instruction);
        if (rejectionReason != null) {
            return DirectRouteEvaluation.none(rejectionReason);
        }
        return directRouteEvaluation(session, workspaceContext, instruction);
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
        String rejectionReason = softCompletedArtifactEditRejectionReason(instruction);
        if (rejectionReason != null) {
            return RecoveryEvaluation.none(rejectionReason);
        }
        DirectRouteEvaluation directRouteEvaluation = directRouteEvaluation(session, workspaceContext, instruction);
        if (directRouteEvaluation.type() == DirectRouteType.NONE) {
            return RecoveryEvaluation.none(directRouteEvaluation.reason());
        }
        return new RecoveryEvaluation(
                directRouteEvaluation.type() == DirectRouteType.DIRECT_ROUTE
                        ? RecoveryType.RECOVERED
                        : RecoveryType.SELECTION_REQUIRED,
                directRouteEvaluation.candidates(),
                directRouteEvaluation.recoveredArtifactType(),
                directRouteEvaluation.reason()
        );
    }

    private DirectRouteEvaluation directRouteEvaluation(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            String instruction
    ) {
        String rejectionReason = softCompletedArtifactEditRejectionReason(instruction);
        if (rejectionReason != null) {
            return DirectRouteEvaluation.none(rejectionReason);
        }
        List<ArtifactRecord> editableArtifacts = sessionResolver.resolveEditableArtifacts(session.getTaskId());
        if (editableArtifacts.isEmpty()) {
            return DirectRouteEvaluation.none("no editable artifacts");
        }
        ArtifactTypeEnum explicitType = explicitArtifactType(instruction);
        List<ArtifactRecord> docCandidates = editableArtifacts.stream().filter(artifact -> artifact.getType() == ArtifactTypeEnum.DOC).toList();
        List<ArtifactRecord> pptCandidates = editableArtifacts.stream().filter(artifact -> artifact.getType() == ArtifactTypeEnum.PPT).toList();

        boolean docRecoverable = explicitType != ArtifactTypeEnum.PPT && hasConcreteDocEditIntent(instruction, workspaceContext) && !docCandidates.isEmpty();
        boolean pptRecoverable = explicitType != ArtifactTypeEnum.DOC && hasConcretePptEditIntent(instruction, workspaceContext) && !pptCandidates.isEmpty();

        if (explicitType == ArtifactTypeEnum.DOC) {
            return resolveDirectRouteByType(ArtifactTypeEnum.DOC, docRecoverable, docCandidates, "explicit DOC edit");
        }
        if (explicitType == ArtifactTypeEnum.PPT) {
            return resolveDirectRouteByType(ArtifactTypeEnum.PPT, pptRecoverable, pptCandidates, "explicit PPT edit");
        }
        if (docRecoverable == pptRecoverable) {
            return DirectRouteEvaluation.none(docRecoverable ? "both DOC and PPT semantics matched" : "no concrete edit semantics matched");
        }
        if (docRecoverable) {
            return resolveDirectRouteByType(ArtifactTypeEnum.DOC, true, docCandidates, "implicit DOC edit");
        }
        return resolveDirectRouteByType(ArtifactTypeEnum.PPT, true, pptCandidates, "implicit PPT edit");
    }

    boolean isSoftCompletedArtifactEditCandidate(String instruction) {
        return softCompletedArtifactEditRejectionReason(instruction) == null;
    }

    String softCompletedArtifactEditRejectionReason(String instruction) {
        if (looksLikeExplicitFreshTaskRequest(instruction)) {
            return "explicit fresh-task request";
        }
        RoutingEvidence evidence = routingEvidenceExtractor.extract(instruction);
        if (evidence.ambiguousMaterialOrganizationLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            return "ambiguous material organization request";
        }
        if (evidence.newDeliverableLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            return "looks like new deliverable request";
        }
        if (evidence.freshTaskLevel() == SignalLevel.HIGH) {
            return "explicit fresh-task request";
        }
        return null;
    }

    private DirectRouteEvaluation resolveDirectRouteByType(
            ArtifactTypeEnum artifactType,
            boolean recoverable,
            List<ArtifactRecord> candidates,
            String reason
    ) {
        if (!recoverable || candidates == null || candidates.isEmpty()) {
            return DirectRouteEvaluation.none(reason + ": not recoverable");
        }
        if (candidates.size() == 1) {
            return new DirectRouteEvaluation(DirectRouteType.DIRECT_ROUTE, candidates, artifactType, reason);
        }
        return new DirectRouteEvaluation(DirectRouteType.SELECTION_REQUIRED, candidates, artifactType, reason + ": multiple candidates");
    }

    private boolean isCurrentCompletedTaskActive(PlanTaskSession session, WorkspaceContext workspaceContext) {
        if (session == null || workspaceContext == null) {
            return false;
        }
        return sessionResolver.isTaskCurrentInConversation(session.getTaskId(), workspaceContext);
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
        return routingEvidenceExtractor.looksLikeExplicitFreshTask(instruction);
    }

    private boolean looksLikeNewCompletedDeliverableRequest(String instruction) {
        return routingEvidenceExtractor.looksLikeNewCompletedDeliverableRequest(instruction);
    }

    private boolean looksLikeAmbiguousMaterialOrganizationRequest(String instruction) {
        return routingEvidenceExtractor.looksLikeAmbiguousMaterialOrganizationRequest(instruction);
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

    public enum DirectRouteType {
        NONE,
        DIRECT_ROUTE,
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

    public record DirectRouteEvaluation(
            DirectRouteType type,
            List<ArtifactRecord> candidates,
            ArtifactTypeEnum recoveredArtifactType,
            String reason
    ) {
        public static DirectRouteEvaluation none(String reason) {
            return new DirectRouteEvaluation(DirectRouteType.NONE, List.of(), null, reason);
        }
    }
}
