package com.lark.imcollab.planner.gate;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGateServiceTest {

    private final PlanGateService service = new PlanGateService();

    @Test
    void acceptsExecutableDocPlanWithExecutionContract() {
        TaskPlanGraph graph = graph(List.of(step("doc-1", List.of())));
        ExecutionContract contract = ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("写文档")
                .clarifiedInstruction("写技术文档")
                .allowedArtifacts(List.of("DOC"))
                .build();

        PlanGateResult result = service.check(graph, contract);

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void rejectsMissingContractAndDependencyCycle() {
        TaskStepRecord first = step("a", List.of("b"));
        TaskStepRecord second = step("b", List.of("a"));

        PlanGateResult result = service.check(graph(List.of(first, second)), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("execution contract is required", "step dependencies contain a cycle");
    }

    @Test
    void rejectsStepWithoutWorkerAndMissingDeliverables() {
        TaskStepRecord step = step("doc-1", List.of());
        step.setAssignedWorker("");
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .steps(List.of(step))
                .deliverables(List.of())
                .build();

        PlanGateResult result = service.check(graph, ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("写文档")
                .clarifiedInstruction("写文档")
                .allowedArtifacts(List.of("DOC"))
                .build());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("plan deliverables are required", "step doc-1 missing assignedWorker");
    }

    @Test
    void rejectsUnsupportedDeliverableAndStepType() {
        TaskStepRecord step = step("whiteboard-1", List.of());
        step.setType(StepTypeEnum.WHITEBOARD_CREATE);
        step.setAssignedWorker("whiteboard-worker");
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("生成白板")
                .deliverables(List.of("WHITEBOARD"))
                .steps(List.of(step))
                .build();

        PlanGateResult result = service.check(graph, ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("生成白板")
                .clarifiedInstruction("生成白板")
                .allowedArtifacts(List.of("WHITEBOARD"))
                .build());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("unsupported deliverable: WHITEBOARD");
        assertThat(result.reasons()).contains("step whiteboard-1 has unsupported type");
    }

    @Test
    void rejectsWorkerMismatchAndContractOverflow() {
        TaskStepRecord step = step("ppt-1", List.of());
        step.setType(StepTypeEnum.PPT_CREATE);
        step.setAssignedWorker("doc-create-worker");
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("生成 PPT")
                .deliverables(List.of("PPT"))
                .steps(List.of(step))
                .build();

        PlanGateResult result = service.check(graph, ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("写文档")
                .clarifiedInstruction("写文档")
                .allowedArtifacts(List.of("DOC"))
                .build());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("deliverable is outside execution contract: PPT");
        assertThat(result.reasons()).contains("step ppt-1 worker does not match capability");
    }

    @Test
    void rejectsStandaloneSummaryExecutionInCurrentHarness() {
        TaskStepRecord step = step("summary-1", List.of());
        step.setType(StepTypeEnum.SUMMARY);
        step.setAssignedWorker("summary-worker");
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("生成一段摘要")
                .deliverables(List.of("SUMMARY"))
                .steps(List.of(step))
                .build();

        PlanGateResult result = service.check(graph, ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("写一段摘要")
                .clarifiedInstruction("写一段摘要")
                .allowedArtifacts(List.of("SUMMARY"))
                .build());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains(
                "standalone SUMMARY steps are not executable in the current harness; merge the summary into a DOC or split it into a separate future capability");
    }

    @Test
    void rejectsMultipleDocStepsInOneExecutionRun() {
        TaskStepRecord docOne = step("doc-1", List.of());
        TaskStepRecord docTwo = step("doc-2", List.of("doc-1"));
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("生成文档和风险清单")
                .deliverables(List.of("DOC"))
                .steps(List.of(docOne, docTwo))
                .build();

        PlanGateResult result = service.check(graph, ExecutionContract.builder()
                .taskId("task-1")
                .rawInstruction("写文档")
                .clarifiedInstruction("写文档并补风险清单")
                .allowedArtifacts(List.of("DOC"))
                .build());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains(
                "multiple DOC steps are not executable in one run; merge extra sections into the main DOC");
    }

    private TaskPlanGraph graph(List<TaskStepRecord> steps) {
        return TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("写技术文档")
                .deliverables(List.of("DOC"))
                .steps(steps)
                .build();
    }

    private TaskStepRecord step(String stepId, List<String> dependsOn) {
        return TaskStepRecord.builder()
                .stepId(stepId)
                .taskId("task-1")
                .type(StepTypeEnum.DOC_CREATE)
                .name("写文档")
                .status(StepStatusEnum.READY)
                .assignedWorker("doc-create-worker")
                .dependsOn(dependsOn)
                .build();
    }
}
