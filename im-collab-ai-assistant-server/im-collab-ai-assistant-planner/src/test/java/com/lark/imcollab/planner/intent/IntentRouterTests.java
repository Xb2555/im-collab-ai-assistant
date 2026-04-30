package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.TaskType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTests {

    @Test
    void shouldPreferRuleBasedMixedWhenDocAndSlidesBothPresent() {
        IntentRouter router = new IntentRouter((instruction, allowedChoices, systemPrompt) -> "WRITE_DOC");

        TaskType taskType = router.route(Conversation.builder().rawMessage("帮我生成文档和 ppt").build());

        assertThat(taskType).isEqualTo(TaskType.MIXED);
    }

    @Test
    void shouldFallbackToLlmChoiceWhenRulesMiss() {
        IntentRouter router = new IntentRouter((instruction, allowedChoices, systemPrompt) -> "WRITE_WHITEBOARD");

        TaskType taskType = router.route(Conversation.builder().rawMessage("帮我做一个可视化交付").build());

        assertThat(taskType).isEqualTo(TaskType.WRITE_WHITEBOARD);
    }
}
