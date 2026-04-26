package com.lark.imcollab.planner.controller;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.planner.controller.dto.PlanRequest;
import com.lark.imcollab.planner.controller.dto.ResumeRequest;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
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

    @PostMapping("/plan")
    public BaseResponse<PlanTaskSession> plan(@RequestBody PlanRequest request) {
        PlanTaskSession session = supervisorPlannerService.plan(
                request.getRawInstruction(),
                request.getContext(),
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
}
