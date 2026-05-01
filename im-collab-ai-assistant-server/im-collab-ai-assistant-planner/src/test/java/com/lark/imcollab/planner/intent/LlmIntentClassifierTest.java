package com.lark.imcollab.planner.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LlmIntentClassifierTest {

    private final LlmIntentClassifier classifier = new LlmIntentClassifier(null, new ObjectMapper(), new PlannerProperties());

    @Test
    void parsesStructuredIntentJson() {
        Optional<IntentRoutingResult> result = classifier.parse("""
                {"intent":"QUERY_STATUS","confidence":0.86,"reason":"overview request","normalizedInput":"查看当前任务概况","needsClarification":false}
                """);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TaskCommandTypeEnum.QUERY_STATUS);
        assertThat(result.get().confidence()).isEqualTo(0.86d);
        assertThat(result.get().normalizedInput()).isEqualTo("查看当前任务概况");
        assertThat(result.get().needsClarification()).isFalse();
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
