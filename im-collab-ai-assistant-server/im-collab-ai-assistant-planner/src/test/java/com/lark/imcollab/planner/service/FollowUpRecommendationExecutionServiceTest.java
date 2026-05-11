package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.supervisor.PlannerPatchTool;
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
import static org.mockito.Mockito.doAnswer;
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
        PlannerPatchTool patchTool = mock(PlannerPatchTool.class);
        PlanQualityService qualityService = mock(PlanQualityService.class);
        FollowUpRecommendationExecutionService service = new FollowUpRecommendationExecutionService(
                stateStore,
                sessionResolver,
                sessionService,
                graphRunner,
                planningNodeService,
                conversationTaskStateService,
                patchTool,
                qualityService
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
                        .targetDeliverable(ArtifactTypeEnum.PPT)
                        .sourceArtifactId("artifact-doc")
                        .sourceArtifactType(ArtifactTypeEnum.DOC)
                        .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                        .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                        .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                        .build()))
                .build()));
        PlanTaskSession targetSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(UserPlanCard.builder()
                                .cardId("card-001")
                                .taskId("task-1")
                                .title("执行方案文档")
                                .description("已有文档")
                                .type(PlanCardTypeEnum.DOC)
                                .status("COMPLETED")
                                .build()))
                        .build())
                .intakeState(TaskIntakeState.builder().continuationKey("LARK:chat-1").build())
                .build();
        when(sessionService.get("task-1")).thenReturn(targetSession);
        when(sessionResolver.findArtifactById("task-1", "artifact-doc")).thenReturn(Optional.of(ArtifactRecord.builder()
                .artifactId("artifact-doc")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .title("执行方案")
                .url("https://doc.example")
                .build()));
        when(patchTool.merge(any(PlanBlueprint.class), any(PlanPatchIntent.class), eq("task-1")))
                .thenReturn(PlanBlueprint.builder()
                        .planCards(List.of(
                                UserPlanCard.builder()
                                        .cardId("card-001")
                                        .taskId("task-1")
                                        .title("执行方案文档")
                                        .description("已有文档")
                                        .type(PlanCardTypeEnum.DOC)
                                        .status("COMPLETED")
                                        .build(),
                                UserPlanCard.builder()
                                        .cardId("card-002")
                                        .taskId("task-1")
                                        .title("生成汇报PPT初稿")
                                        .description("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                                        .type(PlanCardTypeEnum.PPT)
                                        .status("PENDING")
                                        .build()))
                        .build());
        doAnswer(invocation -> {
            PlanTaskSession session = invocation.getArgument(0);
            PlanBlueprint merged = invocation.getArgument(1);
            session.setPlanBlueprint(merged);
            session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
            return session;
        }).when(qualityService).applyMergedPlanAdjustment(any(PlanTaskSession.class), any(PlanBlueprint.class), anyString());

        PlanTaskSession result = service.executeGuiRecommendation("task-1", "GENERATE_PPT_FROM_DOC", null);

        assertThat(result).isSameAs(targetSession);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        verify(patchTool).merge(any(PlanBlueprint.class), argThat(intent ->
                        intent != null
                                && intent.getOperation() == com.lark.imcollab.planner.replan.PlanPatchOperation.ADD_STEP
                                && intent.getNewCardDrafts() != null
                                && intent.getNewCardDrafts().size() == 1
                                && intent.getNewCardDrafts().get(0).getType() == PlanCardTypeEnum.PPT
                                && intent.getNewCardDrafts().get(0).getDescription().contains("保留现有文档")),
                eq("task-1"));
        verify(qualityService).applyMergedPlanAdjustment(any(PlanTaskSession.class), any(PlanBlueprint.class), eq("保留现有文档，基于该文档新增一份汇报PPT初稿。"));
        verify(graphRunner, times(0)).run(any(PlannerSupervisorDecision.class), anyString(), anyString(), any(), anyString());
    }

    @Test
    void executePendingRecommendationCanStartNewTask() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskSessionResolver sessionResolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PlannerPatchTool patchTool = mock(PlannerPatchTool.class);
        PlanQualityService qualityService = mock(PlanQualityService.class);
        FollowUpRecommendationExecutionService service = new FollowUpRecommendationExecutionService(
                stateStore,
                sessionResolver,
                sessionService,
                graphRunner,
                planningNodeService,
                conversationTaskStateService,
                patchTool,
                qualityService
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
