package com.lark.imcollab.planner.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
