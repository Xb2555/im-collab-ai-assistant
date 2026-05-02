package com.lark.imcollab.app.planner.assembler;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.vo.PlanCardVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskActionVO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlannerViewAssembler {

    public PlanPreviewVO toPlanPreview(PlanTaskSession session) {
        if (session == null) {
            return null;
        }
        return new PlanPreviewVO(
                session.getTaskId(),
                session.getVersion(),
                enumName(session.getPlanningPhase()),
                resolveTitle(session),
                session.getPlanBlueprintSummary(),
                toPlanCards(session.getPlanCards()),
                defaultList(session.getClarificationQuestions()),
                defaultList(session.getClarificationAnswers()),
                resolveActions(session.getPlanningPhase(), session.isAborted())
        );
    }

    public List<PlanCardVO> toPlanCards(List<UserPlanCard> cards) {
        return defaultList(cards).stream()
                .map(this::toPlanCard)
                .toList();
    }

    private PlanCardVO toPlanCard(UserPlanCard card) {
        return new PlanCardVO(
                card.getCardId(),
                card.getTitle(),
                card.getDescription(),
                card.getType() == null ? null : card.getType().name(),
                card.getStatus(),
                card.getProgress(),
                defaultList(card.getDependsOn())
        );
    }

    private TaskActionVO resolveActions(PlanningPhaseEnum phase, boolean aborted) {
        if (aborted || phase == PlanningPhaseEnum.ABORTED || phase == PlanningPhaseEnum.COMPLETED) {
            return new TaskActionVO(false, false, false, false, false, false);
        }
        boolean canConfirm = phase == PlanningPhaseEnum.PLAN_READY;
        boolean canReplan = phase == PlanningPhaseEnum.PLAN_READY
                || phase == PlanningPhaseEnum.EXECUTING
                || phase == PlanningPhaseEnum.FAILED;
        boolean canCancel = phase != PlanningPhaseEnum.COMPLETED && phase != PlanningPhaseEnum.ABORTED;
        boolean canResume = phase == PlanningPhaseEnum.ASK_USER || phase == PlanningPhaseEnum.FAILED;
        boolean canInterrupt = phase == PlanningPhaseEnum.EXECUTING;
        boolean canRetry = phase == PlanningPhaseEnum.FAILED;
        return new TaskActionVO(canConfirm, canReplan, canCancel, canResume, canInterrupt, canRetry);
    }

    private String resolveTitle(PlanTaskSession session) {
        String planTitle = buildPlanTitle(session.getPlanCards());
        if (planTitle != null) {
            return planTitle;
        }
        if (session.getExecutionContract() != null && session.getExecutionContract().getTaskBrief() != null) {
            return session.getExecutionContract().getTaskBrief();
        }
        if (session.getPlanBlueprint() != null && session.getPlanBlueprint().getTaskBrief() != null) {
            return session.getPlanBlueprint().getTaskBrief();
        }
        if (session.getIntentSnapshot() != null && session.getIntentSnapshot().getUserGoal() != null) {
            return session.getIntentSnapshot().getUserGoal();
        }
        return session.getPlanBlueprintSummary();
    }

    private String buildPlanTitle(List<UserPlanCard> cards) {
        List<String> items = defaultList(cards).stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .map(this::cardTitleItem)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (items.isEmpty()) {
            return null;
        }
        int maxItems = Math.min(items.size(), 4);
        String suffix = items.size() > maxItems ? "等" + items.size() + "项任务" : "";
        return "生成" + joinChinese(items.subList(0, maxItems)) + suffix;
    }

    private String cardTitleItem(UserPlanCard card) {
        String title = card == null ? null : card.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalized = title.trim()
                .replaceFirst("^(先|再|然后|最后|并)?\\s*(生成|创建|撰写|输出|整理|补充|制作|准备)", "")
                .trim();
        return normalized.isBlank() ? title.trim() : normalized;
    }

    private String joinChinese(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + "和" + items.get(1);
        }
        return String.join("、", items.subList(0, items.size() - 1)) + "和" + items.get(items.size() - 1);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
