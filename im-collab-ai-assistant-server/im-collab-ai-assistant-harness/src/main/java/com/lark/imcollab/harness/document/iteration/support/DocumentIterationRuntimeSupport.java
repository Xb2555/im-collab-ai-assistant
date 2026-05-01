package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.PendingDocumentIteration;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.ApprovalRepository;
import com.lark.imcollab.common.port.PendingDocumentIterationRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DocumentIterationRuntimeSupport {

    private static final String OWNER_SCENARIO = "SCENARIO_C_DOCUMENT_ITERATION";

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ArtifactRepository artifactRepository;
    private final ApprovalRepository approvalRepository;
    private final PendingDocumentIterationRepository pendingRepository;
    private final PlannerStateStore plannerStateStore;
    private final ObjectMapper objectMapper;

    public DocumentIterationRuntimeSupport(
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            ArtifactRepository artifactRepository,
            ApprovalRepository approvalRepository,
            PendingDocumentIterationRepository pendingRepository,
            PlannerStateStore plannerStateStore,
            ObjectMapper objectMapper
    ) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.artifactRepository = artifactRepository;
        this.approvalRepository = approvalRepository;
        this.pendingRepository = pendingRepository;
        this.plannerStateStore = plannerStateStore;
        this.objectMapper = objectMapper;
    }

    public RuntimeContext start(DocumentIterationRequest request) {
        String taskId = hasText(request.getTaskId()) ? request.getTaskId().trim() : "doc-iter-" + UUID.randomUUID();
        String stepId = taskId + ":document:iteration";
        WorkspaceContext context = request.getWorkspaceContext();
        Instant now = Instant.now();

        Task task = taskRepository.findById(taskId).orElse(Task.builder()
                .taskId(taskId)
                .type(TaskType.WRITE_DOC)
                .createdAt(now)
                .artifacts(new ArrayList<>())
                .steps(new ArrayList<>())
                .build());
        task.setRawInstruction(request.getInstruction());
        task.setClarifiedInstruction(request.getInstruction());
        task.setTaskBrief("文档迭代");
        task.setStatus(TaskStatus.EXECUTING);
        task.setUserId(context == null ? null : context.getSenderOpenId());
        task.setUpdatedAt(now);
        taskRepository.save(task);

        Optional<TaskRecord> existingRecord = plannerStateStore.findTask(taskId);
        plannerStateStore.saveTask(TaskRecord.builder()
                .taskId(taskId)
                .ownerOpenId(context == null ? null : context.getSenderOpenId())
                .source(context == null ? null : context.getInputSource())
                .chatId(context == null ? null : context.getChatId())
                .threadId(context == null ? null : context.getThreadId())
                .title("文档迭代")
                .goal(request.getInstruction())
                .status(TaskStatusEnum.EXECUTING)
                .currentStage(TaskStatusEnum.EXECUTING.name())
                .progress(10)
                .artifactIds(existingRecord.map(TaskRecord::getArtifactIds).orElse(List.of()))
                .riskFlags(existingRecord.map(TaskRecord::getRiskFlags).orElse(List.of()))
                .needUserAction(false)
                .version(existingRecord.map(TaskRecord::getVersion).orElse(0) + 1)
                .createdAt(existingRecord.map(TaskRecord::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build());

        plannerStateStore.saveStep(TaskStepRecord.builder()
                .stepId(stepId)
                .taskId(taskId)
                .type(StepTypeEnum.DOC_EDIT)
                .name("文档迭代")
                .status(StepStatusEnum.RUNNING)
                .inputSummary(request.getInstruction())
                .assignedWorker("document-iteration")
                .dependsOn(List.of())
                .retryCount(0)
                .progress(20)
                .version(1)
                .startedAt(now)
                .build());

        publishEvent(taskId, stepId, TaskEventType.STEP_STARTED, request.getInstruction(), TaskEventTypeEnum.STEP_STARTED);
        return new RuntimeContext(taskId, stepId);
    }

    public void complete(RuntimeContext context, String summary) {
        Instant now = Instant.now();
        taskRepository.findById(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.COMPLETED);
            task.setUpdatedAt(now);
            taskRepository.save(task);
        });
        plannerStateStore.findTask(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.COMPLETED);
            task.setCurrentStage(TaskStatusEnum.COMPLETED.name());
            task.setProgress(100);
            task.setNeedUserAction(false);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(now);
            plannerStateStore.saveTask(task);
        });
        plannerStateStore.findStep(context.getStepId()).ifPresent(step -> {
            step.setStatus(StepStatusEnum.COMPLETED);
            step.setOutputSummary(summary);
            step.setProgress(100);
            step.setEndedAt(now);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        publishEvent(context.getTaskId(), context.getStepId(), TaskEventType.TASK_COMPLETED, summary, TaskEventTypeEnum.TASK_COMPLETED);
        pendingRepository.deleteByTaskId(context.getTaskId());
    }

    public void fail(RuntimeContext context, String reason) {
        Instant now = Instant.now();
        taskRepository.findById(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.FAILED);
            task.setFailReason(reason);
            task.setUpdatedAt(now);
            taskRepository.save(task);
        });
        plannerStateStore.findTask(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.FAILED);
            task.setCurrentStage(TaskStatusEnum.FAILED.name());
            task.setNeedUserAction(false);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(now);
            plannerStateStore.saveTask(task);
        });
        plannerStateStore.findStep(context.getStepId()).ifPresent(step -> {
            step.setStatus(StepStatusEnum.FAILED);
            step.setOutputSummary(reason);
            step.setEndedAt(now);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        approvalRepository.updateStatus(context.getStepId(), ApprovalStatus.FAILED, reason);
        publishEvent(context.getTaskId(), context.getStepId(), TaskEventType.TASK_FAILED, reason, TaskEventTypeEnum.TASK_FAILED);
        pendingRepository.deleteByTaskId(context.getTaskId());
    }

    public void waitForApproval(
            RuntimeContext context,
            DocumentIterationRequest request,
            DocumentEditPlan editPlan,
            Artifact ownedArtifact,
            String operatorOpenId
    ) {
        Instant now = Instant.now();
        taskRepository.findById(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.AWAITING_APPROVAL);
            task.setUpdatedAt(now);
            taskRepository.save(task);
        });
        plannerStateStore.findTask(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.WAITING_APPROVAL);
            task.setCurrentStage(TaskStatusEnum.WAITING_APPROVAL.name());
            task.setNeedUserAction(true);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(now);
            plannerStateStore.saveTask(task);
        });
        plannerStateStore.findStep(context.getStepId()).ifPresent(step -> {
            step.setStatus(StepStatusEnum.WAITING_APPROVAL);
            step.setOutputSummary("等待用户审批文档编辑计划");
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        approvalRepository.save(Approval.builder()
                .approvalId(context.getStepId())
                .taskId(context.getTaskId())
                .stepId(context.getStepId())
                .prompt(editPlan.getReasoningSummary())
                .status(ApprovalStatus.PENDING)
                .createdAt(now)
                .build());
        pendingRepository.save(PendingDocumentIteration.builder()
                .taskId(context.getTaskId())
                .stepId(context.getStepId())
                .operatorOpenId(operatorOpenId)
                .docId(ownedArtifact.getDocumentId())
                .docUrl(ownedArtifact.getExternalUrl())
                .artifactTaskId(ownedArtifact.getTaskId())
                .intentType(editPlan.getIntentType())
                .selector(editPlan.getSelector())
                .editPlan(editPlan)
                .originalRequest(request)
                .createdAt(now)
                .updatedAt(now)
                .build());
        publishEvent(context.getTaskId(), context.getStepId(), TaskEventType.APPROVAL_REQUESTED,
                editPlan.getReasoningSummary(), TaskEventTypeEnum.PLAN_APPROVAL_REQUIRED);
    }

    public RuntimeContext resumeWaiting(String taskId, String stepId) {
        Instant now = Instant.now();
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TaskStatus.EXECUTING);
            task.setUpdatedAt(now);
            taskRepository.save(task);
        });
        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.EXECUTING);
            task.setCurrentStage(TaskStatusEnum.EXECUTING.name());
            task.setNeedUserAction(false);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(now);
            plannerStateStore.saveTask(task);
        });
        plannerStateStore.findStep(stepId).ifPresent(step -> {
            step.setStatus(StepStatusEnum.RUNNING);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        publishEvent(taskId, stepId, TaskEventType.APPROVAL_DECIDED, "resume", TaskEventTypeEnum.PLAN_APPROVED);
        return new RuntimeContext(taskId, stepId);
    }

    public void reject(RuntimeContext context, String feedback) {
        Instant now = Instant.now();
        taskRepository.findById(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.ABORTED);
            task.setFailReason(feedback);
            task.setUpdatedAt(now);
            taskRepository.save(task);
        });
        plannerStateStore.findTask(context.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatusEnum.CANCELLED);
            task.setCurrentStage(TaskStatusEnum.CANCELLED.name());
            task.setNeedUserAction(false);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(now);
            plannerStateStore.saveTask(task);
        });
        plannerStateStore.findStep(context.getStepId()).ifPresent(step -> {
            step.setStatus(StepStatusEnum.SKIPPED);
            step.setOutputSummary(feedback);
            step.setEndedAt(now);
            step.setVersion(step.getVersion() + 1);
            plannerStateStore.saveStep(step);
        });
        publishEvent(context.getTaskId(), context.getStepId(), TaskEventType.TASK_ABORTED, feedback, TaskEventTypeEnum.TASK_CANCELLED);
        pendingRepository.deleteByTaskId(context.getTaskId());
    }

    public Artifact saveSummaryArtifact(
            RuntimeContext context,
            String title,
            String content,
            String docId,
            String docUrl,
            String lastEditedBy
    ) {
        Artifact artifact = Artifact.builder()
                .artifactId(UUID.randomUUID().toString())
                .taskId(context.getTaskId())
                .stepId(context.getStepId())
                .type(ArtifactType.SUMMARY)
                .title(title)
                .content(content)
                .documentId(docId)
                .externalUrl(docUrl)
                .ownerScenario(OWNER_SCENARIO)
                .createdBySystem(true)
                .lastEditedBy(lastEditedBy)
                .lastEditedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        artifactRepository.save(artifact);
        plannerStateStore.saveArtifact(ArtifactRecord.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .sourceStepId(context.getStepId())
                .type(ArtifactTypeEnum.SUMMARY)
                .title(artifact.getTitle())
                .url(artifact.getExternalUrl())
                .preview(artifact.getContent())
                .status("CREATED")
                .version(1)
                .createdAt(artifact.getCreatedAt())
                .updatedAt(artifact.getCreatedAt())
                .build());
        plannerStateStore.findTask(context.getTaskId()).ifPresent(task -> {
            List<String> artifactIds = new ArrayList<>(task.getArtifactIds() == null ? List.of() : task.getArtifactIds());
            artifactIds.add(artifact.getArtifactId());
            task.setArtifactIds(artifactIds);
            task.setVersion(task.getVersion() + 1);
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        publishEvent(context.getTaskId(), context.getStepId(), TaskEventType.ARTIFACT_CREATED, title, TaskEventTypeEnum.ARTIFACT_CREATED);
        return artifact;
    }

    public void touchOwnedDocument(Artifact ownedArtifact, String lastEditedBy) {
        ownedArtifact.setLastEditedBy(lastEditedBy);
        ownedArtifact.setLastEditedAt(Instant.now());
        artifactRepository.save(ownedArtifact);
    }

    public void recordApprovalDecision(String stepId, ApprovalStatus status, String feedback) {
        approvalRepository.updateStatus(stepId, status, feedback);
    }

    public Optional<PendingDocumentIteration> findPending(String taskId) {
        return pendingRepository.findByTaskId(taskId);
    }

    private void publishEvent(
            String taskId,
            String stepId,
            TaskEventType domainType,
            String payload,
            TaskEventTypeEnum runtimeType
    ) {
        Instant now = Instant.now();
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(domainType)
                .payload(payload)
                .occurredAt(now)
                .build());
        plannerStateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .artifactId(null)
                .type(runtimeType)
                .payloadJson(toJson(payload))
                .version(1)
                .createdAt(now)
                .build());
    }

    private String toJson(String payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "\"\"";
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Getter
    @AllArgsConstructor
    public static class RuntimeContext {
        private final String taskId;
        private final String stepId;
    }
}
