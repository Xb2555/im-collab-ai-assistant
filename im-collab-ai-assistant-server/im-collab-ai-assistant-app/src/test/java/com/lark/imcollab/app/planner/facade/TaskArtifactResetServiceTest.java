package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskArtifactResetServiceTest {

    @Test
    void deletesRemoteArtifactsAndClearsLocalIndexesBeforeExecution() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        PlannerStateStore plannerStateStore = mock(PlannerStateStore.class);
        LarkCliClient larkCliClient = mock(LarkCliClient.class);
        TaskArtifactResetService service = new TaskArtifactResetService(
                artifactRepository,
                plannerStateStore,
                larkCliClient
        );
        when(artifactRepository.findByTaskId("task-1")).thenReturn(List.of(
                Artifact.builder()
                        .artifactId("artifact-doc")
                        .taskId("task-1")
                        .type(ArtifactType.DOC_LINK)
                        .documentId("doc-token")
                        .createdBySystem(true)
                        .createdAt(Instant.now())
                        .build(),
                Artifact.builder()
                        .artifactId("artifact-ppt")
                        .taskId("task-1")
                        .type(ArtifactType.SLIDES_LINK)
                        .documentId("slides-token")
                        .createdBySystem(true)
                        .createdAt(Instant.now())
                        .build()
        ));
        when(plannerStateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder().artifactId("artifact-doc").taskId("task-1").type(ArtifactTypeEnum.DOC).build(),
                ArtifactRecord.builder().artifactId("artifact-ppt").taskId("task-1").type(ArtifactTypeEnum.PPT).build()
        ));
        when(plannerStateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .artifactIds(List.of("artifact-doc", "artifact-ppt"))
                .build()));
        when(larkCliClient.execute(anyList())).thenReturn(new CliCommandResult(0, "{\"ok\":true}"));

        service.clearGeneratedArtifactsBeforeExecution("task-1");

        verify(larkCliClient).execute(List.of(
                "drive", "+delete", "--as", "bot", "--file-token", "doc-token", "--type", "docx", "--yes"
        ));
        verify(larkCliClient).execute(List.of(
                "drive", "+delete", "--as", "user", "--file-token", "slides-token", "--type", "slides", "--yes"
        ));
        verify(artifactRepository).deleteArtifact("task-1", "artifact-doc");
        verify(artifactRepository).deleteArtifact("task-1", "artifact-ppt");
        verify(plannerStateStore).deleteArtifact("task-1", "artifact-doc");
        verify(plannerStateStore).deleteArtifact("task-1", "artifact-ppt");
        verify(plannerStateStore).saveTask(org.mockito.ArgumentMatchers.argThat(task ->
                task != null && task.getArtifactIds() != null && task.getArtifactIds().isEmpty()));
    }
}
