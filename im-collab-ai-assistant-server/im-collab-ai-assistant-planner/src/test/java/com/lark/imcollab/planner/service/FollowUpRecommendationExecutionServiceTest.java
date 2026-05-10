package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.supervisor.PlanningNodeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowUpRecommendationExecutionServiceTest {

    @Test
    void executeGuiRecommendationContinuesCurrentTaskWithStructuredHints() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskSessionResolver sessionResolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        FollowUpRecommendationExecutionService service = new FollowUpRecommendationExecutionService(
                stateStore,
                sessionResolver,
                sessionService,
                graphRunner,
                planningNodeService,
                conversationTaskStateService
        );

        when(stateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .build()));
        when(stateStore.findLatestEvaluation("task-1")).thenReturn(Optional.of(TaskResultEvaluation.builder()
                .taskId("task-1")
                .verdict(ResultVerdictEnum.PASS)
                .nextStepRecommendations(List.of(NextStepRecommendation.builder()
                        .recommendationId("GENERATE_PPT_FROM_DOC")
                        .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                        .targetTaskId("task-1")
                        .sourceArtifactId("artifact-doc")
                        .sourceArtifactType(ArtifactTypeEnum.DOC)
                        .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                        .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                        .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                        .build()))
                .build()));
        when(sessionService.get("task-1")).thenReturn(PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(TaskIntakeState.builder().continuationKey("LARK:chat-1").build())
                .build());
        when(sessionResolver.findArtifactById("task-1", "artifact-doc")).thenReturn(Optional.of(ArtifactRecord.builder()
                .artifactId("artifact-doc")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .title("执行方案")
                .url("https://doc.example")
                .build()));
        PlanTaskSession updated = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), anyString(), any(), anyString()))
                .thenReturn(updated);

        PlanTaskSession result = service.executeGuiRecommendation("task-1", "GENERATE_PPT_FROM_DOC", null);

        assertThat(result).isSameAs(updated);
        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(
                        com.lark.imcollab.planner.supervisor.PlannerSupervisorAction.PLAN_ADJUSTMENT,
                        "resume pending follow-up recommendation"
                )),
                eq("task-1"),
                eq("保留现有文档，基于该文档新增一份汇报PPT初稿。\n产物策略：KEEP_EXISTING_CREATE_NEW\n来源产物ID：artifact-doc"),
                argThat(context -> context != null
                        && context.getDocRefs() != null
                        && context.getDocRefs().contains("https://doc.example")
                        && context.getSourceArtifacts() != null
                        && context.getSourceArtifacts().size() == 1
                        && "artifact-doc".equals(context.getSourceArtifacts().get(0).getArtifactId())),
                eq("基于这份文档生成一版汇报PPT")
        );
        verify(sessionService, times(2)).saveWithoutVersionChange(updated);
    }

    @Test
    void executePendingRecommendationCanStartNewTask() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskSessionResolver sessionResolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        FollowUpRecommendationExecutionService service = new FollowUpRecommendationExecutionService(
                stateStore,
                sessionResolver,
                sessionService,
                graphRunner,
                planningNodeService,
                conversationTaskStateService
        );

        when(sessionService.get("task-1")).thenReturn(PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build());
        when(sessionResolver.findArtifactById("task-1", "artifact-ppt")).thenReturn(Optional.of(ArtifactRecord.builder()
                .artifactId("artifact-ppt")
                .taskId("task-1")
                .type(ArtifactTypeEnum.PPT)
                .title("汇报稿")
                .url("https://ppt.example")
                .build()));
        when(planningNodeService.plan(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(0))
                        .planningPhase(PlanningPhaseEnum.PLAN_READY)
                        .build());

        PlanTaskSession result = service.executePendingRecommendation(
                PendingFollowUpRecommendation.builder()
                        .recommendationId("GENERATE_DOC_FROM_PPT")
                        .followUpMode(FollowUpModeEnum.START_NEW_TASK)
                        .targetTaskId("task-1")
                        .sourceArtifactId("artifact-ppt")
                        .sourceArtifactType(ArtifactTypeEnum.PPT)
                        .suggestedUserInstruction("基于这份PPT补一份配套文档")
                        .plannerInstruction("保留现有PPT，基于该PPT新增一份配套文档。")
                        .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                        .build(),
                WorkspaceContext.builder().chatId("chat-1").inputSource("GUI").build(),
                null,
                "LARK_PRIVATE_CHAT:chat-1"
        );

        assertThat(result).isNotNull();
        assertThat(result.getTaskId()).isNotBlank();
        verify(planningNodeService).plan(
                anyString(),
                eq("保留现有PPT，基于该PPT新增一份配套文档。\n产物策略：KEEP_EXISTING_CREATE_NEW\n来源产物ID：artifact-ppt"),
                argThat(context -> context != null
                        && context.getSourceArtifacts() != null
                        && context.getSourceArtifacts().size() == 1
                        && "artifact-ppt".equals(context.getSourceArtifacts().get(0).getArtifactId())),
                eq("基于这份PPT补一份配套文档")
        );
        verify(sessionResolver).bindConversation(argThat(resolution ->
                resolution != null
                        && "LARK_PRIVATE_CHAT:chat-1".equals(resolution.continuationKey())
                        && result.getTaskId().equals(resolution.taskId())));
        verify(sessionService).saveWithoutVersionChange(result);
    }
}
