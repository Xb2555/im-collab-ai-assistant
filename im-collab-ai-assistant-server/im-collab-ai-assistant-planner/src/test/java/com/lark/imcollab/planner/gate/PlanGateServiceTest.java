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
