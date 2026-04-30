package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPatchMergerTest {

    private final PlanPatchMerger merger = new PlanPatchMerger();

    @Test
    void supersedesOnlyUnfinishedStepsAndAppendsNewStep() {
        TaskStepRecord completed = step("done", StepStatusEnum.COMPLETED);
        TaskStepRecord ready = step("old", StepStatusEnum.READY);
        TaskStepRecord added = step("new", StepStatusEnum.READY);
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .steps(List.of(completed, ready))
                .build();

        TaskPlanGraph result = merger.merge(graph, new PlanPatch(List.of(added), List.of("done", "old"), "replace old step"));

        assertThat(result.getSteps()).hasSize(3);
        assertThat(result.getSteps()).filteredOn(step -> step.getStepId().equals("done"))
                .singleElement()
                .extracting(TaskStepRecord::getStatus)
                .isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(result.getSteps()).filteredOn(step -> step.getStepId().equals("old"))
                .singleElement()
                .extracting(TaskStepRecord::getStatus)
                .isEqualTo(StepStatusEnum.SUPERSEDED);
        assertThat(result.getSteps()).filteredOn(step -> step.getStepId().equals("new")).hasSize(1);
    }

    private TaskStepRecord step(String stepId, StepStatusEnum status) {
        return TaskStepRecord.builder()
                .stepId(stepId)
                .taskId("task-1")
                .type(StepTypeEnum.DOC_CREATE)
                .status(status)
                .assignedWorker("doc-create-worker")
                .build();
    }
}
