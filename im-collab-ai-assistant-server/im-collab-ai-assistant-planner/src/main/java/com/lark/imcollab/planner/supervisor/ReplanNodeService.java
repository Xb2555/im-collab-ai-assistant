package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.replan.PlanAdjustmentInterpreter;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.replan.PlanPatchOperation;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class ReplanNodeService {

    private static final Logger log = LoggerFactory.getLogger(ReplanNodeService.class);

    private final PlannerSessionService sessionService;
    private final PlanAdjustmentInterpreter adjustmentInterpreter;
    private final PlannerPatchTool patchTool;
    private final PlannerQuestionTool questionTool;
    private final PlanningNodeService planningNodeService;
    private final PlanQualityService qualityService;

    public ReplanNodeService(
            PlannerSessionService sessionService,
            PlanAdjustmentInterpreter adjustmentInterpreter,
            PlannerPatchTool patchTool,
            PlannerQuestionTool questionTool,
            PlanningNodeService planningNodeService,
            PlanQualityService qualityService
    ) {
        this.sessionService = sessionService;
        this.adjustmentInterpreter = adjustmentInterpreter;
        this.patchTool = patchTool;
        this.questionTool = questionTool;
        this.planningNodeService = planningNodeService;
        this.qualityService = qualityService;
    }

    public PlanTaskSession replan(String taskId, String adjustmentInstruction, WorkspaceContext workspaceContext) {
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        if (session.getPlanBlueprint() == null) {
            return planningNodeService.plan(taskId, adjustmentInstruction, workspaceContext, null);
        }
        PlanPatchIntent patchIntent = adjustmentInterpreter.interpret(session, adjustmentInstruction, workspaceContext);
        if (patchIntent == null || patchIntent.getOperation() == null
                || patchIntent.getOperation() == PlanPatchOperation.CLARIFY_REQUIRED) {
            questionTool.askUser(session, List.of(firstNonBlank(
                    patchIntent == null ? null : patchIntent.getClarificationQuestion(),
                    "我先不改计划。你想新增、删除、改写，还是调整某一步？")));
            return sessionService.get(taskId);
        }
        if (patchIntent.getOperation() == PlanPatchOperation.REGENERATE_ALL) {
            return planningNodeService.plan(taskId, adjustmentInstruction, workspaceContext, adjustmentInstruction);
        }
        session.setClarifiedInstruction(appendSupplement(
                firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction()),
                adjustmentInstruction
        ));
        String beforeSignature = visiblePlanSignature(session.getPlanBlueprint());
        int beforeCardCount = activeCardCount(session.getPlanBlueprint());
        log.info("Planner replan patch selected task={} operation={} confidence={} targetCards={} newDrafts={} reason={}",
                taskId,
                patchIntent.getOperation(),
                patchIntent.getConfidence(),
                patchIntent.getTargetCardIds() == null ? 0 : patchIntent.getTargetCardIds().size(),
                patchIntent.getNewCardDrafts() == null ? 0 : patchIntent.getNewCardDrafts().size(),
                patchIntent.getReason());
        PlanBlueprint merged = patchTool.merge(session.getPlanBlueprint(), patchIntent, taskId);
        int afterCardCount = activeCardCount(merged);
        log.info("Planner replan merge result task={} operation={} beforeCards={} afterCards={}",
                taskId,
                patchIntent.getOperation(),
                beforeCardCount,
                afterCardCount);
        if (Objects.equals(beforeSignature, visiblePlanSignature(merged))
                || (patchIntent.getOperation() == PlanPatchOperation.ADD_STEP
                && afterCardCount <= beforeCardCount)) {
            questionTool.askUser(session, List.of("我理解你想调整计划，但这次没有形成新的步骤变化。你可以再说具体一点：想新增、删除、修改，还是调整顺序？"));
            return sessionService.get(taskId);
        }
        qualityService.applyMergedPlanAdjustment(session, merged, patchIntent.getReason());
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private String visiblePlanSignature(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (UserPlanCard card : blueprint.getPlanCards()) {
            if (card == null || "SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                continue;
            }
            builder.append(card.getCardId()).append('|')
                    .append(card.getType()).append('|')
                    .append(card.getTitle()).append('|')
                    .append(card.getDescription()).append(';');
        }
        return builder.toString();
    }

    private int activeCardCount(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return 0;
        }
        int count = 0;
        for (UserPlanCard card : blueprint.getPlanCards()) {
            if (card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private String appendSupplement(String base, String supplement) {
        String safeBase = normalize(base);
        String safeSupplement = normalize(supplement);
        if (safeSupplement.isBlank() || safeBase.contains(safeSupplement)) {
            return safeBase;
        }
        if (safeBase.isBlank()) {
            return safeSupplement;
        }
        return safeBase + "\n补充说明：" + safeSupplement;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
