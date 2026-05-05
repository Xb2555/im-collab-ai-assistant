package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.service.TaskCancellationRegistry;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class PresentationExecutionSupport {

    public static final String STORYLINE_TASK_SUFFIX = "build_storyline";
    public static final String OUTLINE_TASK_SUFFIX = "generate_slide_outline";
    public static final String REVIEW_TASK_SUFFIX = "review_presentation";
    public static final String WRITE_TASK_SUFFIX = "write_slides_and_sync";

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ArtifactRepository artifactRepository;
    private final PlannerStateStore plannerStateStore;
    private final ObjectMapper objectMapper;
    private final TaskCancellationRegistry cancellationRegistry;

    public PresentationExecutionSupport(
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            ArtifactRepository artifactRepository,
            PlannerStateStore plannerStateStore,
            ObjectMapper objectMapper,
            TaskCancellationRegistry cancellationRegistry) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.artifactRepository = artifactRepository;
        this.plannerStateStore = plannerStateStore;
        this.objectMapper = objectMapper;
        this.cancellationRegistry = cancellationRegistry;
    }

    public Task loadTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public List<Artifact> findArtifacts(String taskId) {
        return artifactRepository.findByTaskId(taskId);
    }

    public void saveArtifact(
            String taskId,
            String stepId,
            String title,
            String content,
            String presentationId,
            String url
    ) {
        if (cancellationRegistry.isCancelled(taskId)) {
            return;
        }
        Artifact artifact = Artifact.builder()
                .artifactId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(ArtifactType.SLIDES_LINK)
                .title(title)
                .content(content)
                .documentId(presentationId)
                .externalUrl(url)
                .ownerScenario("SCENARIO_D_PRESENTATION_GENERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();
        artifactRepository.save(artifact);
        syncPlannerArtifact(artifact);
    }

    public void publishEvent(String taskId, String stepId, TaskEventType type) {
        publishEvent(taskId, stepId, type, null);
    }

    public void publishEvent(String taskId, String stepId, TaskEventType type, String payload) {
        if (cancellationRegistry.isCancelled(taskId) && type != TaskEventType.TASK_ABORTED) {
            return;
        }
        TaskEvent event = TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
        eventRepository.save(event);
        syncPlannerEvent(event);
    }

    public String subtaskId(String taskId, String suffix) {
        return taskId + ":presentation:" + suffix;
    }

    public String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    public Optional<TaskStepRecord> findPptStep(String taskId) {
        List<TaskStepRecord> steps = plannerStateStore.findStepsByTaskId(taskId);
        return steps.stream()
                .filter(Objects::nonNull)
                .filter(this::isPptStep)
                .filter(step -> step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SKIPPED
                        && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .findFirst()
                .or(() -> steps.stream()
                        .filter(Objects::nonNull)
                        .filter(this::isPptStep)
                        .findFirst());
    }

    private void syncPlannerArtifact(Artifact artifact) {
        String stepId = artifact.getStepId() == null || artifact.getStepId().isBlank()
                ? resolvePlannerStepId(artifact.getTaskId())
                : artifact.getStepId();
        plannerStateStore.saveArtifact(ArtifactRecord.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .sourceStepId(stepId)
                .type(ArtifactTypeEnum.PPT)
                .title(artifact.getTitle())
                .url(blankToNull(artifact.getExternalUrl()))
                .preview(blankToNull(artifact.getContent()))
                .status("CREATED")
                .version(1)
                .createdAt(artifact.getCreatedAt())
                .updatedAt(artifact.getCreatedAt())
                .build());

        plannerStateStore.findTask(artifact.getTaskId()).ifPresent(task -> {
            List<String> artifactIds = new ArrayList<>(task.getArtifactIds() == null ? List.of() : task.getArtifactIds());
            if (!artifactIds.contains(artifact.getArtifactId())) {
                artifactIds.add(artifact.getArtifactId());
            }
            task.setArtifactIds(artifactIds);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
    }

    private void syncPlannerEvent(TaskEvent event) {
        String stepId = event.getStepId() == null || event.getStepId().isBlank()
                ? resolvePlannerStepId(event.getTaskId())
                : event.getStepId();
        plannerStateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(event.getEventId())
                .taskId(event.getTaskId())
                .stepId(stepId)
                .artifactId(null)
                .type(mapEventType(event.getType()))
                .payloadJson(toPayloadJson(event.getPayload()))
                .version(1)
                .createdAt(event.getOccurredAt())
                .build());

        switch (event.getType()) {
            case STEP_STARTED -> markPptStepRunning(event.getTaskId());
            case STEP_COMPLETED -> {
                if (isPlannerPptStepEvent(event.getTaskId(), event.getStepId())) {
                    markPptStepCompleted(event.getTaskId());
                }
            }
            case STEP_FAILED -> markPptStepFailed(event.getTaskId(), event.getPayload());
            case TASK_COMPLETED -> markTaskCompletedIfAllExecutableStepsDone(event.getTaskId());
            case TASK_FAILED -> markTaskFailed(event.getTaskId(), event.getPayload());
            case TASK_ABORTED -> markTaskCancelled(event.getTaskId(), event.getPayload());
            default -> {
            }
        }
    }

    public void markPptStepRunning(String taskId) {
        updatePptStep(taskId, step -> {
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setStatus(StepStatusEnum.RUNNING);
            step.setProgress(Math.max(step.getProgress(), 10));
        });
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.EXECUTING);
            task.setCurrentStage(TaskStatusEnum.EXECUTING.name());
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        taskRepository.updateStatus(taskId, TaskStatus.EXECUTING);
    }

    public void markPptStepCompleted(String taskId) {
        updatePptStep(taskId, step -> {
            step.setStatus(StepStatusEnum.COMPLETED);
            step.setProgress(100);
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setEndedAt(Instant.now());
        });
        markTaskCompletedIfAllExecutableStepsDone(taskId);
    }

    public void markPptStepFailed(String taskId, String reason) {
        updatePptStep(taskId, step -> {
            step.setStatus(StepStatusEnum.FAILED);
            step.setProgress(Math.min(step.getProgress(), 90));
            step.setOutputSummary(blankToNull(reason));
            step.setEndedAt(Instant.now());
        });
    }

    public void markTaskFailed(String taskId, String reason) {
        markPptStepFailed(taskId, reason);
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.FAILED);
            task.setCurrentStage(TaskStatusEnum.FAILED.name());
            task.setProgress(Math.min(task.getProgress(), 90));
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        taskRepository.updateStatus(taskId, TaskStatus.FAILED);
    }

    public void markTaskCancelled(String taskId, String reason) {
        updatePptStep(taskId, step -> {
            step.setStatus(StepStatusEnum.SKIPPED);
            step.setOutputSummary(blankToNull(reason));
            step.setEndedAt(Instant.now());
        });
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.CANCELLED);
            task.setCurrentStage(TaskStatusEnum.CANCELLED.name());
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        taskRepository.updateStatus(taskId, TaskStatus.ABORTED);
    }

    public void markTaskCompletedIfAllExecutableStepsDone(String taskId) {
        List<TaskStepRecord> executableSteps = plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(Objects::nonNull)
                .filter(this::isExecutableStep)
                .filter(step -> step.getStatus() != StepStatusEnum.SKIPPED
                        && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .toList();
        if (executableSteps.isEmpty() || executableSteps.stream().anyMatch(step -> step.getStatus() != StepStatusEnum.COMPLETED)) {
            return;
        }
        plannerStateStore.findTask(taskId).ifPresent(task -> completePlannerTask(task, taskId));
        taskRepository.updateStatus(taskId, TaskStatus.COMPLETED);
    }

    private void updatePptStep(String taskId, java.util.function.Consumer<TaskStepRecord> updater) {
        findPptStep(taskId).ifPresent(step -> {
            updater.accept(step);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
    }

    private void completePlannerTask(TaskRecord task, String taskId) {
        task.setStatus(TaskStatusEnum.COMPLETED);
        task.setCurrentStage(TaskStatusEnum.COMPLETED.name());
        task.setProgress(100);
        task.setNeedUserAction(false);
        task.setUpdatedAt(Instant.now());
        plannerStateStore.saveTask(task);
    }

    private boolean isPptStep(TaskStepRecord step) {
        return step.getType() == StepTypeEnum.PPT_CREATE || step.getType() == StepTypeEnum.PPT_OUTLINE;
    }

    private boolean isPlannerPptStepEvent(String taskId, String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return false;
        }
        return findPptStep(taskId)
                .map(TaskStepRecord::getStepId)
                .filter(stepId::equals)
                .isPresent();
    }

    private boolean isExecutableStep(TaskStepRecord step) {
        return step.getType() == StepTypeEnum.DOC_CREATE
                || step.getType() == StepTypeEnum.DOC_DRAFT
                || step.getType() == StepTypeEnum.DOC_EDIT
                || step.getType() == StepTypeEnum.PPT_CREATE
                || step.getType() == StepTypeEnum.PPT_OUTLINE
                || step.getType() == StepTypeEnum.WHITEBOARD_CREATE
                || step.getType() == StepTypeEnum.SUMMARY;
    }

    private String resolvePlannerStepId(String taskId) {
        return findPptStep(taskId).map(TaskStepRecord::getStepId).orElse(null);
    }

    private TaskEventTypeEnum mapEventType(TaskEventType type) {
        return switch (type) {
            case PLAN_READY -> TaskEventTypeEnum.PLAN_READY;
            case STEP_STARTED -> TaskEventTypeEnum.STEP_STARTED;
            case STEP_COMPLETED -> TaskEventTypeEnum.STEP_COMPLETED;
            case STEP_FAILED -> TaskEventTypeEnum.STEP_FAILED;
            case STEP_RETRYING -> TaskEventTypeEnum.STEP_RETRY_SCHEDULED;
            case APPROVAL_REQUESTED -> TaskEventTypeEnum.PLAN_APPROVAL_REQUIRED;
            case ARTIFACT_CREATED -> TaskEventTypeEnum.ARTIFACT_CREATED;
            case TASK_COMPLETED -> TaskEventTypeEnum.TASK_COMPLETED;
            case TASK_FAILED -> TaskEventTypeEnum.TASK_FAILED;
            case TASK_ABORTED -> TaskEventTypeEnum.TASK_CANCELLED;
            case TASK_CREATED, PLANNING_STARTED, APPROVAL_DECIDED -> TaskEventTypeEnum.STEP_READY;
        };
    }

    private String toPayloadJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "\"" + payload.replace("\"", "\\\"") + "\"";
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
