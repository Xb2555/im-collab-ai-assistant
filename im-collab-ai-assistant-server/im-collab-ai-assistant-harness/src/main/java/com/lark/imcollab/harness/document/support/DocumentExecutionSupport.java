package com.lark.imcollab.harness.document.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class DocumentExecutionSupport {

    public static final String OUTLINE_TASK_SUFFIX = "generate_outline";
    public static final String SECTIONS_TASK_SUFFIX = "generate_sections";
    public static final String REVIEW_TASK_SUFFIX = "review_doc";
    public static final String WRITE_TASK_SUFFIX = "write_doc_and_sync";

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ArtifactRepository artifactRepository;
    private final PlannerStateStore plannerStateStore;
    private final ObjectMapper objectMapper;
    private final TaskCancellationRegistry cancellationRegistry;

    public DocumentExecutionSupport(
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

    public void saveArtifact(String taskId, String stepId, ArtifactType type, String title, String content, String url) {
        saveArtifact(taskId, stepId, type, title, content, null, url);
    }

    public void saveArtifact(
            String taskId,
            String stepId,
            ArtifactType type,
            String title,
            String content,
            String documentId,
            String url
    ) {
        if (cancellationRegistry.isCancelled(taskId)) {
            return;
        }
        Artifact artifact = Artifact.builder()
                .artifactId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .title(title)
                .content(content)
                .documentId(documentId)
                .externalUrl(url)
                .ownerScenario("SCENARIO_C_DOCUMENT_GENERATION")
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

    public void publishApprovalRequest(String taskId, String stepId, String prompt) {
        if (cancellationRegistry.isCancelled(taskId)) {
            return;
        }
        TaskEvent event = TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(TaskEventType.APPROVAL_REQUESTED)
                .payload(prompt)
                .occurredAt(Instant.now())
                .build();
        eventRepository.save(event);
        syncPlannerEvent(event);
    }

    public <T> T execute(Supplier<T> executor) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Task execution interrupted");
        }
        return executor.get();
    }

    public String subtaskId(String taskId, String suffix) {
        return taskId + ":document:" + suffix;
    }

    public String sectionSubtaskId(String taskId, String sectionKey) {
        return taskId + ":document:section:" + sectionKey;
    }

    public String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private void syncPlannerArtifact(Artifact artifact) {
        if (!isUserVisibleArtifact(artifact)) {
            return;
        }
        String stepId = resolveArtifactStepId(artifact);
        plannerStateStore.saveArtifact(ArtifactRecord.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .sourceStepId(stepId)
                .type(mapArtifactType(artifact.getType()))
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

    private boolean isUserVisibleArtifact(Artifact artifact) {
        if (artifact == null || artifact.getType() == null) {
            return false;
        }
        return switch (artifact.getType()) {
            case DOC_LINK, SLIDES_LINK, WHITEBOARD_LINK, DIAGRAM_SOURCE, SUMMARY -> true;
            case DOC_OUTLINE, DOC_DRAFT -> false;
        };
    }

    private void syncPlannerEvent(TaskEvent event) {
        plannerStateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(event.getEventId())
                .taskId(event.getTaskId())
                .stepId(resolvePlannerStepId(event.getTaskId()))
                .artifactId(null)
                .type(mapEventType(event.getType()))
                .payloadJson(toPayloadJson(event.getPayload()))
                .version(1)
                .createdAt(event.getOccurredAt())
                .build());

        switch (event.getType()) {
            case STEP_STARTED -> markPrimaryStepRunning(event.getTaskId());
            case STEP_COMPLETED -> markPrimaryStepCompleted(event.getTaskId());
            case APPROVAL_REQUESTED -> markPrimaryStepWaitingApproval(event.getTaskId());
            case TASK_COMPLETED -> markTaskCompletedIfAllExecutableStepsDone(event.getTaskId());
            case TASK_FAILED -> markTaskFailed(event.getTaskId(), event.getPayload());
            case TASK_ABORTED -> markTaskCancelled(event.getTaskId(), event.getPayload());
            case STEP_FAILED -> markPrimaryStepFailed(event.getTaskId(), event.getPayload());
            default -> {
            }
        }
    }

    private void markPrimaryStepRunning(String taskId) {
        updatePrimaryDocStep(taskId, step -> {
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
    }

    private void markPrimaryStepWaitingApproval(String taskId) {
        updatePrimaryDocStep(taskId, step -> {
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setStatus(StepStatusEnum.WAITING_APPROVAL);
        });
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.WAITING_APPROVAL);
            task.setCurrentStage(TaskStatusEnum.WAITING_APPROVAL.name());
            task.setNeedUserAction(true);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
    }

    private void markPrimaryStepFailed(String taskId, String reason) {
        String userFacingReason = userFacingFailureReason(reason);
        updatePrimaryDocStep(taskId, step -> {
            step.setStatus(StepStatusEnum.FAILED);
            step.setOutputSummary(blankToNull(userFacingReason));
            step.setEndedAt(Instant.now());
        });
    }

    private void markPrimaryStepCompleted(String taskId) {
        updatePrimaryDocStep(taskId, step -> {
            step.setStatus(StepStatusEnum.COMPLETED);
            step.setProgress(100);
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setEndedAt(Instant.now());
        });
        markTaskCompletedIfAllExecutableStepsDone(taskId);
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
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.COMPLETED);
            task.setCurrentStage(TaskStatusEnum.COMPLETED.name());
            task.setProgress(100);
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        taskRepository.updateStatus(taskId, TaskStatus.COMPLETED);
    }

    private void markTaskFailed(String taskId, String reason) {
        markPrimaryStepFailed(taskId, reason);
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.FAILED);
            task.setCurrentStage(TaskStatusEnum.FAILED.name());
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
    }

    private String userFacingFailureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "文档生成失败，请稍后重试。";
        }
        String trimmed = reason.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (isTechnicalRuntimeError(lower)) {
            return "飞书文档创建失败，请检查 lark-cli 可执行配置、登录状态或文档权限后重试。";
        }
        String firstLine = trimmed.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("文档生成失败，请稍后重试。");
        return firstLine.length() <= 220 ? firstLine : firstLine.substring(0, 220) + "...";
    }

    private boolean isTechnicalRuntimeError(String lowerReason) {
        return lowerReason.contains("powershell")
                || lowerReason.contains(".ps1")
                || lowerReason.contains("command line is too long")
                || lowerReason.contains("commandline is too long")
                || lowerReason.contains("parameterbinding")
                || lowerReason.contains("processbuilder")
                || lowerReason.contains("java.lang.")
                || lowerReason.contains("org.springframework.")
                || lowerReason.contains("exception:");
    }

    private void markTaskCancelled(String taskId, String reason) {
        updatePrimaryDocStep(taskId, step -> {
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
    }

    private void updatePrimaryDocStep(String taskId, java.util.function.Consumer<TaskStepRecord> updater) {
        findPrimaryDocStep(taskId).ifPresent(step -> {
            updater.accept(step);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
    }

    private Optional<TaskStepRecord> findPrimaryDocStep(String taskId) {
        List<TaskStepRecord> steps = plannerStateStore.findStepsByTaskId(taskId);
        return steps.stream()
                .filter(Objects::nonNull)
                .filter(step -> step.getType() == StepTypeEnum.DOC_CREATE
                        || step.getType() == StepTypeEnum.DOC_DRAFT
                        || step.getType() == StepTypeEnum.DOC_EDIT)
                .filter(step -> step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SKIPPED
                        && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .findFirst()
                .or(() -> steps.stream()
                        .filter(Objects::nonNull)
                        .filter(step -> step.getType() == StepTypeEnum.DOC_CREATE
                                || step.getType() == StepTypeEnum.DOC_DRAFT
                                || step.getType() == StepTypeEnum.DOC_EDIT)
                        .findFirst());
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
        return findPrimaryDocStep(taskId).map(TaskStepRecord::getStepId).orElse(null);
    }

    public Optional<String> findSummaryStepId(String taskId) {
        return plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(Objects::nonNull)
                .filter(step -> step.getType() == StepTypeEnum.SUMMARY)
                .map(TaskStepRecord::getStepId)
                .findFirst();
    }

    public void markSummaryStepRunning(String taskId) {
        findSummaryStep(taskId).ifPresent(step -> {
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setStatus(StepStatusEnum.RUNNING);
            step.setProgress(Math.max(step.getProgress(), 10));
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
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

    public void markSummaryStepCompleted(String taskId, String summary) {
        findSummaryStep(taskId).ifPresent(step -> {
            if (step.getStartedAt() == null) {
                step.setStartedAt(Instant.now());
            }
            step.setStatus(StepStatusEnum.COMPLETED);
            step.setProgress(100);
            step.setOutputSummary(shorten(summary, 240));
            step.setEndedAt(Instant.now());
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        markTaskCompletedIfAllExecutableStepsDone(taskId);
    }

    public void markSummaryStepFailed(String taskId, String reason) {
        findSummaryStep(taskId).ifPresent(step -> {
            step.setStatus(StepStatusEnum.FAILED);
            step.setProgress(Math.min(step.getProgress(), 90));
            step.setOutputSummary(shorten(reason, 240));
            step.setEndedAt(Instant.now());
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.FAILED);
            task.setCurrentStage(TaskStatusEnum.FAILED.name());
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        taskRepository.updateStatus(taskId, TaskStatus.FAILED);
    }

    private Optional<TaskStepRecord> findSummaryStep(String taskId) {
        return plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(Objects::nonNull)
                .filter(step -> step.getType() == StepTypeEnum.SUMMARY)
                .filter(step -> step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SKIPPED
                        && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .findFirst()
                .or(() -> plannerStateStore.findStepsByTaskId(taskId).stream()
                        .filter(Objects::nonNull)
                        .filter(step -> step.getType() == StepTypeEnum.SUMMARY)
                        .findFirst());
    }

    private String resolveArtifactStepId(Artifact artifact) {
        if (artifact != null
                && artifact.getStepId() != null
                && !artifact.getStepId().isBlank()
                && plannerStateStore.findStepsByTaskId(artifact.getTaskId()).stream()
                .anyMatch(step -> artifact.getStepId().equals(step.getStepId()))) {
            return artifact.getStepId();
        }
        return artifact == null ? null : resolvePlannerStepId(artifact.getTaskId());
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private boolean isDocumentWorkflowStep(TaskStepRecord step) {
        return step.getType() == StepTypeEnum.DOC_CREATE
                || step.getType() == StepTypeEnum.DOC_DRAFT
                || step.getType() == StepTypeEnum.DOC_EDIT
                || step.getType() == StepTypeEnum.SUMMARY;
    }

    private boolean isTerminalNonSuccess(TaskStepRecord step) {
        return step.getStatus() == StepStatusEnum.SKIPPED
                || step.getStatus() == StepStatusEnum.SUPERSEDED;
    }

    private boolean allExecutableStepsFinished(List<TaskStepRecord> steps) {
        if (steps == null || steps.isEmpty()) {
            return true;
        }
        return steps.stream()
                .filter(Objects::nonNull)
                .filter(step -> step.getStatus() != StepStatusEnum.SUPERSEDED)
                .allMatch(step -> step.getStatus() == StepStatusEnum.COMPLETED
                        || step.getStatus() == StepStatusEnum.SKIPPED);
    }

    private ArtifactTypeEnum mapArtifactType(ArtifactType type) {
        return switch (type) {
            case DOC_OUTLINE, DOC_DRAFT, DOC_LINK -> ArtifactTypeEnum.DOC;
            case SLIDES_LINK -> ArtifactTypeEnum.PPT;
            case WHITEBOARD_LINK -> ArtifactTypeEnum.WHITEBOARD;
            case DIAGRAM_SOURCE -> ArtifactTypeEnum.DIAGRAM;
            case SUMMARY -> ArtifactTypeEnum.SUMMARY;
        };
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

    public static class HumanReviewRequiredException extends RuntimeException {
        private final String prompt;

        public HumanReviewRequiredException(String prompt) {
            super("Human review required");
            this.prompt = prompt;
        }

        public String getPrompt() {
            return prompt;
        }
    }

    public static class RetryExhaustedException extends RuntimeException {
        private final String rawOutput;

        public RetryExhaustedException(String rawOutput) {
            super("Retry exhausted");
            this.rawOutput = rawOutput;
        }

        public String getRawOutput() {
            return rawOutput;
        }
    }
}
