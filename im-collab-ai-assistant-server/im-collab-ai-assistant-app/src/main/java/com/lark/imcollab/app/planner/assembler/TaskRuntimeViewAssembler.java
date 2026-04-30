package com.lark.imcollab.app.planner.assembler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.TaskActionVO;
import com.lark.imcollab.common.model.vo.TaskArtifactVO;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.common.model.vo.TaskEventVO;
import com.lark.imcollab.common.model.vo.TaskStepVO;
import com.lark.imcollab.common.model.vo.TaskSummaryVO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskRuntimeViewAssembler {

    private final ObjectMapper objectMapper;

    public TaskRuntimeViewAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TaskDetailVO toTaskDetail(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new TaskDetailVO(
                toTaskSummary(snapshot.getTask()),
                activeSteps(snapshot.getSteps()).stream().map(this::toTaskStep).toList(),
                defaultList(snapshot.getArtifacts()).stream().map(this::toTaskArtifact).toList(),
                defaultList(snapshot.getEvents()).stream().map(this::toTaskEvent).toList(),
                resolveActions(snapshot.getTask(), activeSteps(snapshot.getSteps()))
        );
    }

    public TaskSummaryVO toTaskSummary(TaskRecord task) {
        if (task == null) {
            return null;
        }
        return new TaskSummaryVO(
                task.getTaskId(),
                task.getVersion(),
                task.getTitle(),
                task.getGoal(),
                enumName(task.getStatus()),
                task.getCurrentStage(),
                task.getProgress(),
                task.isNeedUserAction(),
                defaultList(task.getRiskFlags()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskStepVO toTaskStep(TaskStepRecord step) {
        return new TaskStepVO(
                step.getStepId(),
                step.getName(),
                enumName(step.getType()),
                enumName(step.getStatus()),
                step.getInputSummary(),
                step.getOutputSummary(),
                step.getProgress(),
                step.getRetryCount(),
                step.getAssignedWorker(),
                step.getStartedAt(),
                step.getEndedAt()
        );
    }

    private TaskArtifactVO toTaskArtifact(ArtifactRecord artifact) {
        return new TaskArtifactVO(
                artifact.getArtifactId(),
                enumName(artifact.getType()),
                artifact.getTitle(),
                artifact.getUrl(),
                artifact.getPreview(),
                artifact.getStatus(),
                artifact.getCreatedAt()
        );
    }

    private TaskEventVO toTaskEvent(TaskEventRecord event) {
        return new TaskEventVO(
                event.getEventId(),
                event.getVersion(),
                enumName(event.getType()),
                event.getStepId(),
                resolveEventMessage(event),
                event.getCreatedAt()
        );
    }

    private TaskActionVO resolveActions(TaskRecord task, List<TaskStepRecord> steps) {
        TaskStatusEnum status = task == null ? null : task.getStatus();
        boolean canCancel = status != TaskStatusEnum.COMPLETED && status != TaskStatusEnum.CANCELLED;
        boolean canReplan = status == TaskStatusEnum.EXECUTING
                || status == TaskStatusEnum.FAILED
                || status == TaskStatusEnum.WAITING_APPROVAL;
        boolean canResume = task != null && task.isNeedUserAction();
        boolean canInterrupt = status == TaskStatusEnum.EXECUTING
                && defaultList(steps).stream().anyMatch(step -> step.getStatus() == StepStatusEnum.RUNNING);
        boolean canConfirm = status == TaskStatusEnum.WAITING_APPROVAL;
        return new TaskActionVO(canConfirm, canReplan, canCancel, canResume, canInterrupt);
    }

    private String resolveEventMessage(TaskEventRecord event) {
        String payload = readPayloadMessage(event.getPayloadJson());
        if (payload != null && !payload.isBlank()) {
            return payload;
        }
        return switch (event.getType()) {
            case TASK_RECEIVED -> "任务已接收";
            case CLARIFICATION_REQUESTED -> "等待用户补充信息";
            case CLARIFICATION_ANSWERED -> "已收到用户补充信息";
            case INTENT_READY -> "任务意图已确认";
            case PLAN_READY -> "任务计划已生成";
            case PLAN_ADJUSTED -> "任务计划已调整";
            case PLAN_APPROVAL_REQUIRED -> "等待用户确认计划";
            case PLAN_APPROVED -> "用户已确认开始执行";
            case STEP_READY -> "步骤已就绪";
            case STEP_STARTED -> "步骤开始执行";
            case STEP_COMPLETED -> "步骤执行完成";
            case STEP_FAILED -> "步骤执行失败";
            case STEP_RETRY_SCHEDULED -> "步骤已进入重试";
            case ARTIFACT_CREATED -> "已生成产物";
            case TASK_COMPLETED -> "任务已完成";
            case TASK_FAILED -> "任务执行失败";
            case TASK_CANCELLED -> "任务已取消";
            case INTAKE_ACCEPTED -> "已收到用户指令";
            case INTENT_ROUTING -> "正在理解用户意图";
            case CLARIFICATION_REQUIRED -> "需要补充关键信息";
            case PLANNING_STARTED -> "正在生成任务计划";
            case PLAN_GATE_CHECKING -> "正在检查计划可执行性";
            case PLAN_FAILED -> "任务计划生成失败";
        };
    }

    private String readPayloadMessage(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank() || "null".equals(payloadJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node.isTextual()) {
                return node.asText();
            }
        } catch (Exception ignored) {
            // Keep raw payload when it is already human-readable text.
        }
        return payloadJson;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<TaskStepRecord> activeSteps(List<TaskStepRecord> steps) {
        return defaultList(steps).stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .toList();
    }
}
