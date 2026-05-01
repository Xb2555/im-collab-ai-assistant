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
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.PlanCardVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.common.model.vo.TaskListVO;
import com.lark.imcollab.common.model.vo.TaskSummaryVO;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.planner.service.AsyncPlannerService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import com.lark.imcollab.planner.config.PlannerProperties;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/planner/tasks")
@Tag(name = "任务规划管理", description = "AI Agent 任务理解、规划、执行、反馈闭环接口")
@RequiredArgsConstructor
public class PlannerController {

    private static final Set<String> SUPPORTED_COMMANDS = Set.of("CONFIRM_EXECUTE", "REPLAN", "CANCEL");

    private final PlannerPlanFacade plannerPlanFacade;
    private final SupervisorPlannerService supervisorPlannerService;
    private final PlannerSessionService sessionService;
    private final TaskRuntimeService taskRuntimeService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateStore repository;
    private final HarnessFacade harnessFacade;
    private final TaskBridgeService taskBridgeService;
    private final AsyncPlannerService asyncPlannerService;
    private final PlannerViewAssembler plannerViewAssembler;
    private final TaskRuntimeViewAssembler taskRuntimeViewAssembler;
    private final LarkOAuthService oauthService;
    private final PlannerProperties plannerProperties;

    @PostMapping("/plan")
    @Operation(summary = "1. 创建任务规划", description = "快速接收任务并在后台生成可执行计划")
    public BaseResponse<PlanPreviewVO> plan(
            @RequestBody PlanRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        PlanRequest ownedRequest = withCurrentUser(request, user.get());
        PlanTaskSession session = asyncPlannerService.submitPlan(
                ownedRequest.getRawInstruction(),
                ownedRequest.getWorkspaceContext(),
                ownedRequest.getTaskId(),
                ownedRequest.getUserFeedback()
        );
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @PostMapping("/plan/sync")
    @Operation(summary = "1.0. 同步创建任务规划", description = "保留旧同步行为，用于调试和回归验证")
    public BaseResponse<PlanPreviewVO> planSync(
            @RequestBody PlanRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        PlanRequest ownedRequest = withCurrentUser(request, user.get());
        PlanTaskSession session = plannerPlanFacade.plan(
                ownedRequest.getRawInstruction(),
                ownedRequest.getWorkspaceContext(),
                ownedRequest.getTaskId(),
                ownedRequest.getUserFeedback()
        );
        return ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
    }

    @GetMapping
    @Operation(summary = "0. 查询我的任务列表", description = "按登录用户查询自己通过 GUI 或 IM 创建的任务")
    public BaseResponse<TaskListVO> listMyTasks(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "status", required = false) List<String> statuses,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor
    ) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        List<TaskStatusEnum> statusFilter = parseStatuses(statuses);
        if (statuses != null && !statuses.isEmpty() && statusFilter.isEmpty()) {
            return error(BusinessCode.PARAMS_ERROR, "Unsupported task status");
        }
        int normalizedLimit = normalizeLimit(limit);
        int offset = parseCursor(cursor);
        List<TaskRecord> tasks = repository.findTasksByOwner(user.get().openId(), statusFilter, offset, normalizedLimit + 1);
        return ResultUtils.success(toTaskList(tasks, offset, normalizedLimit));
    }

    @GetMapping("/active")
    @Operation(summary = "0.1. 查询我的活跃任务", description = "查询当前用户仍在规划、待确认或执行中的任务")
    public BaseResponse<TaskListVO> listMyActiveTasks(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor
    ) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        int normalizedLimit = normalizeLimit(limit);
        int offset = parseCursor(cursor);
        List<TaskRecord> tasks = repository.findTasksByOwner(
                user.get().openId(),
                List.of(TaskStatusEnum.PLANNING, TaskStatusEnum.CLARIFYING, TaskStatusEnum.WAITING_APPROVAL, TaskStatusEnum.EXECUTING),
                offset,
                normalizedLimit + 1
        );
        return ResultUtils.success(toTaskList(tasks, offset, normalizedLimit));
    }

    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "1.1. 订阅任务事件流", description = "SSE 实时推送任务状态变化事件")
    public Flux<String> streamEvents(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return Flux.just(errorJson(BusinessCode.NOT_LOGIN_ERROR, "Not logged in"));
        }
        if (!canAccessTask(taskId, user.get().openId())) {
            return Flux.just(errorJson(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId));
        }
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
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        if (!canAccessTask(taskId, user.get().openId())) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        Optional<PlanTaskSession> session = repository.findSession(taskId);
        return session
                .map(value -> ResultUtils.success(plannerViewAssembler.toPlanPreview(value)))
                .orElseGet(() -> error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId));
    }

    @GetMapping("/{taskId}/runtime")
    @Operation(summary = "4.1. 获取任务运行时快照", description = "查询 Task、Step、Artifact 和标准事件，用于前端卡片和后续场景接入")
    public BaseResponse<TaskDetailVO> getRuntimeSnapshot(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        if (!canAccessTask(taskId, user.get().openId())) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        TaskRuntimeSnapshot snapshot = taskRuntimeService.getSnapshot(taskId);
        if ((snapshot == null || snapshot.getTask() == null) && repository.findSession(taskId).isEmpty()) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        return ResultUtils.success(taskRuntimeViewAssembler.toTaskDetail(snapshot));
    }

    @GetMapping("/{taskId}/cards")
    @Operation(summary = "5. 获取任务卡片列表", description = "查询任务包含的所有卡片（子任务）")
    public BaseResponse<List<PlanCardVO>> getCards(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        if (!canAccessTask(taskId, user.get().openId())) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        Optional<PlanTaskSession> session = repository.findSession(taskId);
        return session
                .map(value -> ResultUtils.success(plannerViewAssembler.toPlanCards(value.getPlanCards())))
                .orElseGet(() -> error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId));
    }

    @PostMapping("/{taskId}/commands")
    @Operation(summary = "6. 执行任务指令（确认执行/重新规划/取消规划）", description = "用户确认执行、重规划或取消任务")
    public BaseResponse<PlanPreviewVO> command(
            @Parameter(description = "任务 ID", required = true, example = "task-123") @PathVariable String taskId,
            @RequestBody PlanCommandRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Optional<LarkFrontendUserResponse> user = currentUser(authorization);
        if (user.isEmpty()) {
            return error(BusinessCode.NOT_LOGIN_ERROR, "Not logged in");
        }
        if (!canAccessTask(taskId, user.get().openId())) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        if (request == null || request.getAction() == null || !SUPPORTED_COMMANDS.contains(request.getAction())) {
            return error(BusinessCode.PARAMS_ERROR, "Unsupported planner command: "
                    + (request == null ? null : request.getAction()));
        }
        Optional<PlanTaskSession> maybeSession = repository.findSession(taskId);
        if (maybeSession.isEmpty()) {
            return error(BusinessCode.NOT_FOUND_ERROR, "Task not found: " + taskId);
        }
        PlanTaskSession session = maybeSession.get();
        sessionService.checkVersion(session, request.getVersion());

        return switch (request.getAction()) {
            case "CONFIRM_EXECUTE" -> {
                taskBridgeService.ensureTask(session);
                session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
                session.setTransitionReason("User confirmed execution");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "EXECUTING");
                taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.EXECUTING, TaskEventTypeEnum.PLAN_APPROVED);
                harnessFacade.startExecution(taskId);
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
            }
            case "REPLAN" -> {
                PlanTaskSession updated = supervisorPlannerService.adjustPlan(taskId, request.getFeedback(), null);
                taskBridgeService.ensureTask(updated);
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(updated));
            }
            case "CANCEL" -> {
                session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
                session.setAborted(true);
                session.setTransitionReason("User cancelled");
                sessionService.save(session);
                sessionService.publishEvent(taskId, "ABORTED");
                taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.ABORTED, TaskEventTypeEnum.TASK_CANCELLED);
                yield ResultUtils.success(plannerViewAssembler.toPlanPreview(session));
            }
            default -> error(BusinessCode.PARAMS_ERROR, "Unsupported planner command: " + request.getAction());
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

    private <T> BaseResponse<T> error(BusinessCode code, String message) {
        return new BaseResponse<>(code.getCode(), null, message);
    }

    private Optional<LarkFrontendUserResponse> currentUser(String authorization) {
        if (!plannerProperties.getAuth().isEnabled()) {
            return Optional.of(testUser());
        }
        return oauthService.findCurrentUserByBusinessToken(extractBearerToken(authorization))
                .filter(user -> hasText(user.openId()));
    }

    private LarkFrontendUserResponse testUser() {
        return new LarkFrontendUserResponse("apifox-test-user", "Apifox Test User", null);
    }

    private String extractBearerToken(String authorization) {
        if (!hasText(authorization)) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }

    private PlanRequest withCurrentUser(PlanRequest request, LarkFrontendUserResponse user) {
        PlanRequest owned = request == null ? new PlanRequest() : request;
        WorkspaceContext context = owned.getWorkspaceContext();
        if (context == null) {
            context = new WorkspaceContext();
            owned.setWorkspaceContext(context);
        }
        context.setSenderOpenId(user.openId());
        context.setInputSource(InputSourceEnum.GUI.name());
        return owned;
    }

    private boolean canAccessTask(String taskId, String ownerOpenId) {
        if (!plannerProperties.getAuth().isEnabled()) {
            return true;
        }
        if (!hasText(taskId) || !hasText(ownerOpenId)) {
            return false;
        }
        Optional<TaskRecord> task = repository.findTask(taskId);
        if (task.isPresent() && hasText(task.get().getOwnerOpenId())) {
            return ownerOpenId.equals(task.get().getOwnerOpenId());
        }
        Optional<PlanTaskSession> session = repository.findSession(taskId);
        String sessionOwner = session
                .map(PlanTaskSession::getInputContext)
                .map(context -> context == null ? null : context.getSenderOpenId())
                .orElse(null);
        return !hasText(sessionOwner) || ownerOpenId.equals(sessionOwner);
    }

    private List<TaskStatusEnum> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<TaskStatusEnum> result = new ArrayList<>();
        for (String raw : statuses) {
            if (!hasText(raw)) {
                continue;
            }
            for (String part : raw.split(",")) {
                if (!hasText(part)) {
                    continue;
                }
                try {
                    result.add(TaskStatusEnum.valueOf(part.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    return List.of();
                }
            }
        }
        return result;
    }

    private TaskListVO toTaskList(List<TaskRecord> tasks, int offset, int limit) {
        List<TaskRecord> safeTasks = tasks == null ? List.of() : tasks;
        boolean hasMore = safeTasks.size() > limit;
        List<TaskSummaryVO> summaries = safeTasks.stream()
                .limit(limit)
                .map(taskRuntimeViewAssembler::toTaskSummary)
                .toList();
        return new TaskListVO(summaries, hasMore ? String.valueOf(offset + limit) : null);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private int parseCursor(String cursor) {
        if (!hasText(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String errorJson(BusinessCode code, String message) {
        return "{\"code\":" + code.getCode() + ",\"data\":null,\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
