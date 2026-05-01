package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.planner.clarification.ClarificationService;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.gate.PlanGateService;
import com.lark.imcollab.planner.planning.FastPlanBlueprintFactory;
import com.lark.imcollab.planner.planning.PlanRoutingGate;
import com.lark.imcollab.planner.planning.TaskPlanningService;
import com.lark.imcollab.planner.replan.CardPlanPatchMerger;
import com.lark.imcollab.planner.replan.PlanAdjustmentInterpreter;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupervisorPlannerServiceFastPathTest {

    @Test
    void clearCommonPlanSkipsAllLlmAgents() {
        Fixture fixture = new Fixture();
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），准备配套 PPT，并最后输出一段可以直接发到群里的项目进展摘要";

        PlanTaskSession session = fixture.service.plan(instruction, null, "task-1", null);

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(session.getPlanCards())
                .extracting(card -> card.getType())
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT, PlanCardTypeEnum.SUMMARY);
        assertThat(fixture.store.steps.values())
                .extracting(TaskStepRecord::getType)
                .containsExactly(StepTypeEnum.DOC_CREATE, StepTypeEnum.PPT_CREATE, StepTypeEnum.SUMMARY);
        verifyNoInteractions(fixture.supervisorAgent, fixture.intentAgent, fixture.planningAgent);
    }

    @Test
    void groupSummaryAdjustmentSkipsPlanningAgent() {
        Fixture fixture = new Fixture();
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），并准备配套 PPT 初稿";
        fixture.service.plan(instruction, null, "task-2", null);

        PlanTaskSession adjusted = fixture.service.adjustPlan(
                "task-2",
                "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
                null
        );

        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getType())
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT, PlanCardTypeEnum.SUMMARY);
        assertThat(fixture.store.runtimeEvents)
                .extracting(TaskEventRecord::getType)
                .contains(TaskEventTypeEnum.PLAN_ADJUSTED);
        verifyNoInteractions(fixture.supervisorAgent, fixture.intentAgent, fixture.planningAgent);
    }

    @Test
    void oneSentenceSummaryAdjustmentSkipsPlanningAgentAfterExistingSummary() {
        Fixture fixture = new Fixture();
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），并准备配套 PPT 初稿";
        fixture.service.plan(instruction, null, "task-2", null);
        fixture.service.adjustPlan(
                "task-2",
                "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
                null
        );

        PlanTaskSession adjusted = fixture.service.adjustPlan(
                "task-2",
                "再加一条：最后还要输出一句话总结",
                null
        );

        assertThat(adjusted.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getTitle())
                .contains("生成群内项目进展摘要", "生成一句话总结");
        assertThat(adjusted.getPlanCards()).hasSize(4);
        verifyNoInteractions(fixture.supervisorAgent, fixture.intentAgent, fixture.planningAgent);
    }

    @Test
    void bossReportPptAdjustmentSkipsPlanningAgentAfterExistingSummary() {
        Fixture fixture = new Fixture();
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），并准备配套 PPT 初稿";
        fixture.service.plan(instruction, null, "task-2", null);
        fixture.service.adjustPlan(
                "task-2",
                "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
                null
        );

        PlanTaskSession adjusted = fixture.service.adjustPlan(
                "task-2",
                "再加上回复老板生成的ppt",
                null
        );

        assertThat(adjusted.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getTitle())
                .contains("生成老板汇报 PPT");
        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getCardId())
                .doesNotHaveDuplicates();
        assertThat(adjusted.getPlanCards()).hasSize(4);
        verifyNoInteractions(fixture.supervisorAgent, fixture.intentAgent, fixture.planningAgent);
    }

    @Test
    void naturalRemoveSummaryKeepsOriginalDocAndPptPlan() {
        Fixture fixture = new Fixture();
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），并准备配套 PPT 初稿";
        fixture.service.plan(instruction, null, "task-2", null);
        fixture.service.adjustPlan(
                "task-2",
                "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
                null
        );
        fixture.service.adjustPlan(
                "task-2",
                "再加上回复老板生成的ppt",
                null
        );

        PlanTaskSession adjusted = fixture.service.adjustPlan(
                "task-2",
                "我现在不想在群里汇报摘要了",
                null
        );

        assertThat(adjusted.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getTitle())
                .containsExactly(
                        "生成技术方案文档（含 Mermaid 架构图）",
                        "生成配套 PPT 初稿",
                        "生成老板汇报 PPT"
                );
        assertThat(adjusted.getPlanCards())
                .extracting(card -> card.getCardId())
                .doesNotHaveDuplicates();
        verifyNoInteractions(fixture.supervisorAgent, fixture.intentAgent, fixture.planningAgent);
    }

    @Test
    void complexContextCollectionTaskDoesNotUseFastPathEvenWithDocAndPpt() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.supervisorAgent.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantMessage("{\"questions\":[\"请确认要收集哪些群聊和文档？\"]}"));

        PlanTaskSession session = fixture.service.plan(
                "请自动收集最近两周飞书群聊、相关项目文档和历史决策记录，对比三种技术推进方案，最后输出决策分析文档和管理层汇报 PPT",
                null,
                "task-3",
                null
        );

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(session.getClarificationQuestions()).containsExactly("请确认要收集哪些群聊和文档？");
        assertThat(fixture.store.runtimeEvents)
                .extracting(TaskEventRecord::getType)
                .contains(TaskEventTypeEnum.CLARIFICATION_REQUIRED);
        verify(fixture.supervisorAgent).call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(fixture.intentAgent, fixture.planningAgent);
    }

    private static class Fixture {
        private final InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        private final ReactAgent supervisorAgent = mock(ReactAgent.class);
        private final ReactAgent intentAgent = mock(ReactAgent.class);
        private final ReactAgent planningAgent = mock(ReactAgent.class);
        private final SupervisorPlannerService service;

        private Fixture() {
            ObjectMapper objectMapper = new ObjectMapper();
            PlannerSessionService sessionService = new PlannerSessionService(store, new PlannerProperties());
            ExecutionContractFactory contractFactory = new ExecutionContractFactory();
            PlanGraphBuilder graphBuilder = new PlanGraphBuilder();
            TaskRuntimeProjectionService projectionService = new TaskRuntimeProjectionService(store, objectMapper);
            TaskRepository taskRepository = mock(TaskRepository.class);
            when(taskRepository.findById("task-1")).thenReturn(Optional.empty());
            when(taskRepository.findById("task-2")).thenReturn(Optional.empty());
            when(taskRepository.findById("task-3")).thenReturn(Optional.empty());
            TaskRuntimeService runtimeService = new TaskRuntimeService(
                    store,
                    graphBuilder,
                    objectMapper,
                    taskRepository,
                    contractFactory,
                    projectionService
            );
            PlanQualityService qualityService = new PlanQualityService(objectMapper, List.of(), contractFactory);
            TaskPlanningService planningService = new TaskPlanningService(graphBuilder, contractFactory);
            PlannerProperties plannerProperties = new PlannerProperties();
            plannerProperties.getReplan().setPatchIntentModelEnabled(false);
            service = new SupervisorPlannerService(
                    supervisorAgent,
                    intentAgent,
                    planningAgent,
                    sessionService,
                    qualityService,
                    runtimeService,
                    new ClarificationService(),
                    planningService,
                    new PlanGateService(),
                    projectionService,
                    new FastPlanBlueprintFactory(),
                    new PlanRoutingGate(),
                    new PlanAdjustmentInterpreter(planningAgent, objectMapper, plannerProperties),
                    new CardPlanPatchMerger()
            );
        }
    }

    private static class InMemoryPlannerStateStore implements PlannerStateStore {
        private final Map<String, PlanTaskSession> sessions = new LinkedHashMap<>();
        private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
        private final Map<String, TaskStepRecord> steps = new LinkedHashMap<>();
        private final List<TaskEventRecord> runtimeEvents = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();

        @Override public void saveSession(PlanTaskSession session) { sessions.put(session.getTaskId(), session); }
        @Override public Optional<PlanTaskSession> findSession(String taskId) { return Optional.ofNullable(sessions.get(taskId)); }
        @Override public void saveTask(TaskRecord task) { tasks.put(task.getTaskId(), task); }
        @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.ofNullable(tasks.get(taskId)); }
        @Override public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<com.lark.imcollab.common.model.enums.TaskStatusEnum> statuses, int offset, int limit) { return List.of(); }
        @Override public void saveStep(TaskStepRecord step) { steps.put(step.getStepId(), step); }
        @Override public Optional<TaskStepRecord> findStep(String stepId) { return Optional.ofNullable(steps.get(stepId)); }
        @Override public List<TaskStepRecord> findStepsByTaskId(String taskId) { return steps.values().stream().filter(step -> taskId.equals(step.getTaskId())).toList(); }
        @Override public void appendRuntimeEvent(TaskEventRecord event) { runtimeEvents.add(event); }
        @Override public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) { return runtimeEvents.stream().filter(event -> taskId.equals(event.getTaskId())).toList(); }
        @Override public void appendEvent(TaskEvent event) { events.add(event); }
        @Override public List<String> getEventJsonList(String taskId) { return List.of(); }
        @Override public Optional<String> findConversationTaskId(String conversationKey) { return Optional.empty(); }
        @Override public void saveConversationTaskBinding(String conversationKey, String taskId) { }
        @Override public void saveArtifact(ArtifactRecord artifact) { }
        @Override public List<ArtifactRecord> findArtifactsByTaskId(String taskId) { return List.of(); }
        @Override public void saveSubmission(TaskSubmissionResult submission) { }
        @Override public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) { return Optional.empty(); }
        @Override public void saveEvaluation(TaskResultEvaluation evaluation) { }
        @Override public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) { return Optional.empty(); }
    }
}
