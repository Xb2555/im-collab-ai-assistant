package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchItem;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchResult;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPlannerContextAcquisitionFacadeTest {

    @Test
    void imMessageSearchUsesCliResultsWithoutLlmSelection() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("采购评审", "oc_1", null, null, 50, 5))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        item("om_1", "采购评审：供应商A报价12万。", "user"),
                        item("om_bot", "采购评审：机器人提示。", "bot")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .query("采购评审")
                                .pageSize(50)
                                .pageLimit(5)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").messageId("current").build(),
                "整理历史消息中有关采购评审的讨论"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSelectedMessageIds()).containsExactly("om_1");
        assertThat(result.getSelectedMessages()).containsExactly("洪徐博：采购评审：供应商A报价12万。");
        assertThat(result.getSourceRefs()).containsExactly("im-search:chat:oc_1:query:采购评审");
        verify(selectionService, never()).select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void imMessageSearchFiltersSystemMessagesAndExpandsChatWindowPagination() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("", "oc_1", "2026-05-07T02:00:00+08:00", "2026-05-07T02:06:59+08:00", 30, 3))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        itemWithType("om_sys", "system", "洪徐博 started the group chat.", "user"),
                        itemWithType("om_1", "text", "智能工作流项目需要尽快同步营销方案。", "user"),
                        itemWithType("om_2", "text", "重点突出 AI Agent 自动化多端协同能力。", "user")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .timeRange("5月7号凌晨2点到2点06分")
                                .startTime("2026-05-07T02:00:00+08:00")
                                .endTime("2026-05-07T02:06:59+08:00")
                                .limit(30)
                                .pageSize(30)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "根据5月7号凌晨2点到2点06分的群消息来总结"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSelectedMessageIds()).containsExactly("om_1", "om_2");
        assertThat(result.getSelectedMessages()).containsExactly(
                "洪徐博：智能工作流项目需要尽快同步营销方案。",
                "洪徐博：重点突出 AI Agent 自动化多端协同能力。"
        );
        verify(searchTool).searchMessages("", "oc_1", "2026-05-07T02:00:00+08:00", "2026-05-07T02:06:59+08:00", 30, 3);
    }

    @Test
    void imMessageSearchLimitsFilteredMessages() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("采购评审", "oc_1", null, null, 50, 5))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        item("om_1", "采购评审：供应商A报价12万。", "user"),
                        item("om_bot", "采购评审：机器人提示。", "bot"),
                        item("om_2", "采购评审：供应商B报价13万。", "user"),
                        item("om_3", "采购评审：供应商C交付周期较短。", "user")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .query("采购评审")
                                .limit(2)
                                .pageSize(50)
                                .pageLimit(5)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").messageId("current").build(),
                "整理历史消息中有关采购评审的讨论"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSelectedMessageIds()).containsExactly("om_1", "om_2");
        assertThat(result.getSelectedMessages()).containsExactly(
                "洪徐博：采购评审：供应商A报价12万。",
                "洪徐博：采购评审：供应商B报价13万。"
        );
    }

    @Test
    void imMessageSearchSupportsKeywordAndTimeWindowTogether() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("采购评审", "oc_1", "2026-05-03T21:20:00+08:00", "2026-05-03T21:30:00+08:00", 50, 5))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        item("om_1", "采购评审：供应商A报价12万。", "user")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .query("采购评审")
                                .startTime("2026-05-03T21:20:00+08:00")
                                .endTime("2026-05-03T21:30:00+08:00")
                                .pageSize(50)
                                .pageLimit(5)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "整理这个时间窗里关于采购评审的讨论"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourceRefs()).containsExactly("im-search:chat:oc_1:query:采购评审:time:2026-05-03T21:20:00+08:00/2026-05-03T21:30:00+08:00");
        verify(selectionService, never()).select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void structuredStartAndEndOverrideOriginalRelativeTimePhrase() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("", "oc_1", "2026-05-04T12:00:00+08:00", "2026-05-04T18:00:00+08:00", 50, 1))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        item("om_1", "昨天下午讨论了总结文档结构。", "user")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .timeRange("昨天下午")
                                .startTime("2026-05-04T12:00:00+08:00")
                                .endTime("2026-05-04T18:00:00+08:00")
                                .selectionInstruction("根据昨天下午的消息梳理一下消息内容总结成文档")
                                .pageSize(50)
                                .pageLimit(1)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "根据昨天下午的消息梳理一下消息内容总结成文档"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourceRefs()).containsExactly("im-search:chat:oc_1:time:2026-05-04T12:00:00+08:00/2026-05-04T18:00:00+08:00");
        verify(searchTool).searchMessages("", "oc_1", "2026-05-04T12:00:00+08:00", "2026-05-04T18:00:00+08:00", 50, 1);
    }

    @Test
    void imMessageSearchTimeOnlyUsesStructuredStartAndEnd() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("", "oc_1", "2026-05-04T13:00:00+08:00", "2026-05-04T14:00:00+08:00", 50, 1))
                .thenReturn(new LarkMessageSearchResult(List.of(
                        item("om_1", "最近讨论：先汇总群里的评审口径。", "user")
                ), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .timeRange("2026-05-04T13:00:00+08:00/2026-05-04T14:00:00+08:00")
                                .startTime("2026-05-04T13:00:00+08:00")
                                .endTime("2026-05-04T14:00:00+08:00")
                                .selectionInstruction("整理本群最近60分钟的消息")
                                .pageSize(50)
                                .pageLimit(1)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "整理本群最近60分钟的消息"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSelectedMessageIds()).containsExactly("om_1");
        assertThat(result.getSourceRefs()).containsExactly("im-search:chat:oc_1:time:2026-05-04T13:00:00+08:00/2026-05-04T14:00:00+08:00");
        verify(selectionService, never()).select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void imMessageSearchWithoutStructuredTimeWindowFailsFast() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .timeRange("整理10分钟前关于采购评审的消息")
                                .query("采购评审")
                                .selectionInstruction("根据昨天下午的消息梳理一下消息内容总结成文档")
                                .pageSize(50)
                                .pageLimit(5)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "整理10分钟前关于采购评审的消息"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getClarificationQuestion()).contains("时间条件");
        verify(searchTool, never()).searchMessages(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void invalidStructuredTimeWindowFailsFast() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_1")
                                .timeRange("昨天 9 点到 10 点")
                                .startTime("2026-05-04T11:00:00+08:00")
                                .endTime("2026-05-04T10:00:00+08:00")
                                .pageSize(50)
                                .pageLimit(1)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_1").build(),
                "昨天 9 点到 10 点的消息"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getClarificationQuestion()).contains("时间条件");
        verify(searchTool, never()).searchMessages(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void emptyPrivateConversationWindowSuggestsUsingTargetGroup() {
        LarkMessageSearchTool searchTool = mock(LarkMessageSearchTool.class);
        LarkDocTool docTool = mock(LarkDocTool.class);
        ContextMessageSelectionService selectionService = mock(ContextMessageSelectionService.class);
        PlannerProperties properties = new PlannerProperties();
        when(searchTool.searchMessages("", "oc_private", "2026-05-04T12:00:00+08:00", "2026-05-04T18:00:00+08:00", 50, 1))
                .thenReturn(new LarkMessageSearchResult(List.of(), false, null));
        DefaultPlannerContextAcquisitionFacade facade = new DefaultPlannerContextAcquisitionFacade(
                searchTool,
                docTool,
                properties,
                selectionService
        );

        ContextAcquisitionResult result = facade.acquire(
                ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .sources(List.of(ContextSourceRequest.builder()
                                .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                                .chatId("oc_private")
                                .timeRange("昨天下午")
                                .startTime("2026-05-04T12:00:00+08:00")
                                .endTime("2026-05-04T18:00:00+08:00")
                                .pageSize(50)
                                .pageLimit(1)
                                .build()))
                        .build(),
                WorkspaceContext.builder().chatId("oc_private").chatType("p2p").build(),
                "根据昨天下午的消息梳理一下消息内容总结成文档"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getClarificationQuestion())
                .contains("当前这个私聊会话")
                .contains("某个群里");
    }

    private static LarkMessageSearchItem item(String messageId, String content, String senderType) {
        return itemWithType(messageId, "text", content, senderType);
    }

    private static LarkMessageSearchItem itemWithType(String messageId, String msgType, String content, String senderType) {
        return new LarkMessageSearchItem(
                messageId,
                null,
                msgType,
                "2026-05-04T10:00:00+08:00",
                false,
                false,
                "oc_1",
                "采购群",
                "ou_1",
                "洪徐博",
                senderType,
                content,
                List.of()
        );
    }
}
