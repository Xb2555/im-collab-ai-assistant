package com.lark.imcollab.planner.controller;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.facade.ExecutionHarnessFacade;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.dto.ResumeRequest;
import com.lark.imcollab.common.model.dto.SubmitResultRequest;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/planner/tasks")
@Tag(name = "任务规划管理", description = "AI Agent 任务理解、规划、执行、反馈闭环接口")
@RequiredArgsConstructor
public class PlannerController {

    private final PlannerPlanFacade plannerPlanFacade;
    private final SupervisorPlannerService supervisorPlannerService;
    private final PlannerSessionService sessionService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateStore repository;
    private final ObjectProvider<ExecutionHarnessFacade> executionHarnessFacadeProvider;

    @PostMapping("/plan")
    @Operation(summary = "1. 创建任务规划", description = "根据用户原始指令理解意图并拆解为可执行的任务卡片")
    public BaseResponse<PlanTaskSession> plan(@RequestBody PlanRequest request) {
        PlanTaskSession session = plannerPlanFacade.plan(
                request.getRawInstruction(),
                request.getWorkspaceContext(),
                request.getTaskId(),
                request.getUserFeedback()
        );
        return ResultUtils.success(session);
    }

    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "1.1. 订阅任务事件流", description = "SSE实时推送任务状态变更事件")
    public Flux<String> streamEvents(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId) {
        return Flux.interval(Duration.ofSeconds(1))
                .flatMap(tick -> {
                    List<String> events = sessionService.getEventJsonList(taskId);
                    int lastIndex = sessionService.getLastEventIndex(taskId);
                    if (events.size() > lastIndex) {
                        sessionService.setLastEventIndex(taskId, events.size());
                        return Flux.fromIterable(events.subList(lastIndex, events.size()));
                    }
                    return Flux.empty();
                })
                .take(Duration.ofMinutes(10));
    }

    @PostMapping("/{taskId}/interrupt")
    @Operation(summary = "2. 中断任务", description = "中断正在执行的任务")
    public BaseResponse<PlanTaskSession> interrupt(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession current = sessionService.get(taskId);
        ExecutionHarnessFacade harnessFacade = executionHarnessFacadeProvider.getIfAvailable();
        PlanTaskSession session = current.getPlanningPhase() == PlanningPhaseEnum.EXECUTING && harnessFacade != null
                ? harnessFacade.interruptExecution(taskId)
                : supervisorPlannerService.interrupt(taskId);
        return ResultUtils.success(session);
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "3. 恢复任务（回答 LLM 的反问）", description = "根据用户反馈恢复被中断的任务")
    public BaseResponse<PlanTaskSession> resume(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestBody ResumeRequest request) {
        PlanTaskSession current = sessionService.get(taskId);
        ExecutionHarnessFacade harnessFacade = executionHarnessFacadeProvider.getIfAvailable();
        PlanTaskSession session = current.getPlanningPhase() == PlanningPhaseEnum.EXECUTING && harnessFacade != null
                ? harnessFacade.resumeExecution(taskId, request.getFeedback())
                : supervisorPlannerService.resume(
                        taskId,
                        request.getFeedback(),
                        request.isReplanFromRoot()
                );
        return ResultUtils.success(session);
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "4. 获取任务状态", description = "查询任务当前状态和进度")
    public BaseResponse<PlanTaskSession> getTask(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        return ResultUtils.success(session);
    }

    @GetMapping("/{taskId}/cards")
    @Operation(summary = "5. 获取任务卡片列表", description = "查询任务包含的所有卡片（子任务）")
    public BaseResponse<List<UserPlanCard>> getCards(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        List<UserPlanCard> cards = session.getPlanCards() != null ? session.getPlanCards() : List.of();
        return ResultUtils.success(cards);
    }

    @PostMapping("/{taskId}/commands")
    @Operation(summary = "6. 执行任务指令（确认执行/重新规划/取消规划）", description = "用户确认执行、重规划或取消任务")
    public BaseResponse<PlanTaskSession> command(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestBody PlanCommandRequest request) {
        PlanTaskSession session = sessionService.get(taskId);
        sessionService.checkVersion(session, request.getVersion());

        return switch (request.getAction()) {
            case "CONFIRM_EXECUTE" -> {
                session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
                session.setTransitionReason("User confirmed execution");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "EXECUTING");
                ExecutionHarnessFacade harnessFacade = executionHarnessFacadeProvider.getIfAvailable();
                if (harnessFacade != null) {
                    session = harnessFacade.startExecution(taskId);
                }
                yield ResultUtils.success(session);
            }
            case "REPLAN" -> {
                PlanTaskSession updated = supervisorPlannerService.resume(taskId, request.getFeedback(), false);
                yield ResultUtils.success(updated);
            }
            case "CANCEL" -> {
                session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
                session.setAborted(true);
                session.setTransitionReason("User cancelled");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "ABORTED");
                yield ResultUtils.success(session);
            }
            default -> throw new IllegalArgumentException("Unknown action: " + request.getAction());
        };
    }

    @PostMapping("/{taskId}/agent-tasks/{agentTaskId}/submit-result")
    @Hidden
    @Operation(summary = "7. 提交任务结果", description = "Agent任务执行完成后提交结果（内部接口）")
    public BaseResponse<TaskResultEvaluation> submitResult(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId,
            @Parameter(description = "Agent任务ID", required = true, example = "agent-task-456") @PathVariable String agentTaskId,
            @RequestBody SubmitResultRequest request) {
        TaskSubmissionResult submission = TaskSubmissionResult.builder()
                .taskId(taskId)
                .agentTaskId(agentTaskId)
                .parentCardId(request.getParentCardId())
                .status(request.getStatus() != null ? request.getStatus() : "COMPLETED")
                .artifactRefs(request.getArtifactRefs())
                .rawOutput(request.getRawOutput())
                .errorMessage(request.getErrorMessage())
                .build();
        repository.saveSubmission(submission);
        TaskResultEvaluation evaluation = evaluationService.evaluate(submission);
        return ResultUtils.success(evaluation);
    }

    @GetMapping("/{taskId}/agent-tasks/{agentTaskId}/evaluation")
    @Hidden
    @Operation(summary = "8. 获取任务评估结果", description = "查询指定Agent任务的评估结果（内部接口）")
    public BaseResponse<TaskResultEvaluation> getEvaluation(
            @Parameter(description = "任务ID", required = true, example = "task-123") @PathVariable String taskId,
            @Parameter(description = "Agent任务ID", required = true, example = "agent-task-456") @PathVariable String agentTaskId) {
        TaskResultEvaluation evaluation = repository.findEvaluation(taskId, agentTaskId)
                .orElseThrow(() -> new RuntimeException("Evaluation not found for agentTaskId: " + agentTaskId));
        return ResultUtils.success(evaluation);
    }
}
