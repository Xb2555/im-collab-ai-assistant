package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoggingLarkInboundMessageDispatcherTest {

    private final PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
    private final ImTaskCommandFacade taskCommandFacade = mock(ImTaskCommandFacade.class);
    private final LarkMessageReplyTool replyTool = mock(LarkMessageReplyTool.class);
    private final LoggingLarkInboundMessageDispatcher dispatcher = new LoggingLarkInboundMessageDispatcher(
            plannerPlanFacade,
            taskCommandFacade,
            replyTool,
            null,
            null,
            new LarkIMTaskReplyFormatter()
    );

    @Test
    void confirmActionStartsExecutionFromIm() {
        PlanTaskSession ready = session(TaskIntakeTypeEnum.CONFIRM_ACTION, PlanningPhaseEnum.PLAN_READY);
        PlanTaskSession executing = session(TaskIntakeTypeEnum.CONFIRM_ACTION, PlanningPhaseEnum.EXECUTING);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(ready);
        when(taskCommandFacade.confirmExecution("task-1")).thenReturn(executing);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.EXECUTING));

        dispatcher.dispatch(message("没问题，执行"));

        verify(taskCommandFacade).confirmExecution("task-1");
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("好的，开始执行");
    }

    @Test
    void statusQueryOnlyReadsRuntimeSnapshot() {
        PlanTaskSession status = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(status);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.EXECUTING));

        dispatcher.dispatch(message("进度怎么样"));

        verify(taskCommandFacade).getRuntimeSnapshot("task-1");
        verify(taskCommandFacade, never()).confirmExecution(anyString());
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("任务状态：正在执行", "步骤进度：0/2");
    }

    @Test
    void planAdjustmentRepliesLightSummary() {
        PlanTaskSession adjusted = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(adjusted);

        dispatcher.dispatch(message("再加一条群内摘要"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("计划已更新", "开始执行");
        assertThat(textCaptor.getValue()).doesNotContain("成功标准", "风险关注");
    }

    @Test
    void failedPlanAdjustmentRepliesFailureInsteadOfReceiptText() {
        PlanTaskSession failed = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.FAILED);
        failed.setTransitionReason("计划调整失败");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(failed);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.FAILED));

        dispatcher.dispatch(message("再加一条：最后还要输出一句话总结"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("这次处理没有成功", "计划调整失败");
        assertThat(textCaptor.getValue()).doesNotContain("任务已收到，正在处理");
    }

    @Test
    void unknownIntentDoesNotRepeatPlanReadyAsIfUpdated() {
        PlanTaskSession unknown = session(TaskIntakeTypeEnum.UNKNOWN, PlanningPhaseEnum.PLAN_READY);
        unknown.getIntakeState().setAssistantReply("我有点没对上你的意思。你是想看计划，还是要改其中一步？");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(unknown);

        dispatcher.dispatch(message("帮我看看这个"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("我有点没对上你的意思");
        assertThat(textCaptor.getValue()).doesNotContain("我准备这样推进", "计划已更新");
    }

    @Test
    void detailedPlanExpandsCardsOnlyWhenAsked() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("详细计划"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("详细计划如下", "[DOC]", "[PPT]");
    }

    @Test
    void naturalPlanQuestionExpandsFullPlan() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("计划是什么"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue())
                .contains("详细计划如下", "[DOC]", "[PPT]")
                .doesNotContain("任务状态：");
    }

    @Test
    void completePlanWithParticleExpandsFullPlan() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("我想要的是完整的计划"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        assertThat(textCaptor.getValue()).contains("详细计划如下", "[DOC]", "[PPT]");
    }

    private static PlanTaskSession session(TaskIntakeTypeEnum intakeType, PlanningPhaseEnum phase) {
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(phase)
                .intakeState(TaskIntakeState.builder().intakeType(intakeType).build())
                .planCards(List.of(
                        card("生成技术方案文档", PlanCardTypeEnum.DOC),
                        card("生成汇报 PPT", PlanCardTypeEnum.PPT)
                ))
                .planBlueprint(PlanBlueprint.builder()
                        .successCriteria(List.of("完整准确"))
                        .risks(List.of("资料不足"))
                        .planCards(List.of(
                                card("生成技术方案文档", PlanCardTypeEnum.DOC),
                                card("生成汇报 PPT", PlanCardTypeEnum.PPT)
                        ))
                        .build())
                .build();
    }

    private static TaskRuntimeSnapshot snapshot(TaskStatusEnum status) {
        return TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(status).build())
                .steps(List.of(
                        TaskStepRecord.builder().taskId("task-1").name("生成技术方案文档").status(StepStatusEnum.RUNNING).build(),
                        TaskStepRecord.builder().taskId("task-1").name("生成汇报 PPT").status(StepStatusEnum.READY).build()
                ))
                .artifacts(List.of())
                .build();
    }

    private static UserPlanCard card(String title, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .title(title)
                .type(type)
                .build();
    }

    private static LarkInboundMessage message(String content) {
        return new LarkInboundMessage(
                "event-1",
                "message-1",
                "chat-1",
                "thread-1",
                "p2p",
                "text",
                content,
                "ou-user",
                "2026-04-30T00:00:00Z",
                InputSourceEnum.LARK_PRIVATE_CHAT
        );
    }
}
