package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSessionResolverTest {

    @Test
    void explicitTaskIdIsExistingOnlyWhenSessionExists() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findSession("task-new")).thenReturn(Optional.empty());
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve("task-new", WorkspaceContext.builder()
                .chatId("chat-1")
                .build());

        assertThat(resolution.taskId()).isEqualTo("task-new");
        assertThat(resolution.existingSession()).isFalse();
    }

    @Test
    void explicitTaskIdKeepsExistingSessionWhenPresent() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findSession("task-existing")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-existing")
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve("task-existing", null);

        assertThat(resolution.taskId()).isEqualTo("task-existing");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void conversationBindingKeepsCompletedSessionForReadOnlyFollowUp() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskId("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of("task-completed"));
        when(stateStore.findSession("task-completed")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-completed")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-completed");
        assertThat(resolution.existingSession()).isTrue();
        assertThat(resolution.continuationKey()).isEqualTo("LARK_PRIVATE_CHAT:chat-1:chat-root");
    }

    @Test
    void executingTaskInConversationStateWinsOverLegacyBinding() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-active")
                        .executingTaskId("task-running")
                        .lastCompletedTaskId("task-done")
                        .build()));
        when(stateStore.findSession("task-running")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-running")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-running");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void activeTaskInConversationStateWinsWhenNoExecutingTaskExists() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-active")
                        .build()));
        when(stateStore.findSession("task-active")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-active")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-active");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void pendingSelectionBindingWinsOverCompletedActiveTask() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findPendingSelectionSession("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(PlanTaskSession.builder()
                        .taskId("selector-task")
                        .planningPhase(PlanningPhaseEnum.INTAKE)
                        .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                                .pendingTaskSelection(com.lark.imcollab.common.model.entity.PendingTaskSelection.builder()
                                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                        .build())
                                .build())
                        .build()));
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-completed")
                        .lastCompletedTaskId("task-completed")
                        .build()));
        when(stateStore.findSession("task-completed")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-completed")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        when(stateStore.findConversationTaskId("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of("selector-task"));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("selector-task");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void explicitConversationBindingWinsOverStaleConversationStateAfterCompletedTaskSelection() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskId("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of("task-selected"));
        when(stateStore.findSession("task-selected")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-selected")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("selector-task")
                        .lastCompletedTaskId("task-selected")
                        .build()));
        when(stateStore.findSession("selector-task")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-selected");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void stalePendingSelectionDoesNotOverrideNewCompletedTaskBinding() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskId("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of("task-selected"));
        when(stateStore.findPendingSelectionSession("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(PlanTaskSession.builder()
                        .taskId("selector-task")
                        .planningPhase(PlanningPhaseEnum.INTAKE)
                        .build()));
        when(stateStore.findSession("task-selected")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-selected")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("selector-task")
                        .lastCompletedTaskId("task-selected")
                        .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-selected");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void completedCandidatesAreScopedToCurrentConversationAndOwner() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        when(stateStore.findTasksByConversation(
                "LARK_GROUP_CHAT",
                "chat-1",
                "thread-1",
                "ou-1",
                List.of(TaskStatusEnum.COMPLETED),
                5
        )).thenReturn(List.of(TaskRecord.builder()
                .taskId("task-1")
                .title("采购评审 PPT")
                .updatedAt(Instant.parse("2026-05-04T08:00:00Z"))
                .build()));
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(ArtifactRecord.builder()
                .artifactId("artifact-1")
                .type(ArtifactTypeEnum.PPT)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var candidates = resolver.resolveCompletedCandidates(context);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getTaskId()).isEqualTo("task-1");
        assertThat(candidates.get(0).getArtifactTypes()).containsExactly(ArtifactTypeEnum.PPT);
        assertThat(candidates.get(0).getUpdatedAt()).isEqualTo(Instant.parse("2026-05-04T08:00:00Z"));
    }

    @Test
    void completedCandidatesFallbackToArtifactTimestampsWhenTaskTimeMissing() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        when(stateStore.findTasksByConversation(
                "LARK_PRIVATE_CHAT",
                "chat-1",
                null,
                "ou-1",
                List.of(TaskStatusEnum.COMPLETED),
                5
        )).thenReturn(List.of(TaskRecord.builder()
                .taskId("task-1")
                .title("文档迭代")
                .build()));
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("artifact-1")
                        .type(ArtifactTypeEnum.DOC)
                        .createdAt(Instant.parse("2026-05-10T01:00:00Z"))
                        .updatedAt(Instant.parse("2026-05-10T03:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("artifact-2")
                        .type(ArtifactTypeEnum.PPT)
                        .createdAt(Instant.parse("2026-05-10T02:00:00Z"))
                        .updatedAt(Instant.parse("2026-05-10T04:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var candidates = resolver.resolveCompletedCandidates(context);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-05-10T01:00:00Z"));
        assertThat(candidates.get(0).getUpdatedAt()).isEqualTo(Instant.parse("2026-05-10T04:00:00Z"));
    }

    @Test
    void findLatestShareableArtifactPrefersRequestedType() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("ppt-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.PPT)
                        .url("https://slides.example/1")
                        .updatedAt(Instant.parse("2026-05-10T04:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("doc-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/1")
                        .updatedAt(Instant.parse("2026-05-10T03:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var artifact = resolver.findLatestShareableArtifact("task-1", ArtifactTypeEnum.DOC);

        assertThat(artifact).isPresent();
        assertThat(artifact.get().getType()).isEqualTo(ArtifactTypeEnum.DOC);
        assertThat(artifact.get().getUrl()).isEqualTo("https://doc.example/1");
    }

    @Test
    void findLatestShareableArtifactCanReturnSummaryWithoutUrl() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("summary-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.SUMMARY)
                        .title("项目摘要")
                        .preview("项目已完成文档和PPT，可继续同步。")
                        .updatedAt(Instant.parse("2026-05-10T05:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var artifact = resolver.findLatestShareableArtifact("task-1", ArtifactTypeEnum.SUMMARY);

        assertThat(artifact).isPresent();
        assertThat(artifact.get().getType()).isEqualTo(ArtifactTypeEnum.SUMMARY);
        assertThat(artifact.get().getPreview()).contains("项目已完成文档和PPT");
    }

    @Test
    void inferEditableArtifactUsesDocumentSemanticsWhenDocAndPptCoexist() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("doc-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/1")
                        .updatedAt(Instant.parse("2026-05-10T05:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("ppt-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.PPT)
                        .url("https://slides.example/1")
                        .updatedAt(Instant.parse("2026-05-10T04:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var artifact = resolver.inferEditableArtifact("task-1", "再加一小节关于项目总结的内容");

        assertThat(artifact).isPresent();
        assertThat(artifact.get().getType()).isEqualTo(ArtifactTypeEnum.DOC);
        assertThat(artifact.get().getArtifactId()).isEqualTo("doc-1");
    }

    @Test
    void inferEditableArtifactUsesPresentationSemanticsWhenDocAndPptCoexist() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("doc-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/1")
                        .updatedAt(Instant.parse("2026-05-10T05:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("ppt-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.PPT)
                        .url("https://slides.example/1")
                        .updatedAt(Instant.parse("2026-05-10T04:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var artifact = resolver.inferEditableArtifact("task-1", "把第二页标题改成项目总结");

        assertThat(artifact).isPresent();
        assertThat(artifact.get().getType()).isEqualTo(ArtifactTypeEnum.PPT);
        assertThat(artifact.get().getArtifactId()).isEqualTo("ppt-1");
    }

    @Test
    void inferEditableArtifactPrefersLatestExplicitTypeWhenMultipleDocsExist() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(
                ArtifactRecord.builder()
                        .artifactId("doc-old")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/old")
                        .updatedAt(Instant.parse("2026-05-10T03:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("doc-new")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/new")
                        .updatedAt(Instant.parse("2026-05-10T06:00:00Z"))
                        .build(),
                ArtifactRecord.builder()
                        .artifactId("ppt-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.PPT)
                        .url("https://slides.example/1")
                        .updatedAt(Instant.parse("2026-05-10T04:00:00Z"))
                        .build()
        ));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var artifact = resolver.inferEditableArtifact("task-1", "帮我改一下这个doc，补一段项目总结");

        assertThat(artifact).isPresent();
        assertThat(artifact.get().getType()).isEqualTo(ArtifactTypeEnum.DOC);
        assertThat(artifact.get().getArtifactId()).isEqualTo("doc-new");
    }

}
