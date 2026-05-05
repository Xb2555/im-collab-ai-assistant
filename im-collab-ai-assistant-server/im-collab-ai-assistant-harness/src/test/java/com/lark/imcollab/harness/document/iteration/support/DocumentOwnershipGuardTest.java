package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentOwnershipGuardTest {

    @Test
    void requestedTaskIdRecoversOwnedDocWhenIndexLookupMisses() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
        Artifact owned = Artifact.builder()
                .artifactId("doc-link-1")
                .taskId("task-1")
                .type(ArtifactType.DOC_LINK)
                .documentId("doc-1")
                .externalUrl("https://example/doc-1")
                .ownerScenario("SCENARIO_C_DOCUMENT_GENERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();
        when(artifactRepository.findLatestDocArtifactByTaskId("task-1")).thenReturn(Optional.of(owned));
        when(artifactRepository.findOwnedDocumentRecordByDocumentId(anyString())).thenReturn(Optional.empty());
        when(artifactRepository.findOwnedDocumentRecordByExternalUrl(anyString())).thenReturn(Optional.empty());

        DocumentOwnershipGuard guard = new DocumentOwnershipGuard(
                artifactRepository,
                taskRepository,
                plannerStateStore
        );

        Artifact result = guard.assertEditable("https://example/doc-1", "ou-user", "task-1");

        assertThat(result).isEqualTo(owned);
    }
}
