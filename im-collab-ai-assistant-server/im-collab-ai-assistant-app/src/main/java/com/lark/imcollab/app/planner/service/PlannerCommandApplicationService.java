package com.lark.imcollab.app.planner.service;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.planner.exception.RetryNotAllowedException;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.springframework.stereotype.Service;

@Service
public class PlannerCommandApplicationService {

    private final PlannerSupervisorGraphRunner graphRunner;
    private final TaskBridgeService taskBridgeService;
    private final PlannerRetryService plannerRetryService;
    private final ImTaskCommandFacade taskCommandFacade;

    public PlannerCommandApplicationService(
            PlannerSupervisorGraphRunner graphRunner,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            ImTaskCommandFacade taskCommandFacade
    ) {
        this.graphRunner = graphRunner;
        this.taskBridgeService = taskBridgeService;
        this.plannerRetryService = plannerRetryService;
        this.taskCommandFacade = taskCommandFacade;
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot) {
        PlannerSupervisorAction action = replanFromRoot
                ? PlannerSupervisorAction.PLAN_ADJUSTMENT
                : PlannerSupervisorAction.CLARIFICATION_REPLY;
        return graphRunner.run(
                new PlannerSupervisorDecision(action, replanFromRoot ? "resume as plan adjustment" : "clarification reply"),
                taskId,
                feedback,
                null,
                feedback
        );
    }

    public PlanTaskSession replan(String taskId, String feedback) {
        PlanTaskSession session = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "user requested plan adjustment"),
                taskId,
                feedback,
                null,
                feedback
        );
        taskBridgeService.ensureTask(session);
        return session;
    }

    public PlanTaskSession confirmExecution(String taskId, PlanTaskSession currentSession) {
        taskBridgeService.ensureTask(currentSession);
        return graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CONFIRM_ACTION, "user confirmed execution"),
                taskId,
                "开始执行",
                null,
                null
        );
    }

    public PlanTaskSession cancel(String taskId) {
        return graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CANCEL_TASK, "user cancelled task"),
                taskId,
                "取消任务",
                null,
                null
        );
    }

    public PlanTaskSession retryFailed(String taskId, PlanTaskSession currentSession) {
        if (!plannerRetryService.isRetryable(taskId, currentSession)) {
            throw new RetryNotAllowedException("当前任务不是失败状态，不需要重试。");
        }
        return taskCommandFacade.retryExecution(taskId);
    }
}
