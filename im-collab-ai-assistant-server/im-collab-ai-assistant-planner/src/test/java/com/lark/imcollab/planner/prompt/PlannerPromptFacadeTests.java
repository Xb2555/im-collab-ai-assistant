package com.lark.imcollab.planner.prompt;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptFacadeTests {

    private final PromptTemplateService templateService = new PromptTemplateService(new DefaultResourceLoader());

    @Test
    void shouldRenderProfileVersionTemplateWhenExists() {
        PlannerPromptFacade facade = new PlannerPromptFacade(templateService, baseProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .promptProfile("pm")
                .promptVersion("v1")
                .profession("产品经理")
                .industry("SaaS")
                .audience("老板")
                .tone("专业")
                .language("中文")
                .build();

        String prompt = facade.supervisorPrompt(session);

        assertTrue(prompt.contains("产品经理场景"));
        assertTrue(prompt.contains("职业：产品经理"));
    }

    @Test
    void shouldFallbackToProfileWithoutVersionWhenVersionFileMissing() {
        PlannerPromptFacade facade = new PlannerPromptFacade(templateService, baseProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .promptProfile("test-profile")
                .promptVersion("v99")
                .profession("Architect")
                .build();

        String prompt = facade.supervisorPrompt(session);

        assertTrue(prompt.contains("TEST PROFILE SUPERVISOR"));
        assertTrue(prompt.contains("profession=Architect"));
    }

    @Test
    void shouldFallbackToConfiguredDefaultProfileWhenProfileTemplateMissing() {
        PlannerPromptFacade facade = new PlannerPromptFacade(templateService, baseProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .promptProfile("pm")
                .promptVersion("v999")
                .profession("测试角色")
                .build();

        String prompt = facade.resultJudgePrompt(session);

        assertTrue(prompt.contains("子任务结果评审 Agent"));
        assertTrue(prompt.contains("职业：测试角色"));
    }

    @Test
    void templateServiceShouldReplaceMissingPlaceholderWithEmptyString() {
        assertTrue(templateService.exists("prompt/tests/template-placeholder.md"));
        assertFalse(templateService.exists("prompt/tests/not-exist.md"));

        String rendered = templateService.render(
                "prompt/tests/template-placeholder.md",
                Map.of("a", "1", "c", "3"));

        assertEquals("A=1,B=,C=3\n", rendered.replace("\r\n", "\n"));
    }

    private PlannerProperties baseProperties() {
        PlannerProperties properties = new PlannerProperties();
        properties.getPrompt().setProfile("default");
        properties.getPrompt().setVersion("v1");
        properties.getPrompt().setFallbackProfile("default");
        properties.getPrompt().setFallbackVersion("v1");
        properties.getPrompt().setDefaultProfession("DefaultProfession");
        properties.getPrompt().setDefaultIndustry("DefaultIndustry");
        properties.getPrompt().setDefaultAudience("DefaultAudience");
        properties.getPrompt().setDefaultTone("DefaultTone");
        properties.getPrompt().setDefaultLanguage("zh-CN");
        return properties;
    }
}
