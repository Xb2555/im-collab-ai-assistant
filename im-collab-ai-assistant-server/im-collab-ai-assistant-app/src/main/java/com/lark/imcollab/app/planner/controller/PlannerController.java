package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.PlannerFacade;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.utils.ResultUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@Tag(name = "任务管理")
@RequiredArgsConstructor
public class PlannerController {

    private final PlannerFacade plannerFacade;
    private final HarnessFacade harnessFacade;

    @PostMapping("/plan")
    @Operation(summary = "创建任务规划")
    public BaseResponse<Task> plan(@RequestBody Map<String, String> body) {
        Conversation conversation = Conversation.builder()
                .conversationId(UUID.randomUUID().toString())
                .userId(body.getOrDefault("userId", "anonymous"))
                .rawMessage(body.get("rawInstruction"))
                .receivedAt(Instant.now())
                .build();
        return ResultUtils.success(plannerFacade.plan(conversation));
    }

    @PostMapping("/{taskId}/execute")
    @Operation(summary = "确认执行任务")
    public BaseResponse<Task> execute(@PathVariable String taskId) {
        return ResultUtils.success(harnessFacade.startExecution(taskId));
    }

    @PostMapping("/{taskId}/replan")
    @Operation(summary = "重新规划任务")
    public BaseResponse<Task> replan(@PathVariable String taskId, @RequestBody Map<String, String> body) {
        return ResultUtils.success(plannerFacade.replan(taskId, body.get("feedback")));
    }

    @PostMapping("/{taskId}/approve")
    @Operation(summary = "审批任务步骤")
    public BaseResponse<Task> approve(@PathVariable String taskId, @RequestBody Map<String, String> body) {
        Approval approval = Approval.builder()
                .approvalId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(body.get("stepId"))
                .status(ApprovalStatus.valueOf(body.getOrDefault("status", "APPROVED")))
                .userFeedback(body.get("feedback"))
                .decidedAt(Instant.now())
                .build();
        return ResultUtils.success(harnessFacade.resumeExecution(taskId, approval));
    }

    @PostMapping("/{taskId}/abort")
    @Operation(summary = "中止任务")
    public BaseResponse<Task> abort(@PathVariable String taskId) {
        return ResultUtils.success(harnessFacade.abortExecution(taskId));
    }
}
