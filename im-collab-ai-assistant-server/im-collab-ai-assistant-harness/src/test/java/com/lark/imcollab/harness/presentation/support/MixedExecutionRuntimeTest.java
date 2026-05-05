package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.service.TaskCancellationRegistry;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MixedExecutionRuntimeTest {

    private final TaskCancellationRegistry cancellationRegistry = new TaskCancellationRegistry();

    @Test
    void documentCompletionDoesNotCompleteMixedTaskBeforePresentation() {
        InMemoryRuntime runtime = mixedRuntime();
        DocumentExecutionSupport documentSupport = new DocumentExecutionSupport(
                runtime.taskRepository,
                runtime.eventRepository,
                runtime.artifactRepository,
                runtime.stateStore,
                new ObjectMapper(),
                new TaskCancellationRegistry());

        documentSupport.publishEvent("task-1", null, TaskEventType.STEP_COMPLETED, "doc done");

        assertThat(runtime.steps.get("doc-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.steps.get("ppt-step").getStatus()).isEqualTo(StepStatusEnum.READY);
        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.EXECUTING);
        assertThat(runtime.tasks.get("task-1").getStatus()).isNotEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void presentationCompletionFinishesMixedTaskAndKeepsArtifacts() {
        InMemoryRuntime runtime = mixedRuntime();
        runtime.steps.get("doc-step").setStatus(StepStatusEnum.COMPLETED);
        runtime.artifacts.add(Artifact.builder()
                .artifactId("doc-artifact")
                .taskId("task-1")
                .stepId("doc-step")
                .type(ArtifactType.DOC_LINK)
                .title("技术方案")
                .externalUrl("https://example.feishu.cn/docx/doc-1")
                .createdAt(Instant.now())
                .build());
        PresentationExecutionSupport presentationSupport = new PresentationExecutionSupport(
                runtime.taskRepository, runtime.eventRepository, runtime.artifactRepository, runtime.stateStore, new ObjectMapper(),
                cancellationRegistry);

        presentationSupport.saveArtifact("task-1", "ppt-step", "技术方案汇报", "based on doc", "slides-1", "https://example.feishu.cn/slides/slides-1");
        presentationSupport.publishEvent("task-1", "ppt-step", TaskEventType.STEP_COMPLETED, "ppt done");

        assertThat(runtime.steps.get("ppt-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(runtime.tasks.get("task-1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(runtime.artifacts).extracting(Artifact::getType)
                .contains(ArtifactType.DOC_LINK, ArtifactType.SLIDES_LINK);
        assertThat(runtime.artifactRecords.values()).extracting(ArtifactRecord::getType)
                .contains(com.lark.imcollab.common.model.enums.ArtifactTypeEnum.PPT);
    }

    @Test
    void summaryArtifactCompletesSummaryStepWithoutFinishingMixedTaskEarly() {
        InMemoryRuntime runtime = mixedRuntime();
        runtime.steps.put("summary-step", TaskStepRecord.builder()
                .stepId("summary-step")
                .taskId("task-1")
                .type(StepTypeEnum.SUMMARY)
                .status(StepStatusEnum.READY)
                .version(1)
                .build());
        runtime.steps.get("doc-step").setStatus(StepStatusEnum.COMPLETED);
        DocumentExecutionSupport documentSupport = new DocumentExecutionSupport(
                runtime.taskRepository,
                runtime.eventRepository,
                runtime.artifactRepository,
                runtime.stateStore,
                new ObjectMapper(),
                new TaskCancellationRegistry());

        documentSupport.saveArtifact("task-1", "summary-step", ArtifactType.SUMMARY,
                "项目进展摘要", "摘要内容", null);
        documentSupport.markSummaryStepCompleted("task-1", "摘要内容");

        assertThat(runtime.steps.get("summary-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.artifactRecords.values()).extracting(ArtifactRecord::getType)
                .contains(com.lark.imcollab.common.model.enums.ArtifactTypeEnum.SUMMARY);
        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.EXECUTING);

        PresentationExecutionSupport presentationSupport = new PresentationExecutionSupport(
                runtime.taskRepository, runtime.eventRepository, runtime.artifactRepository, runtime.stateStore, new ObjectMapper(),
                cancellationRegistry);
        presentationSupport.publishEvent("task-1", "ppt-step", TaskEventType.STEP_COMPLETED, "ppt done");

        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(runtime.tasks.get("task-1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void cancelledPresentationExecutionDoesNotProjectArtifactsOrCompletionEvents() {
        InMemoryRuntime runtime = mixedRuntime();
        cancellationRegistry.markCancelled("task-1");
        PresentationExecutionSupport presentationSupport = new PresentationExecutionSupport(
                runtime.taskRepository, runtime.eventRepository, runtime.artifactRepository, runtime.stateStore, new ObjectMapper(),
                cancellationRegistry);

        presentationSupport.saveArtifact("task-1", "ppt-step", "技术方案汇报", "based on doc", "slides-1",
                "https://example.feishu.cn/slides/slides-1");
        presentationSupport.publishEvent("task-1", "ppt-step", TaskEventType.STEP_COMPLETED, "ppt done");
        presentationSupport.publishEvent("task-1", "ppt-step", TaskEventType.TASK_ABORTED, "cancelled");

        assertThat(runtime.artifacts).isEmpty();
        assertThat(runtime.artifactRecords).isEmpty();
        assertThat(runtime.runtimeEvents).extracting(TaskEventRecord::getType)
                .containsExactly(com.lark.imcollab.common.model.enums.TaskEventTypeEnum.TASK_CANCELLED);
        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
        assertThat(runtime.steps.get("ppt-step").getStatus()).isEqualTo(StepStatusEnum.SKIPPED);
    }

    @Test
    void summaryRuntimeEventsAttachToSummaryStepAndDoNotResetDocumentStep() {
        InMemoryRuntime runtime = mixedRuntime();
        runtime.steps.put("summary-step", TaskStepRecord.builder()
                .stepId("summary-step")
                .taskId("task-1")
                .type(StepTypeEnum.SUMMARY)
                .status(StepStatusEnum.READY)
                .version(1)
                .build());
        runtime.steps.get("doc-step").setStatus(StepStatusEnum.COMPLETED);
        runtime.steps.get("ppt-step").setStatus(StepStatusEnum.COMPLETED);
        DocumentExecutionSupport documentSupport = new DocumentExecutionSupport(
                runtime.taskRepository,
                runtime.eventRepository,
                runtime.artifactRepository,
                runtime.stateStore,
                new ObjectMapper(),
                new TaskCancellationRegistry());

        documentSupport.publishEvent("task-1", "summary-step", TaskEventType.STEP_STARTED, "开始生成任务上下文摘要");
        documentSupport.publishEvent("task-1", "summary-step", TaskEventType.STEP_COMPLETED, "任务上下文摘要已完成");

        assertThat(runtime.runtimeEvents).extracting(TaskEventRecord::getStepId)
                .containsExactly("summary-step", "summary-step");
        assertThat(runtime.steps.get("summary-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.steps.get("doc-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.steps.get("ppt-step").getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(runtime.taskRecords.get("task-1").getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
    }

    @Test
    void internalDocumentArtifactsAreNotProjectedToPlannerRuntime() {
        InMemoryRuntime runtime = mixedRuntime();
        DocumentExecutionSupport documentSupport = new DocumentExecutionSupport(
                runtime.taskRepository,
                runtime.eventRepository,
                runtime.artifactRepository,
                runtime.stateStore,
                new ObjectMapper(),
                new TaskCancellationRegistry());

        documentSupport.saveArtifact("task-1", "doc-step", ArtifactType.DOC_OUTLINE,
                "技术方案", "{\"title\":\"技术方案\"}", null);
        documentSupport.saveArtifact("task-1", "doc-step", ArtifactType.DOC_DRAFT,
                "技术方案", "## 草稿", null);
        documentSupport.saveArtifact("task-1", "doc-step", ArtifactType.DOC_LINK,
                "技术方案", null, "doc-1", "https://example.feishu.cn/docx/doc-1");

        assertThat(runtime.artifacts).extracting(Artifact::getType)
                .contains(ArtifactType.DOC_OUTLINE, ArtifactType.DOC_DRAFT, ArtifactType.DOC_LINK);
        assertThat(runtime.artifactRecords.values()).extracting(ArtifactRecord::getType)
                .containsExactly(com.lark.imcollab.common.model.enums.ArtifactTypeEnum.DOC);
        assertThat(runtime.artifactRecords.values()).extracting(ArtifactRecord::getUrl)
                .containsExactly("https://example.feishu.cn/docx/doc-1");
    }

    private InMemoryRuntime mixedRuntime() {
        InMemoryRuntime runtime = new InMemoryRuntime();
        runtime.tasks.put("task-1", Task.builder().taskId("task-1").status(TaskStatus.EXECUTING).build());
        runtime.taskRecords.put("task-1", TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.EXECUTING)
                .currentStage(TaskStatusEnum.EXECUTING.name())
                .version(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        runtime.steps.put("doc-step", TaskStepRecord.builder()
                .stepId("doc-step")
                .taskId("task-1")
                .type(StepTypeEnum.DOC_CREATE)
                .status(StepStatusEnum.READY)
                .version(1)
                .build());
        runtime.steps.put("ppt-step", TaskStepRecord.builder()
                .stepId("ppt-step")
                .taskId("task-1")
                .type(StepTypeEnum.PPT_CREATE)
                .status(StepStatusEnum.READY)
                .version(1)
                .build());
        return runtime;
    }

    private static class InMemoryRuntime {

        private final Map<String, Task> tasks = new HashMap<>();
        private final Map<String, TaskRecord> taskRecords = new HashMap<>();
        private final Map<String, TaskStepRecord> steps = new HashMap<>();
        private final Map<String, ArtifactRecord> artifactRecords = new HashMap<>();
        private final List<Artifact> artifacts = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskEventRecord> runtimeEvents = new ArrayList<>();
        private final TaskRepository taskRepository = new InMemoryTaskRepository(this);
        private final TaskEventRepository eventRepository = new InMemoryTaskEventRepository(this);
        private final ArtifactRepository artifactRepository = new InMemoryArtifactRepository(this);
        private final PlannerStateStore stateStore = new InMemoryPlannerStateStore(this);
    }

    private record InMemoryTaskRepository(InMemoryRuntime runtime) implements TaskRepository {

        @Override
        public void save(Task task) {
            runtime.tasks.put(task.getTaskId(), task);
        }

        @Override
        public Optional<Task> findById(String taskId) {
            return Optional.ofNullable(runtime.tasks.get(taskId));
        }

        @Override
        public void updateStatus(String taskId, TaskStatus status) {
            runtime.tasks.computeIfAbsent(taskId, id -> Task.builder().taskId(id).build()).setStatus(status);
        }
    }

    private record InMemoryTaskEventRepository(InMemoryRuntime runtime) implements TaskEventRepository {

        @Override
        public void save(TaskEvent event) {
            runtime.events.add(event);
        }

        @Override
        public List<TaskEvent> findByTaskId(String taskId) {
            return runtime.events.stream().filter(event -> taskId.equals(event.getTaskId())).toList();
        }
    }

    private record InMemoryArtifactRepository(InMemoryRuntime runtime) implements ArtifactRepository {

        @Override
        public void save(Artifact artifact) {
            runtime.artifacts.add(artifact);
        }

        @Override
        public List<Artifact> findByTaskId(String taskId) {
            return runtime.artifacts.stream().filter(artifact -> taskId.equals(artifact.getTaskId())).toList();
        }

        @Override
        public Optional<Artifact> findByExternalUrl(String externalUrl) {
            return runtime.artifacts.stream().filter(artifact -> externalUrl.equals(artifact.getExternalUrl())).findFirst();
        }

        @Override
        public Optional<Artifact> findByDocumentId(String documentId) {
            return runtime.artifacts.stream().filter(artifact -> documentId.equals(artifact.getDocumentId())).findFirst();
        }

        @Override
        public Optional<Artifact> findOwnedDocumentRecordByExternalUrl(String externalUrl) {
            return findByExternalUrl(externalUrl);
        }

        @Override
        public Optional<Artifact> findOwnedDocumentRecordByDocumentId(String documentId) {
            return findByDocumentId(documentId);
        }

        @Override
        public Optional<Artifact> findLatestDocArtifactByTaskId(String taskId) {
            return runtime.artifacts.stream().filter(artifact -> taskId.equals(artifact.getTaskId())).findFirst();
        }
    }

    private record InMemoryPlannerStateStore(InMemoryRuntime runtime) implements PlannerStateStore {

        @Override
        public void saveSession(PlanTaskSession session) {
        }

        @Override
        public Optional<PlanTaskSession> findSession(String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findConversationTaskId(String conversationKey) {
            return Optional.empty();
        }

        @Override
        public void saveConversationTaskBinding(String conversationKey, String taskId) {
        }

        @Override
        public void appendEvent(com.lark.imcollab.common.model.entity.TaskEvent event) {
        }

        @Override
        public List<String> getEventJsonList(String taskId) {
            return List.of();
        }

        @Override
        public void saveTask(TaskRecord task) {
            runtime.taskRecords.put(task.getTaskId(), task);
        }

        @Override
        public Optional<TaskRecord> findTask(String taskId) {
            return Optional.ofNullable(runtime.taskRecords.get(taskId));
        }

        @Override
        public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<TaskStatusEnum> statuses, int offset, int limit) {
            return List.of();
        }

        @Override
        public void saveStep(TaskStepRecord step) {
            runtime.steps.put(step.getStepId(), step);
        }

        @Override
        public List<TaskStepRecord> findStepsByTaskId(String taskId) {
            return runtime.steps.values().stream().filter(step -> taskId.equals(step.getTaskId())).toList();
        }

        @Override
        public Optional<TaskStepRecord> findStep(String stepId) {
            return Optional.empty();
        }

        @Override
        public void saveArtifact(ArtifactRecord artifact) {
            runtime.artifactRecords.put(artifact.getArtifactId(), artifact);
        }

        @Override
        public List<ArtifactRecord> findArtifactsByTaskId(String taskId) {
            return runtime.artifactRecords.values().stream().filter(artifact -> taskId.equals(artifact.getTaskId())).toList();
        }

        @Override
        public void appendRuntimeEvent(TaskEventRecord event) {
            runtime.runtimeEvents.add(event);
        }

        @Override
        public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) {
            return runtime.runtimeEvents.stream().filter(event -> taskId.equals(event.getTaskId())).toList();
        }

        @Override
        public void saveSubmission(TaskSubmissionResult submission) {
        }

        @Override
        public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
            return Optional.empty();
        }

        @Override
        public void saveEvaluation(TaskResultEvaluation evaluation) {
        }

        @Override
        public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) {
            return Optional.empty();
        }
    }
}
