package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

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
            new LarkIMTaskReplyFormatter(),
            new DocRefExtractionService(new ObjectMapper())
    );

    @Test
    void asyncDispatcherReturnsReceiptSessionBeforePlannerFinishes() {
        PlannerPlanFacade slowPlanner = mock(PlannerPlanFacade.class);
        LarkMessageReplyTool asyncReplyTool = mock(LarkMessageReplyTool.class);
        CapturingExecutor executor = new CapturingExecutor();
        LoggingLarkInboundMessageDispatcher asyncDispatcher = new LoggingLarkInboundMessageDispatcher(
                slowPlanner,
                taskCommandFacade,
                asyncReplyTool,
                null,
                null,
                new LarkIMTaskReplyFormatter(),
                new DocRefExtractionService(new ObjectMapper()),
                executor
        );
        PlanTaskSession ready = session(TaskIntakeTypeEnum.NEW_TASK, PlanningPhaseEnum.PLAN_READY);
        when(slowPlanner.previewImmediateReply(
                anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。");
        when(slowPlanner.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(ready);

        PlanTaskSession accepted = asyncDispatcher.dispatch(message("生成一份技术方案文档"));

        assertThat(accepted.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        verify(slowPlanner, never()).plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(sentPrivateTexts(asyncReplyTool)).containsExactly("🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。");
        executor.runAll();
        verify(slowPlanner).plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        assertThat(sentPrivateTexts(asyncReplyTool))
                .hasSize(2)
                .last()
                .satisfies(reply -> assertThat(reply).contains("我准备这样推进"));
    }

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
        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("🚀 好，我开始推进了")
                        .contains("任务状态：正在执行")
                        .contains("步骤进度：0/2"));
    }

    @Test
    void confirmActionAlreadyExecutingRepliesConfirmationAndStatusTogether() {
        PlanTaskSession executing = session(TaskIntakeTypeEnum.CONFIRM_ACTION, PlanningPhaseEnum.EXECUTING);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(executing);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.EXECUTING));

        dispatcher.dispatch(message("开始执行"));

        verify(taskCommandFacade, never()).confirmExecution(anyString());
        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("🚀 好，我开始推进了")
                        .contains("任务状态：正在执行")
                        .contains("步骤进度：0/2"));
    }

    @Test
    void confirmActionRetriesFailedTaskFromIm() {
        PlanTaskSession failed = session(TaskIntakeTypeEnum.CONFIRM_ACTION, PlanningPhaseEnum.FAILED);
        PlanTaskSession retrying = session(TaskIntakeTypeEnum.CONFIRM_ACTION, PlanningPhaseEnum.EXECUTING);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(failed);
        when(taskCommandFacade.retryExecution("task-1", "再试一次")).thenReturn(retrying);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.EXECUTING));

        dispatcher.dispatch(message("再试一次"));

        verify(taskCommandFacade).retryExecution("task-1", "再试一次");
        verify(taskCommandFacade, never()).confirmExecution(anyString());
        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("🔁 好，我接着重试这一步")
                        .contains("任务状态：正在执行")
                        .contains("步骤进度：0/2"));
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
        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply).contains("任务状态：正在执行", "步骤进度：0/2"));
    }

    @Test
    void planAdjustmentRepliesLightSummary() {
        PlanTaskSession adjusted = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(adjusted);

        dispatcher.dispatch(message("再加一条群内摘要"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("计划已更新", "你确认没问题我就继续推进")
                        .doesNotContain("成功标准", "风险关注"));
    }

    @Test
    void executingPlanAdjustmentRepliesRestartedExecutionAndStatus() {
        PlanTaskSession executing = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.EXECUTING);
        executing.getIntakeState().setAssistantReply("已中断当前执行，并按新计划重新开始执行。");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(executing);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.EXECUTING));

        dispatcher.dispatch(message("把第三页改一下并继续跑"));

        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("已中断当前执行，并按新计划重新开始执行")
                        .contains("任务状态：正在执行")
                        .contains("步骤进度：0/2"));
    }

    @Test
    void imRawInstructionIsNotPassedAsSelectedWorkspaceMaterial() {
        PlanTaskSession clarification = session(TaskIntakeTypeEnum.NEW_TASK, PlanningPhaseEnum.ASK_USER);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(clarification);

        dispatcher.dispatch(message("帮我总结群里消息并生成一个总结文档"));

        ArgumentCaptor<WorkspaceContext> contextCaptor = ArgumentCaptor.forClass(WorkspaceContext.class);
        verify(plannerPlanFacade).plan(
                org.mockito.ArgumentMatchers.eq("帮我总结群里消息并生成一个总结文档"),
                contextCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        WorkspaceContext context = contextCaptor.getValue();
        assertThat(context.getSelectedMessages()).isEmpty();
        assertThat(context.getTimeRange()).isNull();
        assertThat(context.getSelectionType()).isNull();
        assertThat(context.getMessageId()).isEqualTo("message-1");
    }

    @Test
    void docLinkMessagePopulatesWorkspaceDocRefs() {
        PlanTaskSession clarification = session(TaskIntakeTypeEnum.CLARIFICATION_REPLY, PlanningPhaseEnum.PLAN_READY);
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(clarification);

        dispatcher.dispatch(message(
                "这个是文档链接：https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph",
                "{\"text\":\"这个是文档链接\",\"share_link\":\"https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph\"}"
        ));

        ArgumentCaptor<WorkspaceContext> contextCaptor = ArgumentCaptor.forClass(WorkspaceContext.class);
        verify(plannerPlanFacade).plan(
                org.mockito.ArgumentMatchers.eq("这个是文档链接：https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph"),
                contextCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(contextCaptor.getValue().getDocRefs())
                .containsExactly("https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph");
        assertThat(contextCaptor.getValue().getSelectionType()).isEqualTo("DOCUMENT");
    }

    @Test
    void planAdjustmentClarificationDoesNotPretendPlanUpdated() {
        PlanTaskSession unchanged = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.PLAN_READY);
        unchanged.getIntakeState().setAssistantReply("我还没完全判断清楚要怎么改这份计划。");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(unchanged);

        dispatcher.dispatch(message("顺手处理一下"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("我还没完全判断清楚")
                        .doesNotContain("计划已更新"));
    }

    @Test
    void completedPlanAdjustmentIncludesUpdatedSummaryAndArtifactLinks() {
        PlanTaskSession completed = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.COMPLETED);
        completed.getIntakeState().setAssistantReply("已修改 PPT 第 1 页：IM指定产物修改验证2002");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(completed);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshotWithArtifact());

        dispatcher.dispatch(message("2"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("当前上一轮任务已完成")
                        .contains("按现有PPT修改处理")
                        .contains("已修改 PPT 第 1 页：IM指定产物修改验证2002")
                        .contains("任务状态：已完成")
                        .contains("[PPT] IM指定产物修改验证")
                        .contains("https://example.feishu.cn/slides/abc123"));
    }

    @Test
    void completedPlanAdjustmentWithContinueWordsStillClarifiesNoRestart() {
        PlanTaskSession completed = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.COMPLETED);
        completed.getIntakeState().setAssistantReply("已修改 PPT 第 3 页：实施收益");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(completed);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshotWithArtifact());

        dispatcher.dispatch(message("把第三页改一下并继续执行"));

        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("当前上一轮任务已完成")
                        .contains("按现有PPT修改处理")
                        .contains("不是重新启动执行")
                        .contains("已修改 PPT 第 3 页：实施收益"));
    }

    @Test
    void completedDocAdjustmentUsesDocumentWordingInsteadOfPpt() {
        PlanTaskSession completed = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.COMPLETED);
        completed.getIntakeState().setAssistantReply("已修改文档 2.2 节：补充验证结论");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(completed);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshotWithArtifact());

        dispatcher.dispatch(message("把这份文档在 2.2 验证结论末尾补充一句"));

        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("当前上一轮任务已完成")
                        .contains("按现有文档修改处理")
                        .contains("已修改文档 2.2 节：补充验证结论")
                        .doesNotContain("按现有 PPT 修改处理"));
    }

    @Test
    void failedPlanAdjustmentRepliesFailureInsteadOfReceiptText() {
        PlanTaskSession failed = session(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, PlanningPhaseEnum.FAILED);
        failed.setTransitionReason("计划调整失败");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(failed);
        when(taskCommandFacade.getRuntimeSnapshot("task-1")).thenReturn(snapshot(TaskStatusEnum.FAILED));

        dispatcher.dispatch(message("再加一条：最后还要输出一句话总结"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply).contains("这次处理没有成功", "计划调整失败"));
        assertThat(replies).allSatisfy(reply -> assertThat(reply).doesNotContain("任务已收到，正在处理"));
    }

    @Test
    void unknownIntentDoesNotRepeatPlanReadyAsIfUpdated() {
        PlanTaskSession unknown = session(TaskIntakeTypeEnum.UNKNOWN, PlanningPhaseEnum.PLAN_READY);
        unknown.getIntakeState().setAssistantReply("我有点没对上你的意思。你是想看计划，还是要改其中一步？");
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(unknown);

        dispatcher.dispatch(message("帮我看看这个"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply)
                        .contains("我有点没对上你的意思")
                        .doesNotContain("我准备这样推进", "计划已更新"));
    }

    @Test
    void detailedPlanExpandsCardsOnlyWhenAsked() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        detailed.getIntakeState().setAssistantReply(new LarkIMTaskReplyFormatter().fullPlan(detailed));
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("详细计划"));

        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply).contains("详细计划如下", "[DOC]", "[PPT]"));
    }

    @Test
    void naturalPlanQuestionExpandsFullPlan() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        detailed.getIntakeState().setAssistantReply(new LarkIMTaskReplyFormatter().fullPlan(detailed));
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("计划是什么"));

        assertThat(sentPrivateTexts(replyTool)).singleElement()
                .satisfies(reply -> assertThat(reply)
                .contains("详细计划如下", "[DOC]", "[PPT]")
                .doesNotContain("任务状态："));
    }

    @Test
    void completePlanWithParticleExpandsFullPlan() {
        PlanTaskSession detailed = session(TaskIntakeTypeEnum.STATUS_QUERY, PlanningPhaseEnum.PLAN_READY);
        detailed.getIntakeState().setAssistantReply(new LarkIMTaskReplyFormatter().fullPlan(detailed));
        when(plannerPlanFacade.plan(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(detailed);

        dispatcher.dispatch(message("我想要的是完整的计划"));

        List<String> replies = sentPrivateTexts(replyTool);
        assertThat(replies).singleElement()
                .satisfies(reply -> assertThat(reply).contains("详细计划如下", "[DOC]", "[PPT]"));
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

    private static TaskRuntimeSnapshot snapshotWithArtifact() {
        return TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(TaskStatusEnum.COMPLETED).build())
                .steps(List.of(
                        TaskStepRecord.builder().taskId("task-1").name("生成汇报 PPT").status(StepStatusEnum.COMPLETED).build()
                ))
                .artifacts(List.of(
                        ArtifactRecord.builder()
                                .artifactId("artifact-1")
                                .taskId("task-1")
                                .type(ArtifactTypeEnum.PPT)
                                .title("IM指定产物修改验证")
                                .url("https://example.feishu.cn/slides/abc123")
                                .build()
                ))
                .build();
    }

    private static UserPlanCard card(String title, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .title(title)
                .type(type)
                .build();
    }

    private static LarkInboundMessage message(String content) {
        return message(content, content);
    }

    private static LarkInboundMessage message(String content, String rawContent) {
        return new LarkInboundMessage(
                "event-1",
                "message-1",
                "chat-1",
                "thread-1",
                "p2p",
                "text",
                content,
                rawContent,
                "ou-user",
                "2026-04-30T00:00:00Z",
                InputSourceEnum.LARK_PRIVATE_CHAT
        );
    }

    private static List<String> sentPrivateTexts(LarkMessageReplyTool replyTool) {
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool, org.mockito.Mockito.atLeastOnce())
                .sendPrivateText(org.mockito.ArgumentMatchers.eq("ou-user"), textCaptor.capture(), anyString());
        return textCaptor.getAllValues();
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
            tasks.clear();
        }
    }
}
