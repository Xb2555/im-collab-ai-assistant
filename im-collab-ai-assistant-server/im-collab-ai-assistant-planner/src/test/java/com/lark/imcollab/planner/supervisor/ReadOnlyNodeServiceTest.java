package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyNodeServiceTest {

    @Test
    void unknownRoutePreservesNaturalIntakeReply() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                mock(PlannerRuntimeTool.class),
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-ack")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .lastUserMessage("这个方案还行")
                        .assistantReply("好的，这版计划我先保留着。准备推进时回复“开始执行”，要改也可以直接说。")
                        .build())
                .build();
        when(sessionService.get("task-ack")).thenReturn(session);

        PlanTaskSession result = service.readOnly(
                "task-ack",
                "这个方案还行",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.UNKNOWN)
                        .confidence(0.2d)
                        .reason("weak approval")
                        .needsClarification(true)
                        .clarificationQuestion("我没完全判断清楚你的意思。你是想查看计划、调整计划，还是开始执行？")
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply()).contains("计划我先保留");
        assertThat(result.getIntakeState().getAssistantReply()).doesNotContain("没完全判断清楚");
        verify(memoryService).appendAssistantTurn(session, result.getIntakeState().getAssistantReply());
        verify(sessionService, never()).saveWithoutVersionChange(session);
    }

    @Test
    void artifactReadOnlyReplyIncludesFinalLinks() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                runtimeTool,
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-artifact")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .readOnlyView("ARTIFACTS")
                        .lastUserMessage("已有产物")
                        .build())
                .build();
        when(sessionService.get("task-artifact")).thenReturn(session);
        when(runtimeTool.getSnapshot("task-artifact")).thenReturn(TaskRuntimeSnapshot.builder()
                .artifacts(List.of(ArtifactRecord.builder()
                        .artifactId("artifact-1")
                        .type(ArtifactTypeEnum.DOC)
                        .title("项目进展文档")
                        .url("https://example.feishu.cn/docx/doc")
                        .build()))
                .build());

        PlanTaskSession result = service.readOnly(
                "task-artifact",
                "已有产物",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.QUERY_STATUS)
                        .confidence(1.0d)
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply()).contains("项目进展文档");
        assertThat(result.getIntakeState().getAssistantReply()).contains("https://example.feishu.cn/docx/doc");
        verify(memoryService).appendAssistantTurn(session, result.getIntakeState().getAssistantReply());
        verify(sessionService, never()).saveWithoutVersionChange(session);
    }

    @Test
    void artifactReadOnlyReplyStillWorksForCompletedTaskWithoutPlanCards() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                runtimeTool,
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-artifact-no-plan")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .readOnlyView("ARTIFACTS")
                        .lastUserMessage("现有产物")
                        .build())
                .build();
        when(sessionService.get("task-artifact-no-plan")).thenReturn(session);
        when(runtimeTool.getSnapshot("task-artifact-no-plan")).thenReturn(TaskRuntimeSnapshot.builder()
                .artifacts(List.of(ArtifactRecord.builder()
                        .artifactId("artifact-2")
                        .type(ArtifactTypeEnum.PPT)
                        .title("老板汇报PPT")
                        .url("https://example.feishu.cn/slides/ppt")
                        .build()))
                .build());

        PlanTaskSession result = service.readOnly(
                "task-artifact-no-plan",
                "现有产物",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.QUERY_STATUS)
                        .confidence(1.0d)
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply())
                .contains("已有产物：")
                .contains("老板汇报PPT")
                .contains("https://example.feishu.cn/slides/ppt")
                .doesNotContain("现在还没有任务产物");
    }

    @Test
    void statusReadOnlyReplyDoesNotAskFollowUp() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                runtimeTool,
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-status")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成摘要")
                        .type(PlanCardTypeEnum.SUMMARY)
                        .build()))
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .readOnlyView("STATUS")
                        .lastUserMessage("任务状态")
                        .build())
                .build();
        when(sessionService.get("task-status")).thenReturn(session);
        when(runtimeTool.getSnapshot("task-status")).thenReturn(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.COMPLETED).build())
                .steps(List.of(TaskStepRecord.builder()
                        .stepId("card-001")
                        .name("生成摘要")
                        .type(StepTypeEnum.SUMMARY)
                        .status(StepStatusEnum.COMPLETED)
                        .build()))
                .artifacts(List.of(ArtifactRecord.builder()
                        .type(ArtifactTypeEnum.SUMMARY)
                        .title("项目摘要")
                        .preview("项目摘要内容")
                        .build()))
                .build());

        PlanTaskSession result = service.readOnly(
                "task-status",
                "任务状态",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.QUERY_STATUS)
                        .confidence(1.0d)
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply())
                .contains("任务状态：已完成", "步骤进度：1/1", "已有产物：1 个")
                .doesNotContain("需要我", "吗？");
    }

    @Test
    void planReadOnlyFallsBackToRuntimeStepsWhenPlanCardsMissing() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                runtimeTool,
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-plan-no-cards")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .readOnlyView("PLAN")
                        .lastUserMessage("完整计划")
                        .build())
                .build();
        when(sessionService.get("task-plan-no-cards")).thenReturn(session);
        when(runtimeTool.getSnapshot("task-plan-no-cards")).thenReturn(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.COMPLETED).build())
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("step-1")
                                .type(StepTypeEnum.DOC_CREATE)
                                .name("生成飞书文档")
                                .status(StepStatusEnum.COMPLETED)
                                .outputSummary("已生成初稿文档")
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("step-2")
                                .type(StepTypeEnum.PPT_CREATE)
                                .name("生成汇报PPT")
                                .status(StepStatusEnum.COMPLETED)
                                .outputSummary("已生成4页PPT")
                                .build()))
                .build());

        PlanTaskSession result = service.readOnly(
                "task-plan-no-cards",
                "完整计划",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.QUERY_STATUS)
                        .confidence(1.0d)
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply())
                .contains("没有保留原始计划卡片")
                .contains("[DOC_CREATE] 生成飞书文档 - 状态：COMPLETED - 已生成初稿文档")
                .contains("[PPT_CREATE] 生成汇报PPT - 状态：COMPLETED - 已生成4页PPT");
    }
}
