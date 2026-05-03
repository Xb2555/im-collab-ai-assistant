package com.lark.imcollab.planner.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRuntimeProjectionServiceTest {

    @Test
    void projectStageWritesTaskAndEventBeforePlanIsReady() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("写技术方案")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .inputContext(TaskInputContext.builder()
                        .senderOpenId("ou-user")
                        .inputSource("LARK_PRIVATE_CHAT")
                        .chatId("chat-1")
                        .threadId("thread-1")
                        .build())
                .build();

        service.projectStage(session, TaskEventTypeEnum.INTAKE_ACCEPTED, "已收到");

        assertThat(store.task).isNotNull();
        assertThat(store.task.getOwnerOpenId()).isEqualTo("ou-user");
        assertThat(store.task.getSource()).isEqualTo("LARK_PRIVATE_CHAT");
        assertThat(store.task.getChatId()).isEqualTo("chat-1");
        assertThat(store.task.getThreadId()).isEqualTo("thread-1");
        assertThat(store.task.getStatus().name()).isEqualTo("PLANNING");
        assertThat(store.events).hasSize(1);
        assertThat(store.events.get(0).getType()).isEqualTo(TaskEventTypeEnum.INTAKE_ACCEPTED);
        assertThat(store.streamEvents)
                .extracting(com.lark.imcollab.common.model.entity.TaskEvent::getStatus)
                .containsExactly(TaskEventTypeEnum.INTAKE_ACCEPTED.name());
    }

    @Test
    void projectPlanGraphKeepsDocStepVisibleForScenarioC() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .rawInstruction("写带 Mermaid 的架构文档")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-2")
                .goal("写带 Mermaid 的架构文档")
                .deliverables(List.of("DOC"))
                .steps(List.of(TaskStepRecord.builder()
                        .stepId("doc-step")
                        .taskId("task-2")
                        .type(StepTypeEnum.DOC_CREATE)
                        .status(StepStatusEnum.READY)
                        .assignedWorker("doc-create-worker")
                        .dependsOn(List.of())
                        .build()))
                .build();

        service.projectPlanGraph(session, graph, TaskEventTypeEnum.PLAN_READY);

        assertThat(store.steps).singleElement()
                .extracting(TaskStepRecord::getType)
                .isEqualTo(StepTypeEnum.DOC_CREATE);
        assertThat(store.events).singleElement()
                .extracting(TaskEventRecord::getType)
                .isEqualTo(TaskEventTypeEnum.PLAN_READY);
        assertThat(store.streamEvents)
                .extracting(com.lark.imcollab.common.model.entity.TaskEvent::getStatus)
                .containsExactly(TaskEventTypeEnum.PLAN_READY.name());
    }

    @Test
    void projectPlanGraphKeepsDocStepAndProjectsSummaryStep() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-3")
                .rawInstruction("写文档并输出群内摘要")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-3")
                .goal("写文档并输出群内摘要")
                .deliverables(List.of("DOC", "SUMMARY"))
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("doc-step")
                                .taskId("task-3")
                                .type(StepTypeEnum.DOC_CREATE)
                                .name("生成文档")
                                .status(StepStatusEnum.READY)
                                .assignedWorker("doc-create-worker")
                                .dependsOn(List.of())
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("summary-step")
                                .taskId("task-3")
                                .type(StepTypeEnum.SUMMARY)
                                .name("生成群内摘要")
                                .status(StepStatusEnum.READY)
                                .assignedWorker("summary-worker")
                                .dependsOn(List.of("doc-step"))
                                .build()
                ))
                .build();

        service.projectPlanGraph(session, graph, TaskEventTypeEnum.PLAN_ADJUSTED);

        assertThat(store.steps)
                .extracting(TaskStepRecord::getType)
                .containsExactly(StepTypeEnum.DOC_CREATE, StepTypeEnum.SUMMARY);
        assertThat(store.steps.get(1).getAssignedWorker()).isEqualTo("summary-worker");
        assertThat(store.task.getTitle()).isEqualTo("生成文档和群内摘要");
    }

    @Test
    void planAdjustedTitleReflectsCurrentCompletePlan() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-title")
                .rawInstruction("生成文档和 PPT")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-title")
                .goal("生成文档和 PPT")
                .deliverables(List.of("DOC", "PPT", "SUMMARY"))
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("card-001")
                                .taskId("task-title")
                                .type(StepTypeEnum.DOC_CREATE)
                                .name("生成技术方案文档（含 Mermaid 架构图）")
                                .status(StepStatusEnum.READY)
                                .dependsOn(List.of())
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("card-002")
                                .taskId("task-title")
                                .type(StepTypeEnum.PPT_CREATE)
                                .name("生成配套 PPT 初稿")
                                .status(StepStatusEnum.READY)
                                .dependsOn(List.of("card-001"))
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("card-003")
                                .taskId("task-title")
                                .type(StepTypeEnum.SUMMARY)
                                .name("生成项目进展摘要")
                                .status(StepStatusEnum.READY)
                                .dependsOn(List.of("card-002"))
                                .build()
                ))
                .build();

        service.projectPlanGraph(session, graph, TaskEventTypeEnum.PLAN_ADJUSTED);

        assertThat(store.task.getTitle()).isEqualTo("生成技术方案文档（含 Mermaid 架构图）、配套 PPT 初稿和项目进展摘要");
    }

    @Test
    void snapshotAndProgressIgnoreSupersededSteps() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-4")
                .rawInstruction("写文档并调整计划")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .version(2)
                .build();
        store.saveStep(TaskStepRecord.builder()
                .stepId("old-summary")
                .taskId("task-4")
                .type(StepTypeEnum.SUMMARY)
                .status(StepStatusEnum.SUPERSEDED)
                .assignedWorker("summary-worker")
                .build());
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-4")
                .goal("写文档并调整计划")
                .deliverables(List.of("DOC", "SUMMARY"))
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("doc-step")
                                .taskId("task-4")
                                .type(StepTypeEnum.DOC_CREATE)
                                .status(StepStatusEnum.COMPLETED)
                                .assignedWorker("doc-create-worker")
                                .dependsOn(List.of())
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("summary-step")
                                .taskId("task-4")
                                .type(StepTypeEnum.SUMMARY)
                                .status(StepStatusEnum.READY)
                                .assignedWorker("summary-worker")
                                .dependsOn(List.of("doc-step"))
                                .build()
                ))
                .build();

        service.projectPlanGraph(session, graph, TaskEventTypeEnum.PLAN_ADJUSTED);

        assertThat(store.task.getProgress()).isEqualTo(50);
        assertThat(service.getSnapshot("task-4").getSteps())
                .extracting(TaskStepRecord::getStepId)
                .containsExactly("doc-step", "summary-step");
        assertThat(store.steps)
                .extracting(TaskStepRecord::getStepId)
                .contains("old-summary");
    }

    @Test
    void snapshotPrefersFinalArtifactUrlOverPreviewDraftWithSameTitle() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        store.saveArtifact(ArtifactRecord.builder()
                .artifactId("draft")
                .taskId("task-artifact")
                .type(ArtifactTypeEnum.DOC)
                .title("项目进展文档")
                .preview("{\"title\":\"项目进展文档\"}")
                .createdAt(Instant.now())
                .build());
        store.saveArtifact(ArtifactRecord.builder()
                .artifactId("final")
                .taskId("task-artifact")
                .type(ArtifactTypeEnum.DOC)
                .title("项目进展文档")
                .url("https://example.feishu.cn/docx/final")
                .createdAt(Instant.now())
                .build());

        assertThat(service.getSnapshot("task-artifact").getArtifacts())
                .extracting(ArtifactRecord::getArtifactId)
                .containsExactly("final");
    }

    @Test
    void snapshotReconcilesTaskVersionFromSession() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        TaskRuntimeProjectionService service = new TaskRuntimeProjectionService(store, new ObjectMapper());
        store.saveSession(PlanTaskSession.builder()
                .taskId("task-version")
                .planningPhase(PlanningPhaseEnum.FAILED)
                .version(3)
                .build());
        store.saveTask(TaskRecord.builder()
                .taskId("task-version")
                .status(com.lark.imcollab.common.model.enums.TaskStatusEnum.FAILED)
                .version(2)
                .build());

        assertThat(service.getSnapshot("task-version").getTask().getVersion()).isEqualTo(3);
        assertThat(store.task.getVersion()).isEqualTo(3);
    }

    private static class InMemoryPlannerStateStore implements PlannerStateStore {
        private TaskRecord task;
        private PlanTaskSession session;
        private final List<TaskStepRecord> steps = new ArrayList<>();
        private final List<TaskEventRecord> events = new ArrayList<>();
        private final List<com.lark.imcollab.common.model.entity.TaskEvent> streamEvents = new ArrayList<>();
        private final List<ArtifactRecord> artifacts = new ArrayList<>();

        @Override public void saveTask(TaskRecord task) { this.task = task; }
        @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.ofNullable(task); }
        @Override public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<com.lark.imcollab.common.model.enums.TaskStatusEnum> statuses, int offset, int limit) { return List.of(); }
        @Override public void saveStep(TaskStepRecord step) { steps.removeIf(existing -> existing.getStepId().equals(step.getStepId())); steps.add(step); }
        @Override public List<TaskStepRecord> findStepsByTaskId(String taskId) { return steps; }
        @Override public void appendRuntimeEvent(TaskEventRecord event) { events.add(event); }
        @Override public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) { return events; }

        @Override public void saveSession(PlanTaskSession session) { this.session = session; }
        @Override public Optional<PlanTaskSession> findSession(String taskId) { return Optional.ofNullable(session); }
        @Override public Optional<String> findConversationTaskId(String conversationKey) { return Optional.empty(); }
        @Override public void saveConversationTaskBinding(String conversationKey, String taskId) { }
        @Override public void appendEvent(com.lark.imcollab.common.model.entity.TaskEvent event) { streamEvents.add(event); }
        @Override public List<String> getEventJsonList(String taskId) { return List.of(); }
        @Override public Optional<TaskStepRecord> findStep(String stepId) { return steps.stream().filter(step -> step.getStepId().equals(stepId)).findFirst(); }
        @Override public void saveArtifact(com.lark.imcollab.common.model.entity.ArtifactRecord artifact) { artifacts.add(artifact); }
        @Override public List<com.lark.imcollab.common.model.entity.ArtifactRecord> findArtifactsByTaskId(String taskId) { return artifacts; }
        @Override public void saveSubmission(com.lark.imcollab.common.model.entity.TaskSubmissionResult submission) { }
        @Override public Optional<com.lark.imcollab.common.model.entity.TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) { return Optional.empty(); }
        @Override public void saveEvaluation(com.lark.imcollab.common.model.entity.TaskResultEvaluation evaluation) { }
        @Override public Optional<com.lark.imcollab.common.model.entity.TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) { return Optional.empty(); }
    }
}
