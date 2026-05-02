package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskCommand;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRouterServiceTest {

    private final IntentRouterService router = new IntentRouterService();

    @Test
    void cancelCommandUsesHardRuleBeforeAnyPlanningIntent() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskCommand command = router.route(session, "不用做了，取消这个任务", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.CANCEL_TASK);
        assertThat(command.getTaskId()).isEqualTo("task-1");
        assertThat(command.getRawText()).isEqualTo("不用做了，取消这个任务");
    }

    @Test
    void statusQueryUsesModelClassificationInsteadOfHardRule() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.9d,
                "user asks progress",
                "现在进度怎么样了",
                false
        )));

        TaskCommand command = service.route(session, "现在进度怎么样了", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        verify(model).classify(session, "现在进度怎么样了", true);
    }

    @Test
    void retryCommandUsesConfirmAction() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.FAILED)
                .build();

        TaskCommand command = router.route(session, "重试一下", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.CONFIRM_ACTION);
    }

    @Test
    void naturalApprovalWithExecuteUsesConfirmAction() {
        PlanTaskSession session = plannedSession();

        TaskCommand command = router.route(session, "没问题，执行", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.CONFIRM_ACTION);
    }

    @Test
    void genericApprovalDoesNotUseHardConfirmRule() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.UNKNOWN,
                0.7d,
                "generic approval without execute request",
                "这个方案还行",
                true
        )));

        TaskCommand command = service.route(session, "这个方案还行", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
        verify(model).classify(session, "这个方案还行", true);
    }

    @Test
    void quotedStartExecutionInstructionDoesNotUseHardConfirmRule() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.86d,
                "user asks about confirmation instruction",
                "为什么要回复开始执行",
                false
        )));

        TaskCommand command = service.route(session, "为什么要回复“开始执行”？", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        verify(model).classify(session, "为什么要回复“开始执行”？", true);
    }

    @Test
    void taskOverviewUsesModelClassification() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.9d,
                "task overview",
                "任务概况",
                false
        )));

        TaskCommand command = service.route(session, "任务概况", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        verify(model).classify(session, "任务概况", true);
    }

    @Test
    void modelUnavailableTaskOverviewBecomesUnknown() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskCommand command = router.route(session, "任务概况", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    @Test
    void naturalFullPlanQueryRoutesToStatusQueryByModel() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.92d,
                "user asks full plan",
                "完整的计划给我看看",
                false
        )));

        TaskCommand command = service.route(session, "完整的计划给我看看", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
    }

    @Test
    void noSessionFullPlanQueryCanStayReadOnly() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.93d,
                "user asks for existing full plan",
                "完整计划给我看看",
                false,
                "PLAN"
        )));

        TaskCommand command = service.route(null, "完整计划给我看看", null, false);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        verify(model).classify(null, "完整计划给我看看", false);
    }

    @Test
    void taskOverviewWithMutationIntentUsesModelClassification() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.ADJUST_PLAN,
                0.88d,
                "user asks to add overview step",
                "最后加一个任务概况",
                false
        )));

        TaskCommand command = service.route(session, "最后加一个任务概况", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.ADJUST_PLAN);
    }

    @Test
    void modelCanChooseFixedIntentForNaturalExistingSessionInput() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.ADJUST_PLAN,
                0.9d,
                "user asks for boss-facing version",
                "补充老板要的版本",
                false
        )));

        TaskCommand command = service.route(session, "老板要的版本也来一份", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.ADJUST_PLAN);
        verify(model).classify(session, "老板要的版本也来一份", true);
    }

    @Test
    void lowConfidenceModelResultDoesNotBecomePlanAdjustment() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        PlannerProperties properties = new PlannerProperties();
        properties.getIntent().setFallbackToLocalRules(false);
        IntentRouterService service = router(model, properties);
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.ADJUST_PLAN,
                0.2d,
                "low confidence",
                "帮我看看这个",
                false
        )));

        TaskCommand command = service.route(session, "帮我看看这个", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    @Test
    void modelStartTaskInsideExistingPlanIsRejectedByGuard() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        PlannerProperties properties = new PlannerProperties();
        properties.getIntent().setFallbackToLocalRules(false);
        IntentRouterService service = router(model, properties);
        PlanTaskSession session = plannedSession();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.START_TASK,
                0.9d,
                "model drift",
                "重新描述一下",
                false
        )));

        TaskCommand command = service.route(session, "重新描述一下", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    @Test
    void clarificationAnswerWinsWhenSessionIsAskingUser() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-3")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        TaskCommand command = router.route(session, "面向技术评审，输出文档即可", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.ANSWER_CLARIFICATION);
        assertThat(command.getRawText()).isEqualTo("面向技术评审，输出文档即可");
    }

    @Test
    void readOnlyQueryCanOverrideClarificationFallbackWhenSessionIsAskingUser() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-3")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.QUERY_STATUS,
                0.93d,
                "user asks to inspect current plan",
                "完整计划给我看看",
                false,
                "PLAN"
        )));

        TaskCommand command = service.route(session, "完整计划给我看看", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        verify(model).classify(session, "完整计划给我看看", true);
    }

    @Test
    void naturalEditUsesModelClassification() {
        LlmIntentClassifier model = mock(LlmIntentClassifier.class);
        IntentRouterService service = router(model, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-4")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(model.classify(any(), anyString(), anyBoolean())).thenReturn(Optional.of(new IntentRoutingResult(
                TaskCommandTypeEnum.ADJUST_PLAN,
                0.9d,
                "user asks to add risk analysis",
                "再加一条风险分析",
                false
        )));

        TaskCommand command = service.route(session, "再加一条风险分析", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.ADJUST_PLAN);
    }

    @Test
    void colloquialSupplementWithoutModelBecomesUnknown() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-4")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskCommand command = router.route(session, "顺手补一个风险表", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    @Test
    void colloquialAlsoNeedDeliverableWithoutModelBecomesUnknown() {
        PlanTaskSession session = plannedSession();

        TaskCommand command = router.route(session, "还要一个给老板汇报的ppt", null, true);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    @Test
    void blankInputBecomesUnknownInsteadOfStartingEmptyTask() {
        TaskCommand command = router.route(null, "  ", null, false);

        assertThat(command.getType()).isEqualTo(TaskCommandTypeEnum.UNKNOWN);
    }

    private static IntentRouterService router(LlmIntentClassifier model, PlannerProperties properties) {
        return new IntentRouterService(
                new HardRuleIntentClassifier(),
                model,
                new IntentDecisionGuard(properties),
                properties
        );
    }

    private static PlanTaskSession plannedSession() {
        return PlanTaskSession.builder()
                .taskId("task-5")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();
    }
}
