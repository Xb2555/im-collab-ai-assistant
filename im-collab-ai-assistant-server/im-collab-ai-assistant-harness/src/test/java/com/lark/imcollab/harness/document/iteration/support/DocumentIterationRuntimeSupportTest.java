package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.ApprovalRepository;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.PendingDocumentIterationRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void saveSummaryArtifactDoesNotReusePrimaryDocUrlInRuntimeArtifact() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        PendingDocumentIterationRepository pendingRepository = mock(PendingDocumentIterationRepository.class);
        PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
        when(plannerStateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.WAITING_APPROVAL)
                .artifactIds(java.util.List.of("doc-artifact-1"))
                .version(1)
                .createdAt(Instant.parse("2026-05-06T00:00:00Z"))
                .updatedAt(Instant.parse("2026-05-06T00:00:00Z"))
                .build()));

        DocumentIterationRuntimeSupport support = new DocumentIterationRuntimeSupport(
                taskRepository,
                eventRepository,
                artifactRepository,
                approvalRepository,
                pendingRepository,
                plannerStateStore,
                new ObjectMapper()
        );

        Artifact artifact = support.saveSummaryArtifact(
                new DocumentIterationRuntimeSupport.RuntimeContext("task-1", "step-1"),
                "文档迭代结果",
                "摘要内容",
                "doc-123",
                "https://example.feishu.cn/docx/doc-123",
                "ou-user"
        );

        assertThat(artifact.getExternalUrl()).isNull();

        ArgumentCaptor<ArtifactRecord> recordCaptor = ArgumentCaptor.forClass(ArtifactRecord.class);
        verify(plannerStateStore).saveArtifact(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getType().name()).isEqualTo("SUMMARY");
        assertThat(recordCaptor.getValue().getUrl()).isNull();
    }

    @Test
    void saveSummaryArtifactAppendsVersionSuffixForRepeatedSummaryTitles() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        PendingDocumentIterationRepository pendingRepository = mock(PendingDocumentIterationRepository.class);
        PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
        when(plannerStateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .artifactIds(List.of("doc-artifact-1"))
                .version(1)
                .createdAt(Instant.parse("2026-05-06T00:00:00Z"))
                .updatedAt(Instant.parse("2026-05-06T00:00:00Z"))
                .build()));
        when(artifactRepository.findByTaskId("task-1")).thenReturn(List.of(
                Artifact.builder()
                        .artifactId("summary-1")
                        .taskId("task-1")
                        .type(com.lark.imcollab.common.domain.ArtifactType.SUMMARY)
                        .title("文档迭代结果 v1")
                        .createdBySystem(true)
                        .createdAt(Instant.parse("2026-05-06T00:00:00Z"))
                        .build()
        ));

        DocumentIterationRuntimeSupport support = new DocumentIterationRuntimeSupport(
                taskRepository,
                eventRepository,
                artifactRepository,
                approvalRepository,
                pendingRepository,
                plannerStateStore,
                new ObjectMapper()
        );

        Artifact artifact = support.saveSummaryArtifact(
                new DocumentIterationRuntimeSupport.RuntimeContext("task-1", "step-2"),
                "文档迭代结果",
                "第二次摘要",
                "doc-123",
                "https://example.feishu.cn/docx/doc-123",
                "ou-user"
        );

        assertThat(artifact.getTitle()).isEqualTo("文档迭代结果 v2");

        ArgumentCaptor<ArtifactRecord> recordCaptor = ArgumentCaptor.forClass(ArtifactRecord.class);
        verify(plannerStateStore, org.mockito.Mockito.atLeastOnce()).saveArtifact(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getTitle()).isEqualTo("文档迭代结果 v2");
    }
}
