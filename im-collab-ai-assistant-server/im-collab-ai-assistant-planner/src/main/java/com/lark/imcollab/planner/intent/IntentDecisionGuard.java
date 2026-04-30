package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.stereotype.Service;

@Service
public class IntentDecisionGuard {

    private final PlannerProperties plannerProperties;

    public IntentDecisionGuard(PlannerProperties plannerProperties) {
        this.plannerProperties = plannerProperties;
    }

    IntentDecisionGuard() {
        this(new PlannerProperties());
    }

    public IntentRoutingResult guard(
            PlanTaskSession session,
            String rawInput,
            boolean existingSession,
            IntentRoutingResult candidate
    ) {
        String normalized = firstText(candidate == null ? null : candidate.normalizedInput(), rawInput);
        if (candidate == null || candidate.type() == null) {
            return unknown(normalized, "missing intent classification");
        }
        if (candidate.confidence() < plannerProperties.getIntent().getPassThreshold()) {
            return unknown(normalized, "intent confidence below threshold: " + candidate.reason());
        }
        if (!existingSession || session == null) {
            if (candidate.type() == TaskCommandTypeEnum.CANCEL_TASK || candidate.type() == TaskCommandTypeEnum.UNKNOWN) {
                return candidate;
            }
            return rewrite(candidate, TaskCommandTypeEnum.START_TASK, "guard new conversation starts task", normalized, false);
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER
                && candidate.type() != TaskCommandTypeEnum.CANCEL_TASK
                && candidate.type() != TaskCommandTypeEnum.QUERY_STATUS
                && candidate.type() != TaskCommandTypeEnum.CONFIRM_ACTION) {
            return rewrite(candidate, TaskCommandTypeEnum.ANSWER_CLARIFICATION,
                    "guard session waiting clarification", normalized, false);
        }
        if (candidate.type() == TaskCommandTypeEnum.START_TASK && hasExistingPlan(session)) {
            return unknown(normalized, "guard rejected start task inside existing planned session");
        }
        if (candidate.type() == TaskCommandTypeEnum.ADJUST_PLAN && !canAdjust(session)) {
            return unknown(normalized, "guard rejected plan adjustment before plan is available");
        }
        if (!hasText(candidate.normalizedInput())) {
            return rewrite(candidate, candidate.type(), candidate.reason(), normalized, candidate.needsClarification());
        }
        return candidate;
    }

    private boolean canAdjust(PlanTaskSession session) {
        if (session == null) {
            return false;
        }
        PlanningPhaseEnum phase = session.getPlanningPhase();
        return phase == PlanningPhaseEnum.PLAN_READY
                || phase == PlanningPhaseEnum.EXECUTING
                || phase == PlanningPhaseEnum.COMPLETED
                || hasExistingPlan(session);
    }

    private boolean hasExistingPlan(PlanTaskSession session) {
        return session != null
                && ((session.getPlanCards() != null && !session.getPlanCards().isEmpty())
                || session.getPlanBlueprint() != null);
    }

    private IntentRoutingResult rewrite(
            IntentRoutingResult source,
            TaskCommandTypeEnum type,
            String reason,
            String normalizedInput,
            boolean needsClarification
    ) {
        return new IntentRoutingResult(type, source.confidence(), reason, normalizedInput, needsClarification);
    }

    private IntentRoutingResult unknown(String normalizedInput, String reason) {
        return new IntentRoutingResult(TaskCommandTypeEnum.UNKNOWN, 0.0d, reason, normalizedInput, true);
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
}
