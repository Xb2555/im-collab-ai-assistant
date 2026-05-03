package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.port.ApprovalRepository;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.PendingDocumentIterationRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIterationRuntimeSupportTest {

    @Test
    void failClearsPendingAndMarksApprovalFailed() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        PendingDocumentIterationRepository pendingRepository = mock(PendingDocumentIterationRepository.class);
        PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
        when(taskRepository.findById("doc-iter-1")).thenReturn(Optional.empty());
        when(plannerStateStore.findTask("doc-iter-1")).thenReturn(Optional.empty());
        when(plannerStateStore.findStep("step-1")).thenReturn(Optional.empty());

        DocumentIterationRuntimeSupport support = new DocumentIterationRuntimeSupport(
                taskRepository,
                eventRepository,
                artifactRepository,
                approvalRepository,
                pendingRepository,
                plannerStateStore,
                new ObjectMapper()
        );

        support.fail(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"), "verify failed");

        verify(approvalRepository).updateStatus("step-1", ApprovalStatus.FAILED, "verify failed");
        verify(pendingRepository).deleteByTaskId("doc-iter-1");
    }
}
