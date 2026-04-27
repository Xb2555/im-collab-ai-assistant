package com.lark.imcollab.planner.controller;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.dto.ResumeRequest;
import com.lark.imcollab.common.model.dto.SubmitResultRequest;
import com.lark.imcollab.planner.repository.PlannerStateRepository;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/planner/tasks")
@RequiredArgsConstructor
public class PlannerController {

    private final SupervisorPlannerService supervisorPlannerService;
    private final PlannerSessionService sessionService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateRepository repository;

    @PostMapping("/plan")
    public BaseResponse<PlanTaskSession> plan(@RequestBody PlanRequest request) {
        PlanTaskSession session = supervisorPlannerService.plan(
                request.getRawInstruction(),
                request.getWorkspaceContext(),
                request.getTaskId(),
                request.getUserFeedback()
        );
        return ResultUtils.success(session);
    }

    @PostMapping("/{taskId}/interrupt")
    public BaseResponse<PlanTaskSession> interrupt(@PathVariable String taskId) {
        PlanTaskSession session = supervisorPlannerService.interrupt(taskId);
        return ResultUtils.success(session);
    }

    @PostMapping("/{taskId}/resume")
    public BaseResponse<PlanTaskSession> resume(
            @PathVariable String taskId,
            @RequestBody ResumeRequest request) {
        PlanTaskSession session = supervisorPlannerService.resume(
                taskId,
                request.getFeedback(),
                request.isReplanFromRoot()
        );
        return ResultUtils.success(session);
    }

    @GetMapping("/{taskId}")
    public BaseResponse<PlanTaskSession> getTask(@PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        return ResultUtils.success(session);
    }

    @GetMapping("/{taskId}/cards")
    public BaseResponse<List<UserPlanCard>> getCards(@PathVariable String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        List<UserPlanCard> cards = session.getPlanCards() != null ? session.getPlanCards() : List.of();
        return ResultUtils.success(cards);
    }

    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamEvents(@PathVariable String taskId) {
        return Flux.interval(Duration.ofSeconds(1))
                .flatMap(tick -> {
                    List<String> events = sessionService.getEventJsonList(taskId);
                    return Flux.fromIterable(events);
                })
                .take(Duration.ofMinutes(10));
    }

    @PostMapping("/{taskId}/agent-tasks/{agentTaskId}/submit-result")
    public BaseResponse<TaskResultEvaluation> submitResult(
            @PathVariable String taskId,
            @PathVariable String agentTaskId,
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
    public BaseResponse<TaskResultEvaluation> getEvaluation(
            @PathVariable String taskId,
            @PathVariable String agentTaskId) {
        TaskResultEvaluation evaluation = repository.findEvaluation(taskId, agentTaskId)
                .orElseThrow(() -> new RuntimeException("Evaluation not found for agentTaskId: " + agentTaskId));
        return ResultUtils.success(evaluation);
    }

    @PostMapping("/{taskId}/commands")
    public BaseResponse<PlanTaskSession> command(
            @PathVariable String taskId,
            @RequestBody PlanCommandRequest request) {
        PlanTaskSession session = sessionService.get(taskId);
        sessionService.checkVersion(session, request.getVersion());

        return switch (request.getAction()) {
            case "CONFIRM_EXECUTE" -> {
                session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
                session.setTransitionReason("User confirmed execution");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "EXECUTING");
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
}

