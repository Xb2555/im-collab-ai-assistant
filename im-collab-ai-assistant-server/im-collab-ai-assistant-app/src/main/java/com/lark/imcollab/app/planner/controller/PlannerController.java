package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.app.planner.assembler.PlannerViewAssembler;
import com.lark.imcollab.app.planner.assembler.TaskRuntimeViewAssembler;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.dto.ResumeRequest;
import com.lark.imcollab.common.model.dto.SubmitResultRequest;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.vo.PlanCardVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
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
    private final TaskRuntimeService taskRuntimeService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateStore repository;
    private final HarnessFacade harnessFacade;
    private final PlannerViewAssembler plannerViewAssembler;
    private final TaskRuntimeViewAssembler taskRuntimeViewAssembler;
    private final ObjectMapper objectMapper;

    @PostMapping("/plan")
    @Operation(summary = "1. 创建任务规划", description = "根据用户原始指令理解意图并拆解为可执行的任务卡片")
    public BaseResponse<PlanPreviewVO> plan(@RequestBody PlanRequest request) {
        PlanTaskSession session = plannerPlanFacade.plan(
                request.getRawInstruction(),
                request.getWorkspaceContext(),
                request.getTaskId(),
                request.getUserFeedback()
        );
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "1.1. 订阅任务事件流", description = "SSE 实时推送任务状态变化事件")
    public Flux<String> streamEvents(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId) {
        return Flux.interval(Duration.ofSeconds(1))
                .flatMap(tick -> {
                    List<String> events = sessionService.getEventJsonList(taskId);
                    int lastIndex = sessionService.getLastEventIndex(taskId);
                    if (events.size() > lastIndex) {
                        sessionService.setLastEventIndex(taskId, events.size());
                        return Flux.fromIterable(events.subList(lastIndex, events.size()))
                                .map(eventJson -> ensureEventVersion(taskId, eventJson));
                    }
                    return Flux.empty();
                })
                .take(Duration.ofMinutes(10));
    }

    @PostMapping("/{taskId}/interrupt")
    @Operation(summary = "2. 中断任务", description = "中断正在执行的任务")
    public BaseResponse<PlanPreviewVO> interrupt(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession session = supervisorPlannerService.interrupt(taskId);
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "3. 恢复任务（回答 LLM 的反问）", description = "根据用户反馈恢复被中断的任务")
    public BaseResponse<PlanPreviewVO> resume(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestBody ResumeRequest request) {
        PlanTaskSession session = supervisorPlannerService.resume(
                taskId,
                request.getFeedback(),
                request.isReplanFromRoot()
        );
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "4. 获取任务状态", description = "查询任务当前状态和进度")
    public BaseResponse<PlanPreviewVO> getTask(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @GetMapping("/{taskId}/runtime")
    @Operation(summary = "4.1. 获取任务运行时快照", description = "查询 Task、Step、Artifact 和标准事件，用于前端卡片和后续场景接入")
    public BaseResponse<TaskDetailVO> getRuntimeSnapshot(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId) {
        return ResultUtils.success(taskRuntimeViewAssembler.toTaskDetail(taskRuntimeService.getSnapshot(taskId)));
    }

    @GetMapping("/{taskId}/cards")
    @Operation(summary = "5. 获取任务卡片列表", description = "查询任务包含的所有卡片（子任务）")
    public BaseResponse<List<PlanCardVO>> getCards(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        return ResultUtils.success(plannerViewAssembler.toPlanCards(session.getPlanCards()));
    }

    @PostMapping("/{taskId}/commands")
    @Operation(summary = "6. 执行任务指令（确认执行/重新规划/取消规划）", description = "用户确认执行、重规划或取消任务")
    public BaseResponse<PlanPreviewVO> command(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestBody PlanCommandRequest request) {
        PlanTaskSession session = sessionService.get(taskId);
        sessionService.checkVersion(session, request.getVersion());

        return switch (request.getAction()) {
            case "CONFIRM_EXECUTE" -> {
                session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
                session.setTransitionReason("User confirmed execution");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "EXECUTING");
                taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.EXECUTING, TaskEventTypeEnum.PLAN_APPROVED);
                harnessFacade.startExecution(taskId);
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
            }
            case "REPLAN" -> {
                PlanTaskSession updated = supervisorPlannerService.resume(taskId, request.getFeedback(), false);
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(updated));
            }
            case "CANCEL" -> {
                session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
                session.setAborted(true);
                session.setTransitionReason("User cancelled");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "ABORTED");
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
            }
            default -> throw new IllegalArgumentException("Unknown action: " + request.getAction());
        };
    }

    @PostMapping("/{taskId}/agent-tasks/{agentTaskId}/submit-result")
    @Hidden
    @Operation(summary = "7. 提交任务结果", description = "Agent 任务执行完成后提交结果（内部接口）")
    public BaseResponse<TaskResultEvaluation> submitResult(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @Parameter(description = "Agent 任务 ID", required = true, example = "agent-task-456") @PathVariable String agentTaskId,
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
    @Operation(summary = "8. 获取任务评估结果", description = "查询指定 Agent 任务的评估结果（内部接口）")
    public BaseResponse<TaskResultEvaluation> getEvaluation(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @Parameter(description = "Agent 任务 ID", required = true, example = "agent-task-456") @PathVariable String agentTaskId) {
        TaskResultEvaluation evaluation = repository.findEvaluation(taskId, agentTaskId)
                .orElseThrow(() -> new RuntimeException("Evaluation not found for agentTaskId: " + agentTaskId));
        return ResultUtils.success(evaluation);
    }

    private String ensureEventVersion(String taskId, String eventJson) {
        try {
            JsonNode root = objectMapper.readTree(eventJson);
            if (!(root instanceof ObjectNode objectNode)) {
                return eventJson;
            }
            if (!objectNode.has("version") || objectNode.path("version").isNull()) {
                objectNode.put("version", sessionService.get(taskId).getVersion());
            }
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception ignored) {
            return eventJson;
        }
    }
}
