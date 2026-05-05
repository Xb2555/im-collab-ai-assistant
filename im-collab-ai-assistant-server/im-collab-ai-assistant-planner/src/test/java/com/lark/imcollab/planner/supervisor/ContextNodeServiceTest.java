package com.lark.imcollab.planner.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextNodeServiceTest {

    @Test
    void extractsKeywordFromHistoricalDiscussionRequest() {
        assertThat(ContextNodeService.extractSearchQuery(
                "@飞书IM- test 整理一下之前历史消息中有关采购评审的讨论，输出一份采购分析文档，并生成评审ppt"
        )).isEqualTo("采购评审");
    }

    @Test
    void extractsKeywordFromAboutDiscussionRequest() {
        assertThat(ContextNodeService.extractSearchQuery("帮我整理一下关于供应商准入的消息"))
                .isEqualTo("供应商准入");
    }

    @Test
    void extractsKeywordWhenPointInTimeIsPresent() {
        assertThat(ContextNodeService.extractSearchQuery("拉取10分钟前关于采购评审的讨论"))
                .isEqualTo("采购评审");
    }

    @Test
    void acquisitionPromptRequiresConcreteStartAndEndTimeForRelativeTime() throws Exception {
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-time").build();
        when(memoryService.renderContext(session)).thenReturn("");
        ContextNodeService service = new ContextNodeService(
                null,
                null,
                new PlannerContextTool(),
                mock(PlannerRuntimeTool.class),
                memoryService,
                new PlannerProperties(),
                new ObjectMapper()
        );

        String prompt = buildAcquisitionPrompt(
                service,
                session,
                "根据昨天下午的消息梳理一下消息内容总结成文档",
                WorkspaceContext.builder().chatId("oc_1").chatType("group").build()
        );

        assertThat(prompt)
                .contains("Current time:")
                .contains("startTime and endTime MUST be non-empty")
                .contains("昨天下午")
                .contains("12:00:00 to 18:00:00")
                .contains("never output only a vague timeRange");
    }

    private String buildAcquisitionPrompt(
            ContextNodeService service,
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) throws Exception {
        Method method = ContextNodeService.class.getDeclaredMethod(
                "buildAcquisitionPrompt",
                PlanTaskSession.class,
                String.class,
                WorkspaceContext.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, session, rawInstruction, workspaceContext);
    }
}
