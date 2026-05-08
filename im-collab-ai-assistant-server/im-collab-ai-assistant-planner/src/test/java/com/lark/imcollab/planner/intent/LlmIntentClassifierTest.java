package com.lark.imcollab.planner.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LlmIntentClassifierTest {

    private final PlannerProperties properties = new PlannerProperties();
    private final LlmIntentClassifier classifier = new LlmIntentClassifier(
            null,
            new ObjectMapper(),
            properties,
            new PlannerConversationMemoryService(properties)
    );

    @Test
    void parsesStructuredIntentJson() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"QUERY_STATUS","confidence":0.86,"reason":"overview request","normalizedInput":"查看当前任务概况","needsClarification":false,"readOnlyView":"STATUS"}
                """);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        assertThat(result.get().confidence()).isEqualTo(0.86d);
        assertThat(result.get().normalizedInput()).isEqualTo("查看当前任务概况");
        assertThat(result.get().needsClarification()).isFalse();
        assertThat(result.get().readOnlyView()).isEqualTo("STATUS");
    }

    @Test
    void parsesFullPlanReadOnlyView() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"QUERY_STATUS","confidence":0.9,"reason":"full plan request","normalizedInput":"完整计划","needsClarification":false,"readOnlyView":"PLAN"}
                """);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        assertThat(result.get().readOnlyView()).isEqualTo("PLAN");
    }

    @Test
    void parsesCompletedTaskListReadOnlyView() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"QUERY_STATUS","confidence":0.91,"reason":"completed task browsing request","normalizedInput":"我想看看已完成任务列表","needsClarification":false,"readOnlyView":"COMPLETED_TASKS"}
                """);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        assertThat(result.get().readOnlyView()).isEqualTo("COMPLETED_TASKS");
    }

    @Test
    void invalidJsonReturnsEmptyInsteadOfGuessing() {
        Optional<IntentRoutingResult> result = classifier.parse("QUERY_STATUS");

        assertThat(result).isEmpty();
    }

    @Test
    void unsupportedIntentReturnsEmpty() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"MAKE_MAGIC","confidence":0.9,"reason":"unsupported","normalizedInput":"x","needsClarification":false}
                """);

        assertThat(result).isEmpty();
    }

    @Test
    void parsesAdjustmentTarget() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"ADJUST_PLAN","confidence":0.94,"reason":"edit generated ppt","normalizedInput":"把刚生成的PPT第二页标题改一下","needsClarification":false,"adjustmentTarget":"COMPLETED_ARTIFACT"}
                """);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TaskCommandTypeEnum.ADJUST_PLAN);
        assertThat(result.get().adjustmentTarget()).isEqualTo(AdjustmentTargetEnum.COMPLETED_ARTIFACT);
    }
}
