package com.lark.imcollab.app.planner.assembler;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.vo.PlanCardVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskActionVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PlannerViewAssembler {

    public PlanPreviewVO toPlanPreview(PlanTaskSession session) {
        return toPlanPreview(session, null);
    }

    public PlanPreviewVO toPlanPreview(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (session == null) {
            return null;
        }
        boolean transientReply = isTransientReply(session);
        return new PlanPreviewVO(
                session.getTaskId(),
                session.getVersion(),
                enumName(session.getPlanningPhase()),
                resolveTitle(session),
                session.getPlanBlueprintSummary(),
                toPlanCards(session.getPlanCards(), snapshot),
                defaultList(session.getClarificationQuestions()),
                defaultList(session.getClarificationAnswers()),
                transientReply
                        ? new TaskActionVO(false, false, false, false, false, false)
                        : resolveActions(session.getPlanningPhase(), session.isAborted()),
                !transientReply,
                !transientReply,
                transientReply,
                session.getIntakeState() == null ? null : session.getIntakeState().getAssistantReply()
        );
    }

    public List<PlanCardVO> toPlanCards(List<UserPlanCard> cards) {
        return toPlanCards(cards, null);
    }

    public List<PlanCardVO> toPlanCards(List<UserPlanCard> cards, TaskRuntimeSnapshot snapshot) {
        Map<String, TaskStepRecord> stepById = defaultList(snapshot == null ? null : snapshot.getSteps()).stream()
                .filter(step -> step != null && step.getStepId() != null)
                .collect(Collectors.toMap(TaskStepRecord::getStepId, Function.identity(), (left, right) -> right));
        return defaultList(cards).stream()
                .map(card -> toPlanCard(card, stepById.get(card.getCardId())))
                .toList();
    }

    private PlanCardVO toPlanCard(UserPlanCard card, TaskStepRecord step) {
        return new PlanCardVO(
                card.getCardId(),
                card.getTitle(),
                card.getDescription(),
                card.getType() == null ? null : card.getType().name(),
                step == null ? card.getStatus() : planCardStatus(step.getStatus()),
                step == null ? card.getProgress() : step.getProgress(),
                defaultList(card.getDependsOn())
        );
    }

    private String planCardStatus(StepStatusEnum status) {
        if (status == null) {
            return "pending";
        }
        return status.name().toLowerCase();
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

    private boolean isTransientReply(PlanTaskSession session) {
        TaskIntakeState intakeState = session.getIntakeState();
        TaskIntakeTypeEnum intakeType = intakeState == null ? null : intakeState.getIntakeType();
        if (intakeType == null) {
            return false;
        }
        return (intakeType == TaskIntakeTypeEnum.UNKNOWN
                || intakeType == TaskIntakeTypeEnum.STATUS_QUERY
                || intakeType == TaskIntakeTypeEnum.CANCEL_TASK
                || intakeType == TaskIntakeTypeEnum.CONFIRM_ACTION)
                && session.getPlanBlueprint() == null
                && defaultList(session.getPlanCards()).isEmpty();
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
