package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.exception.RetryNotAllowedException;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PlannerRetryService {

    private final PlannerStateStore stateStore;
    private final PlannerSessionService sessionService;
    private final ObjectMapper objectMapper;

    public PlannerRetryService(
            PlannerStateStore stateStore,
            PlannerSessionService sessionService,
            ObjectMapper objectMapper
    ) {
        this.stateStore = stateStore;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public boolean isRetryable(String taskId, PlanTaskSession session) {
        if (session != null && session.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            return true;
        }
        return stateStore.findTask(taskId)
                .map(task -> task.getStatus() == TaskStatusEnum.FAILED)
                .orElse(false);
    }

    public PlanTaskSession prepareRetry(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (!isRetryable(taskId, session)) {
            throw new RetryNotAllowedException("当前任务不是失败状态，不需要重试。");
        }

        session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        session.setTransitionReason("Retrying failed task");
        session.setAborted(false);
        sessionService.save(session);

        List<TaskStepRecord> steps = stateStore.findStepsByTaskId(taskId);
        String retryStepId = resetFailedOrRunningSteps(steps);
        updateTaskForRetry(taskId, session.getVersion());
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_RETRY_SCHEDULED, retryStepId,
                "已进入重试，将从失败步骤继续执行。");
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.PLAN_APPROVED, null,
                "用户请求重试失败任务。");
        sessionService.publishEvent(taskId, "EXECUTING");
        return session;
    }

    private String resetFailedOrRunningSteps(List<TaskStepRecord> steps) {
        String firstRetryStepId = null;
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        for (TaskStepRecord step : steps) {
            if (step == null || !shouldReset(step.getStatus())) {
                continue;
            }
            if (firstRetryStepId == null) {
                firstRetryStepId = step.getStepId();
            }
            step.setStatus(StepStatusEnum.READY);
            step.setRetryCount(step.getRetryCount() + 1);
            step.setProgress(0);
            step.setStartedAt(null);
            step.setEndedAt(null);
            step.setVersion(step.getVersion() + 1);
            stateStore.saveStep(step);
        }
        return firstRetryStepId;
    }

    private boolean shouldReset(StepStatusEnum status) {
        return status == StepStatusEnum.FAILED || status == StepStatusEnum.RUNNING;
    }

    private void updateTaskForRetry(String taskId, int version) {
        stateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.EXECUTING);
            task.setCurrentStage(PlanningPhaseEnum.EXECUTING.name());
            task.setNeedUserAction(false);
            task.setVersion(version);
            task.setUpdatedAt(Instant.now());
            stateStore.saveTask(task);
        });
    }

    private void appendRuntimeEvent(
            String taskId,
            int version,
            TaskEventTypeEnum eventType,
            String stepId,
            Object payload
    ) {
        stateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(eventType)
                .payloadJson(toJson(payload))
                .version(version)
                .createdAt(Instant.now())
                .build());
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return String.valueOf(payload);
        }
    }
}
