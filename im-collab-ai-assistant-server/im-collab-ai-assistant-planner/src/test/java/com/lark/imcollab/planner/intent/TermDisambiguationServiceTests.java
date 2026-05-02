package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TermDisambiguationServiceTests {

    private final PlannerProperties properties = new PlannerProperties();

    TermDisambiguationServiceTests() {
        properties.initDefaults();
    }

    @Test
    void shouldResolveHarnessUsingLlmChoice() {
        TermDisambiguationService service = serviceReturning("WORKSPACE_INTERNAL_CAPABILITY");
        PlanTaskSession session = PlanTaskSession.builder()
                .profession("产品经理")
                .industry("智能办公")
                .clarificationAnswers(List.of("就是当前项目里的 harness 模块"))
                .build();

        TermDisambiguationService.DisambiguationOutcome outcome = service.resolve(
                session,
                "请分析 harness 模块在场景 c 里的文档生成链路",
                WorkspaceContext.builder().profession("产品经理").industry("智能办公").build()
        );

        assertThat(outcome.requireInput()).isNull();
        assertThat(outcome.resolutions()).hasSize(1);
        assertThat(outcome.resolutions().get(0).getResolvedMeaning()).isEqualTo("WORKSPACE_INTERNAL_CAPABILITY");
    }

    @Test
    void shouldAskUserWhenLlmRequestsClarification() {
        TermDisambiguationService service = serviceReturning("CLARIFY");
        PlanTaskSession session = PlanTaskSession.builder()
                .profession("工程师")
                .industry("软件")
                .build();

        TermDisambiguationService.DisambiguationOutcome outcome = service.resolve(
                session,
                "帮我写一篇 harness 技术文档",
                WorkspaceContext.builder().build()
        );

        assertThat(outcome.resolutions()).isEmpty();
        assertThat(outcome.requireInput()).isNotNull();
        assertThat(outcome.requireInput().getType()).isEqualTo("CHOICE");
        assertThat(outcome.requireInput().getOptions()).hasSize(3);
        assertThat(outcome.requireInput().getOptions()).doesNotContain("当前项目里的 harness 执行编排模块");
    }

    private TermDisambiguationService serviceReturning(String choice) {
        LlmChoiceResolver resolver = (instruction, allowedChoices, systemPrompt) -> choice;
        return new TermDisambiguationService(new TermDisambiguationPolicyRegistry(properties, resolver));
    }
}
