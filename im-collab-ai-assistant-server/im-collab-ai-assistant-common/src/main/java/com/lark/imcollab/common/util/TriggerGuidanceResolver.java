package com.lark.imcollab.common.util;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.vo.TriggerGuidanceVO;

import java.util.List;

public class TriggerGuidanceResolver {

    public static final String CODE_EXECUTE_PLAN = "EXECUTE_PLAN";
    public static final String CODE_FULL_RESET = "FULL_RESET";
    public static final String CODE_EDIT_CURRENT_PLAN = "EDIT_CURRENT_PLAN";

    public List<TriggerGuidanceVO> resolve(PlanTaskSession session) {
        return resolve(session, null);
    }

    public List<TriggerGuidanceVO> resolve(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (session == null || session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY) {
            return List.of();
        }
        boolean hasCompletedOutputs = hasCompletedOutputs(session, snapshot);
        return List.of(
                new TriggerGuidanceVO(
                        CODE_EXECUTE_PLAN,
                        "开始执行",
                        "开始执行",
                        "按当前计划继续",
                        true
                ),
                new TriggerGuidanceVO(
                        CODE_FULL_RESET,
                        "从头重做",
                        "从头重做",
                        hasCompletedOutputs
                                ? "整任务重跑，并丢弃当前已完成产物"
                                : "放弃当前方案，重新规划并重跑整个任务",
                        true
                ),
                new TriggerGuidanceVO(
                        CODE_EDIT_CURRENT_PLAN,
                        "继续修改",
                        null,
                        "直接说你想改哪一步、改成什么",
                        true
                )
        );
    }

    private boolean hasCompletedOutputs(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (snapshot != null && hasVisibleArtifacts(snapshot.getArtifacts())) {
            return true;
        }
        if (snapshot != null && hasCompletedSteps(snapshot.getSteps())) {
            return true;
        }
        return activeCards(session).stream().anyMatch(this::isCompletedCardWithOutputs);
    }

    private boolean hasVisibleArtifacts(List<ArtifactRecord> artifacts) {
        return artifacts != null && artifacts.stream().anyMatch(artifact ->
                artifact != null && (hasText(artifact.getUrl()) || hasText(artifact.getTitle()) || hasText(artifact.getPreview())));
    }

    private boolean hasCompletedSteps(List<TaskStepRecord> steps) {
        return steps != null && steps.stream().anyMatch(step ->
                step != null && step.getStatus() == StepStatusEnum.COMPLETED);
    }

    private boolean isCompletedCardWithOutputs(UserPlanCard card) {
        return card != null
                && "COMPLETED".equalsIgnoreCase(card.getStatus())
                && (card.getArtifactRefs() == null || card.getArtifactRefs().isEmpty() ? true : !card.getArtifactRefs().isEmpty());
    }

    private List<UserPlanCard> activeCards(PlanTaskSession session) {
        if (session == null) {
            return List.of();
        }
        if (session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            return session.getPlanCards().stream()
                    .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                    .toList();
        }
        if (session.getPlanBlueprint() == null || session.getPlanBlueprint().getPlanCards() == null) {
            return List.of();
        }
        return session.getPlanBlueprint().getPlanCards().stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
