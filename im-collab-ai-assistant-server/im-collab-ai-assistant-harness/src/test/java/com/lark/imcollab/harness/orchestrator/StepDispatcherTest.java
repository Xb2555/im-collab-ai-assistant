package com.lark.imcollab.harness.orchestrator;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import com.lark.imcollab.harness.summary.service.SummaryExecutionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepDispatcherTest {

    private final DocumentExecutionService documentExecutionService = mock(DocumentExecutionService.class);
    private final PresentationExecutionService presentationExecutionService = mock(PresentationExecutionService.class);
    private final SummaryExecutionService summaryExecutionService = mock(SummaryExecutionService.class);
    private final PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
    private final StepDispatcher dispatcher = new StepDispatcher(
            mock(TaskRepository.class),
            mock(StepRepository.class),
            mock(TaskEventRepository.class),
            documentExecutionService,
            presentationExecutionService,
            summaryExecutionService,
            plannerStateStore
    );

    @Test
    void pureSummaryBypassesDocumentWorkflow() {
        Task task = Task.builder()
                .taskId("task-1")
                .type(TaskType.WRITE_DOC)
                .executionContract(ExecutionContract.builder()
                        .allowedArtifacts(List.of("SUMMARY"))
                        .build())
                .build();

        dispatcher.dispatch(task);

        verify(summaryExecutionService).execute("task-1");
        verify(documentExecutionService, never()).execute("task-1");
        verify(presentationExecutionService, never()).execute("task-1");
    }

    @Test
    void mixedTaskRunsSummaryAfterDocumentAndPresentationComplete() {
        Task task = Task.builder()
                .taskId("task-1")
                .type(TaskType.MIXED)
                .executionContract(ExecutionContract.builder()
                        .allowedArtifacts(List.of("DOC", "PPT", "SUMMARY"))
                        .build())
                .build();
        when(plannerStateStore.findStepsByTaskId("task-1")).thenReturn(List.of(
                step(StepTypeEnum.DOC_CREATE, StepStatusEnum.COMPLETED),
                step(StepTypeEnum.PPT_CREATE, StepStatusEnum.COMPLETED),
                step(StepTypeEnum.SUMMARY, StepStatusEnum.READY)
        ));

        dispatcher.dispatch(task);

        verify(documentExecutionService).execute("task-1");
        verify(presentationExecutionService).execute("task-1");
        verify(summaryExecutionService).execute("task-1");
    }

    private TaskStepRecord step(StepTypeEnum type, StepStatusEnum status) {
        return TaskStepRecord.builder()
                .taskId("task-1")
                .stepId(type.name())
                .type(type)
                .status(status)
                .build();
    }
}
