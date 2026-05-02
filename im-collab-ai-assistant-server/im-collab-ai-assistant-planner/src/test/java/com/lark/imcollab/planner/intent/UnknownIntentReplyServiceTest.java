package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownIntentReplyServiceTest {

    private final UnknownIntentReplyService service = new UnknownIntentReplyService();

    @Test
    void softPlanFeedbackKeepsPlanWithoutMechanicalUnknownPrompt() {
        String reply = service.reply(plannedSession(), "这个方案感觉还行", "fallback no confident intent");

        assertThat(reply).contains("当前计划");
        assertThat(reply).doesNotContain("没对上", "没完全判断清楚");
    }

    @Test
    void unclearMessageWithPlanStillKeepsCurrentPlan() {
        String reply = service.reply(plannedSession(), "帮我看看这个", "fallback no confident intent");

        assertThat(reply).contains("当前计划");
        assertThat(reply).doesNotContain("没对上");
    }

    @Test
    void modelUnknownReplyCannotPretendExecutionStarted() throws Exception {
        Method normalize = UnknownIntentReplyService.class.getDeclaredMethod("normalizeReply", String.class);
        normalize.setAccessible(true);

        Object reply = normalize.invoke(service, "好的，马上开始执行计划。");

        assertThat(reply).isNull();
    }

    @Test
    void modelUnknownReplyMayMentionExecutionAsAnInstruction() throws Exception {
        Method normalize = UnknownIntentReplyService.class.getDeclaredMethod("normalizeReply", String.class);
        normalize.setAccessible(true);

        Object reply = normalize.invoke(service, "这版计划先保留着，准备推进时再说开始执行。");

        assertThat(reply).isEqualTo("这版计划先保留着，准备推进时再说开始执行。");
    }

    private static PlanTaskSession plannedSession() {
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();
    }
}
