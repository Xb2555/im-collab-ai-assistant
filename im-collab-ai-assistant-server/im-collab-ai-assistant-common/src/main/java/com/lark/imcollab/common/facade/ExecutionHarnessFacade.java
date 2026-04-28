package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

/**
 * Harness 执行入口。
 * planner 在计划确认后通过该门面触发场景执行，而不是直接编排文档/PPT 生成细节。
 */
public interface ExecutionHarnessFacade {

    PlanTaskSession startExecution(String taskId);

    PlanTaskSession interruptExecution(String taskId);

    PlanTaskSession resumeExecution(String taskId, String userFeedback);
}
