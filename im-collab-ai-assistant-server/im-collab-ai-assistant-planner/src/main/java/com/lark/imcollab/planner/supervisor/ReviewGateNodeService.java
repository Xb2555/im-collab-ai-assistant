package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.gate.PlanGateResult;
import com.lark.imcollab.planner.planning.TaskPlanningResult;
import com.lark.imcollab.planner.planning.TaskPlanningService;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReviewGateNodeService {

    private final PlannerReviewTool reviewTool;
    private final PlannerGateTool gateTool;
    private final PlannerQuestionTool questionTool;
    private final TaskPlanningService taskPlanningService;
    private final TaskRuntimeProjectionService projectionService;
    private final TaskRuntimeService taskRuntimeService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerSessionService sessionService;

    public ReviewGateNodeService(
            PlannerReviewTool reviewTool,
            PlannerGateTool gateTool,
            PlannerQuestionTool questionTool,
            TaskPlanningService taskPlanningService,
            TaskRuntimeProjectionService projectionService,
            TaskRuntimeService taskRuntimeService,
            PlannerConversationMemoryService memoryService,
            PlannerSessionService sessionService
    ) {
        this.reviewTool = reviewTool;
        this.gateTool = gateTool;
        this.questionTool = questionTool;
        this.taskPlanningService = taskPlanningService;
        this.projectionService = projectionService;
        this.taskRuntimeService = taskRuntimeService;
        this.memoryService = memoryService;
        this.sessionService = sessionService;
    }

    public PlanReviewResult review(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session == null
                || session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER
                || session.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            return PlanReviewResult.passed("review skipped for non-reviewable phase");
        }
        projectionService.projectStage(session, TaskEventTypeEnum.PLAN_REVIEWING, "Reviewing generated plan");
        PlanReviewResult result = reviewTool.review(session);
        if (result == null) {
            return PlanReviewResult.passed("review skipped");
        }
        if (!result.passed()) {
            if (isEmptyPlan(result)) {
                markPlanningFailed(session, firstNonBlank(
                        result.message(),
                        "未能生成可执行的计划步骤。"));
                return result;
            }
            questionTool.askUser(session, List.of(firstNonBlank(
                    result.message(),
                    "当前计划还需要确认一下：你想保留哪些步骤？")));
        }
        return result;
    }

    public PlanTaskSession gateAndProject(String taskId, TaskEventTypeEnum readyEventType) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session == null) {
            return null;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER
                || session.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            return session;
        }
        TaskPlanningResult planningResult = taskPlanningService.buildReadyPlan(session);
        projectionService.projectStage(session, TaskEventTypeEnum.PLAN_GATE_CHECKING, "Checking generated plan");
        PlanGateResult gateResult = gateTool.check(planningResult.graph(), planningResult.executionContract());
        if (!gateResult.passed()) {
            String question = humanizeGateFailure(gateResult.reasons());
            questionTool.askUser(session, List.of(question));
            return sessionService.get(taskId);
        }
        session.setClarificationQuestions(List.of());
        session.setActivePromptSlots(List.of());
        sessionService.save(session);
        taskRuntimeService.projectPlanReady(session, readyEventType);
        memoryService.appendAssistantTurn(session, "计划已生成：" + summarizeCards(session.getPlanCards()));
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "PLAN_READY");
        return session;
    }

    private void markPlanningFailed(PlanTaskSession session, String reason) {
        if (session == null) {
            return;
        }
        String safeReason = firstNonBlank(reason, "未能生成可执行的计划步骤。");
        session.setPlanningPhase(PlanningPhaseEnum.FAILED);
        session.setTransitionReason(safeReason);
        memoryService.appendAssistantTurn(session, "规划失败：" + safeReason);
        sessionService.save(session);
        projectionService.projectStage(session, TaskEventTypeEnum.PLAN_FAILED, safeReason);
        sessionService.publishEvent(session.getTaskId(), "FAILED");
    }

    private boolean isEmptyPlan(PlanReviewResult result) {
        return result != null
                && result.issues() != null
                && result.issues().stream().anyMatch("PLAN_EMPTY"::equals);
    }

    private String summarizeCards(List<UserPlanCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "暂无步骤";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < cards.size(); index++) {
            if (index > 0) {
                builder.append(" -> ");
            }
            builder.append(index + 1).append(".").append(cards.get(index).getTitle());
        }
        return builder.toString();
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

    private String humanizeGateFailure(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "当前计划包含暂不支持的步骤或产物。请确认是否改成文档、PPT 或摘要。";
        }
        String first = reasons.get(0);
        if (first.contains("multiple DOC steps")) {
            return "当前执行链路一次只能稳定完成一个文档步骤。你可以把新增内容并进主文档，或者拆成后续单独任务。";
        }
        if (first.contains("multiple PPT steps")) {
            return "当前执行链路一次只能稳定完成一个 PPT 步骤。你可以先收敛成一个 PPT 产物。";
        }
        return "当前计划包含暂不支持的步骤或产物。请确认是否改成文档、PPT 或摘要。";
    }
}
