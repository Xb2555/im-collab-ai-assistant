package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.planner.gate.PlannerCapabilityPolicy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PlannerReviewTool {

    private final PlannerCapabilityPolicy capabilityPolicy;

    public PlannerReviewTool(PlannerCapabilityPolicy capabilityPolicy) {
        this.capabilityPolicy = capabilityPolicy;
    }

    @Tool(description = "Scenario B: review a generated or adjusted plan before final gate validation.")
    public PlanReviewResult review(PlanTaskSession session) {
        if (session == null) {
            return PlanReviewResult.rejected(List.of("task session is missing"), "任务不存在，无法审查计划。");
        }
        List<UserPlanCard> cards = session.getPlanCards() == null ? List.of() : session.getPlanCards();
        if (cards.isEmpty()) {
            return PlanReviewResult.rejected(List.of("PLAN_EMPTY"), "未能生成可执行的计划步骤。");
        }
        List<String> issues = new ArrayList<>();
        for (UserPlanCard card : cards) {
            if (card == null) {
                issues.add("plan contains empty card");
                continue;
            }
            if (card.getType() == null || !capabilityPolicy.supportsArtifact(card.getType().name())) {
                issues.add("unsupported card type: " + (card.getType() == null ? "null" : card.getType().name()));
            }
            if (card.getTitle() == null || card.getTitle().isBlank()) {
                issues.add("card missing title: " + card.getCardId());
            }
        }
        if (!issues.isEmpty()) {
            return PlanReviewResult.rejected(issues, "当前计划包含系统暂不支持或不完整的步骤。");
        }
        return PlanReviewResult.passed("plan review passed");
    }
}
