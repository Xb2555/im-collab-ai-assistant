package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ReplanScopeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ReplanScopeService {

    private final TaskRuntimeService taskRuntimeService;

    public ReplanScopeService(TaskRuntimeService taskRuntimeService) {
        this.taskRuntimeService = taskRuntimeService;
    }

    public ReplanScopeDecision inferForInterruptedExecution(
            PlanTaskSession session,
            String instruction,
            boolean preferTailReplan
    ) {
        TaskRuntimeSnapshot snapshot = snapshot(session);
        String anchorCardId = resolveAnchorCardId(session, snapshot);
        if (isExplicitFullReset(instruction)) {
            return new ReplanScopeDecision(ReplanScopeEnum.FULL_TASK_RESET, null, "explicit full reset request");
        }
        if (isExplicitCurrentStepRedo(instruction) && hasText(anchorCardId)) {
            return new ReplanScopeDecision(ReplanScopeEnum.CURRENT_STEP_REDO, anchorCardId, "explicit current-step redo request");
        }
        if (preferTailReplan || hasCompletedPrefix(session, snapshot)) {
            return new ReplanScopeDecision(ReplanScopeEnum.TAIL_REPLAN, anchorCardId, "preserve completed prefix");
        }
        return new ReplanScopeDecision(ReplanScopeEnum.FULL_TASK_RESET, null, "no completed prefix yet");
    }

    public ReplanScopeDecision resolveForPlanAdjustment(PlanTaskSession session, String instruction) {
        TaskRuntimeSnapshot snapshot = snapshot(session);
        String inferredAnchorCardId = resolveAnchorCardId(session, snapshot);
        if (isExplicitFullReset(instruction)) {
            return new ReplanScopeDecision(ReplanScopeEnum.FULL_TASK_RESET, null, "explicit full reset request");
        }
        if (isExplicitCurrentStepRedo(instruction) && hasText(inferredAnchorCardId)) {
            return new ReplanScopeDecision(ReplanScopeEnum.CURRENT_STEP_REDO, inferredAnchorCardId, "explicit current-step redo request");
        }
        TaskIntakeState intakeState = session == null ? null : session.getIntakeState();
        if (intakeState != null && intakeState.getReplanScope() != null) {
            String anchorCardId = hasText(intakeState.getReplanAnchorCardId())
                    ? intakeState.getReplanAnchorCardId()
                    : inferredAnchorCardId;
            return new ReplanScopeDecision(intakeState.getReplanScope(), anchorCardId, "reuse persisted replan scope");
        }
        if (hasCompletedPrefix(session, snapshot)) {
            return new ReplanScopeDecision(ReplanScopeEnum.TAIL_REPLAN, inferredAnchorCardId, "completed prefix detected during replan");
        }
        return new ReplanScopeDecision(ReplanScopeEnum.FULL_TASK_RESET, null, "fallback full reset");
    }

    public void markTailContinuationState(PlanTaskSession session) {
        if (session == null) {
            return;
        }
        apply(session, new ReplanScopeDecision(
                ReplanScopeEnum.TAIL_REPLAN,
                firstUnfinishedCardId(session),
                "follow-up continuation"
        ), true);
    }

    public void apply(PlanTaskSession session, ReplanScopeDecision decision, boolean preserveExistingArtifacts) {
        if (session == null || decision == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setReplanScope(decision.scope());
        intakeState.setReplanAnchorCardId(decision.anchorCardId());
        intakeState.setPreserveExistingArtifactsOnExecution(preserveExistingArtifacts);
        session.setIntakeState(intakeState);
    }

    public void clear(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setReplanScope(null);
        session.getIntakeState().setReplanAnchorCardId(null);
    }

    public String firstUnfinishedCardId(PlanTaskSession session) {
        TaskRuntimeSnapshot snapshot = snapshot(session);
        String fromRuntime = firstUnfinishedStepId(snapshot);
        if (hasText(fromRuntime)) {
            return fromRuntime;
        }
        return firstUnfinishedCardId(cards(session));
    }

    public String resolveAnchorCardId(PlanTaskSession session) {
        return resolveAnchorCardId(session, snapshot(session));
    }

    private String resolveAnchorCardId(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        String runningStepId = runningStepId(snapshot);
        if (hasText(runningStepId)) {
            return runningStepId;
        }
        String unfinishedStepId = firstUnfinishedStepId(snapshot);
        if (hasText(unfinishedStepId)) {
            return unfinishedStepId;
        }
        return firstUnfinishedCardId(cards(session));
    }

    private boolean hasCompletedPrefix(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (snapshot != null && snapshot.getSteps() != null) {
            return snapshot.getSteps().stream()
                    .anyMatch(step -> step != null && step.getStatus() == StepStatusEnum.COMPLETED);
        }
        return cards(session).stream()
                .anyMatch(card -> card != null && "COMPLETED".equalsIgnoreCase(card.getStatus()));
    }

    private String runningStepId(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getSteps() == null) {
            return null;
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.RUNNING)
                .map(TaskStepRecord::getStepId)
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
    }

    private String firstUnfinishedStepId(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getSteps() == null) {
            return null;
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null
                        && step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SUPERSEDED
                        && step.getStatus() != StepStatusEnum.SKIPPED)
                .map(TaskStepRecord::getStepId)
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
    }

    private String firstUnfinishedCardId(List<UserPlanCard> cards) {
        return cards.stream()
                .filter(card -> card != null
                        && !"COMPLETED".equalsIgnoreCase(card.getStatus())
                        && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .map(UserPlanCard::getCardId)
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
    }

    private List<UserPlanCard> cards(PlanTaskSession session) {
        if (session == null) {
            return List.of();
        }
        if (session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            return session.getPlanCards();
        }
        if (session.getPlanBlueprint() != null && session.getPlanBlueprint().getPlanCards() != null) {
            return session.getPlanBlueprint().getPlanCards();
        }
        return List.of();
    }

    private TaskRuntimeSnapshot snapshot(PlanTaskSession session) {
        if (session == null || !hasText(session.getTaskId()) || taskRuntimeService == null) {
            return null;
        }
        return taskRuntimeService.getSnapshot(session.getTaskId());
    }

    private boolean isExplicitFullReset(String instruction) {
        String normalized = compact(instruction);
        return normalized.contains("从头重做")
                || normalized.contains("从头再来")
                || normalized.contains("整个任务重来")
                || normalized.contains("整个方案重来")
                || normalized.contains("全部重做")
                || normalized.contains("全部重来")
                || normalized.contains("忽略已完成")
                || normalized.contains("忽略前面已完成")
                || normalized.contains("忽略前面");
    }

    private boolean isExplicitCurrentStepRedo(String instruction) {
        String normalized = compact(instruction);
        return normalized.contains("当前这一步")
                || normalized.contains("只改当前步骤")
                || normalized.contains("只改正在生成")
                || normalized.contains("这一页重做")
                || normalized.contains("当前页重做")
                || normalized.contains("当前步骤重做");
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
                .replace("'", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ReplanScopeDecision(ReplanScopeEnum scope, String anchorCardId, String reason) {}
}
