package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PlannerContextAcquisitionFacade;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.gate.PlannerCapabilityPolicy;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerToolsTest {

    @Test
    void contextToolSummarizesWorkspaceContext() {
        PlannerContextTool tool = new PlannerContextTool();
        WorkspaceContext context = WorkspaceContext.builder()
                .selectedMessages(List.of("项目目标是生成技术方案"))
                .timeRange("2026-04-01/2026-04-30")
                .build();

        ContextSufficiencyResult result = tool.evaluateContext(null, "生成技术方案文档", context);

        assertThat(result.sufficient()).isTrue();
        assertThat(result.contextSummary())
                .contains("生成技术方案文档")
                .contains("selectedMessages=1")
                .contains("timeRange=2026-04-01/2026-04-30");
    }

    @Test
    void contextToolDoesNotMakeSemanticDecisionForEmbeddedMaterial() {
        PlannerContextTool tool = new PlannerContextTool();
        String instruction = "基于这段材料整理成面向老板的文档：飞书项目协作方案要解决项目沟通分散、任务追踪困难的问题，目标是统一任务、文档和消息协作，让老板快速看到进展。";
        WorkspaceContext context = WorkspaceContext.builder()
                .selectedMessages(List.of(instruction))
                .build();

        ContextSufficiencyResult result = tool.evaluateContext(null, instruction, context);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
    }

    @Test
    void contextToolDefersMultiSectionInlineTaskContextToLlm() {
        PlannerContextTool tool = new PlannerContextTool();
        String instruction = "新开一个任务：请基于以下整个任务上下文生成中文项目进展摘要。上下文标记：IMC6A4。任务目标：验证 SUMMARY 是否能总结整个任务上下文。已完成：Planner 上下文拉取、IM 自然回复、纯 SUMMARY 独立执行链路。当前步骤：补充 SUMMARY 输入。风险：PPT Slides 授权偶发失败。下一步：做失败重试闭环和 GUI 刷新恢复验证。";

        ContextSufficiencyResult result = tool.evaluateContext(null, instruction, null);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
    }

    @Test
    void contextToolAsksForContextWhenInstructionIsVague() {
        PlannerContextTool tool = new PlannerContextTool();

        ContextSufficiencyResult result = tool.evaluateContext(null, "帮我整理一下，给老板看", null);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.clarificationQuestion()).contains("基于哪些材料");
    }

    @Test
    void contextToolAsksForSourceMaterialWhenPlanConstraintHasNoMaterial() {
        PlannerContextTool tool = new PlannerContextTool();

        ContextSufficiencyResult result = tool.evaluateContext(
                null,
                "请生成一份三步以内的项目复盘文档计划，先不要执行。",
                WorkspaceContext.builder()
                        .chatId("private-chat")
                        .chatType("p2p")
                        .inputSource("LARK_PRIVATE_CHAT")
                        .build()
        );

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
        assertThat(result.clarificationQuestion())
                .contains("项目背景")
                .contains("消息范围");
    }

    @Test
    void contextToolDoesNotTreatTestLabelPrefixAsEmbeddedMaterial() {
        PlannerContextTool tool = new PlannerContextTool();

        ContextSufficiencyResult result = tool.evaluateContext(
                null,
                "IM澄清测试-121026：请生成一份三步以内的项目复盘文档计划，先不要执行。",
                WorkspaceContext.builder()
                        .chatId("private-chat")
                        .chatType("p2p")
                        .inputSource("LARK_PRIVATE_CHAT")
                        .build()
        );

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
    }

    @Test
    void contextToolTreatsDocRefsAsHintsNotCollectedMaterial() {
        PlannerContextTool tool = new PlannerContextTool();
        WorkspaceContext context = WorkspaceContext.builder()
                .docRefs(List.of("https://example.feishu.cn/docx/doc_token"))
                .build();

        ContextSufficiencyResult result = tool.evaluateContext(null, "整理一下", context);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.contextSummary()).isBlank();
    }

    @Test
    void contextToolDoesNotTreatPrefixedCurrentMessageAsCollectedMaterial() {
        PlannerContextTool tool = new PlannerContextTool();
        WorkspaceContext context = WorkspaceContext.builder()
                .selectedMessages(List.of("【IM闭环】把刚才讨论的内容整理成项目摘要"))
                .chatId("chat-1")
                .timeRange("1777707108031")
                .build();

        ContextSufficiencyResult result = tool.evaluateContext(null, "把刚才讨论的内容整理成项目摘要", context);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
    }

    @Test
    void contextNodeUsesAcquisitionClarificationWhenNoSourceRefIsAvailable() throws Exception {
        ReactAgent contextCollectorAgent = mock(ReactAgent.class);
        ReactAgent contextAcquisitionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerProperties properties = new PlannerProperties();
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(memoryService.renderContext(session)).thenReturn("");
        when(contextAcquisitionAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"needCollection":false,"sources":[],"reason":"the referenced document was not provided","clarificationQuestion":"请把客户合同链接或关键条款发给我，我再整理风险摘要。"}
                        """)
        ))));
        ContextNodeService service = new ContextNodeService(
                contextCollectorAgent,
                contextAcquisitionAgent,
                new PlannerContextTool(),
                runtimeTool,
                memoryService,
                properties,
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx",
                "根据我没发给你的那份客户合同，整理一份风险摘要给法务看",
                WorkspaceContext.builder().build()
        );

        assertThat(result.sufficient()).isFalse();
        assertThat(result.collectionRequired()).isFalse();
        assertThat(result.clarificationQuestion()).contains("客户合同");
    }

    @Test
    void contextNodeLetsContextCollectorJudgeInlineTaskContextBeforeClarifying() throws Exception {
        ReactAgent contextCollectorAgent = mock(ReactAgent.class);
        ReactAgent contextAcquisitionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(memoryService.renderContext(session)).thenReturn("");
        when(contextAcquisitionAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"needCollection":false,"sources":[],"reason":"no workspace source ref","clarificationQuestion":"请补充项目背景。"}
                        """)
        ))));
        when(contextCollectorAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                List.of(new AssistantMessage("thinking"), new AssistantMessage("""
                                {"sufficient":true,"contextSummary":"用户在整句里提供了任务目标、已完成事项、风险和下一步，可生成摘要。","missingItems":[],"clarificationQuestion":"","reason":"inline task context judged sufficient by model","collectionRequired":false}
                                """))
        ))));
        ContextNodeService service = new ContextNodeService(
                contextCollectorAgent,
                contextAcquisitionAgent,
                new PlannerContextTool(),
                runtimeTool,
                memoryService,
                new PlannerProperties(),
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx",
                "新开一个任务：请基于以下整个任务上下文生成中文项目进展摘要。任务目标：验证 SUMMARY。已完成：上下文拉取和 IM 自然回复。风险：Slides 授权偶发失败。下一步：做失败重试闭环。",
                WorkspaceContext.builder().build()
        );

        assertThat(result.sufficient()).isTrue();
        assertThat(result.reason()).contains("inline task context judged sufficient");
    }

    @Test
    void contextNodeUsesAcquisitionAgentForPrivateChatGroupContextDecision() throws Exception {
        ReactAgent acquisitionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(acquisitionAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"needCollection":false,"sources":[],"reason":"private chat is not the requested group context","clarificationQuestion":"你想总结哪个群、哪段时间的消息？可以在群里直接提我，或把要总结的消息范围发给我。"}
                        """)
        ))));
        ContextNodeService service = new ContextNodeService(
                mock(ReactAgent.class),
                acquisitionAgent,
                new PlannerContextTool(),
                runtimeTool,
                new PlannerConversationMemoryService(new PlannerProperties()),
                new PlannerProperties(),
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx",
                "帮我总结群里消息并生成一个总结文档",
                WorkspaceContext.builder()
                        .chatId("chat-private")
                        .threadId("thread-private")
                        .chatType("p2p")
                        .inputSource("LARK_PRIVATE_CHAT")
                        .build()
        );

        assertThat(result.collectionRequired()).isFalse();
        assertThat(result.sufficient()).isFalse();
        assertThat(result.clarificationQuestion()).contains("哪个群");
    }

    @Test
    void contextNodeUsesAcquisitionAgentForGroupHistoryCollection() throws Exception {
        ReactAgent acquisitionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(acquisitionAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"needCollection":true,"sources":[{"sourceType":"IM_HISTORY","chatId":"chat-group","threadId":"","timeRange":"","docRefs":[],"limit":30}],"reason":"user asks for available group chat history","clarificationQuestion":""}
                        """)
        ))));
        ContextNodeService service = new ContextNodeService(
                mock(ReactAgent.class),
                acquisitionAgent,
                new PlannerContextTool(),
                runtimeTool,
                new PlannerConversationMemoryService(new PlannerProperties()),
                new PlannerProperties(),
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx",
                "帮我总结群里消息并生成一个总结文档",
                WorkspaceContext.builder()
                        .chatId("chat-group")
                        .chatType("group")
                        .inputSource("LARK_GROUP")
                        .build()
        );

        assertThat(result.collectionRequired()).isTrue();
        assertThat(result.acquisitionPlan()).isNotNull();
        assertThat(result.acquisitionPlan().getSources()).hasSize(1);
        assertThat(result.acquisitionPlan().getSources().get(0).getChatId()).isEqualTo("chat-group");
    }

    @Test
    void contextNodeRepairsImSearchTimeRangeWhenAgentOmitsStartAndEnd() throws Exception {
        ReactAgent acquisitionAgent = mock(ReactAgent.class);
        ReactAgent timeWindowResolutionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(acquisitionAgent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of(
                        "messages",
                        new AssistantMessage("""
                                {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"chat-group","threadId":"","timeRange":"昨天下午","docRefs":[],"selectionInstruction":"根据昨天下午的消息梳理一下消息内容总结成文档","limit":30}],"reason":"user asks for yesterday afternoon messages","clarificationQuestion":""}
                                """)
                ))));
        when(timeWindowResolutionAgent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of(
                        "messages",
                        new AssistantMessage("""
                                {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"chat-group","threadId":"","timeRange":"昨天下午","start_time":"2026-05-04T12:00:00+08:00","end_time":"2026-05-04T18:00:00+08:00","docRefs":[],"selectionInstruction":"根据昨天下午的消息梳理一下消息内容总结成文档","limit":30}],"reason":"repaired relative time range","clarificationQuestion":""}
                                """)
                ))));
        ContextNodeService service = new ContextNodeService(
                mock(ReactAgent.class),
                acquisitionAgent,
                timeWindowResolutionAgent,
                new PlannerContextTool(),
                runtimeTool,
                new PlannerConversationMemoryService(new PlannerProperties()),
                new PlannerProperties(),
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx",
                "根据昨天下午的消息梳理一下消息内容总结成文档",
                WorkspaceContext.builder()
                        .chatId("chat-group")
                        .chatType("group")
                        .inputSource("LARK_GROUP")
                        .build()
        );

        assertThat(result.collectionRequired()).isTrue();
        assertThat(result.acquisitionPlan().getSources()).hasSize(1);
        assertThat(result.acquisitionPlan().getSources().get(0).getSourceType())
                .isEqualTo(ContextSourceTypeEnum.IM_MESSAGE_SEARCH);
        assertThat(result.acquisitionPlan().getSources().get(0).getStartTime())
                .isEqualTo("2026-05-04T12:00:00+08:00");
        assertThat(result.acquisitionPlan().getSources().get(0).getEndTime())
                .isEqualTo("2026-05-04T18:00:00+08:00");
        verify(acquisitionAgent, times(1)).invoke(anyString(), any(RunnableConfig.class));
        verify(timeWindowResolutionAgent, times(1)).invoke(anyString(), any(RunnableConfig.class));
    }

    @Test
    void contextNodeRepairsAbsoluteChineseTimeWindowViaResolutionAgent() throws Exception {
        ReactAgent acquisitionAgent = mock(ReactAgent.class);
        ReactAgent timeWindowResolutionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx-abs").build();
        when(acquisitionAgent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of(
                        "messages",
                        new AssistantMessage("""
                                {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"chat-group","threadId":"","query":"智能工作流项目","timeRange":"5月7号凌晨2点到2点06分","selectionInstruction":"根据5月7号凌晨2点到2点06分关于智能工作流项目的消息，总结成给老板看的汇报","limit":30}],"reason":"user asks for a precise historical time window","clarificationQuestion":""}
                                """)
                ))));
        when(timeWindowResolutionAgent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of(
                        "messages",
                        new AssistantMessage("""
                                {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"chat-group","threadId":"","query":"智能工作流项目","timeRange":"5月7号凌晨2点到2点06分","start_time":"2026-05-07T02:00:00+08:00","end_time":"2026-05-07T02:06:00+08:00","selectionInstruction":"根据5月7号凌晨2点到2点06分关于智能工作流项目的消息，总结成给老板看的汇报","limit":30}],"reason":"resolved absolute chinese time window","clarificationQuestion":""}
                                """)
                ))));
        ContextNodeService service = new ContextNodeService(
                mock(ReactAgent.class),
                acquisitionAgent,
                timeWindowResolutionAgent,
                new PlannerContextTool(),
                runtimeTool,
                new PlannerConversationMemoryService(new PlannerProperties()),
                new PlannerProperties(),
                new ObjectMapper()
        );

        ContextSufficiencyResult result = service.check(
                session,
                "task-ctx-abs",
                "根据5月7号凌晨2点到2点06分关于智能工作流项目的消息，总结成给老板看的汇报",
                WorkspaceContext.builder()
                        .chatId("chat-group")
                        .chatType("group")
                        .inputSource("LARK_GROUP")
                        .build()
        );

        assertThat(result.collectionRequired()).isTrue();
        assertThat(result.acquisitionPlan()).isNotNull();
        assertThat(result.acquisitionPlan().getSources()).hasSize(1);
        assertThat(result.acquisitionPlan().getSources().get(0).getStartTime())
                .isEqualTo("2026-05-07T02:00:00+08:00");
        assertThat(result.acquisitionPlan().getSources().get(0).getEndTime())
                .isEqualTo("2026-05-07T02:06:59+08:00");
    }

    @Test
    void reviewToolRejectsUnsupportedCardTypeAndPassesSupportedCards() {
        PlannerReviewTool tool = new PlannerReviewTool(new PlannerCapabilityPolicy());
        PlanTaskSession supported = PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();

        assertThat(tool.review(supported).passed()).isTrue();

        PlanTaskSession unsupported = PlanTaskSession.builder()
                .taskId("task-2")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("未知产物")
                        .build()))
                .build();

        PlanReviewResult result = tool.review(unsupported);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> assertThat(issue).contains("unsupported"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextAcquisitionToolMergesCollectedMessagesAndDocs() {
        PlannerContextAcquisitionFacade facade = mock(PlannerContextAcquisitionFacade.class);
        ObjectProvider<PlannerContextAcquisitionFacade> provider = mock(ObjectProvider.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-1").build();
        when(provider.getIfAvailable()).thenReturn(facade);
        when(sessionService.get("task-1")).thenReturn(session);
        when(facade.acquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ContextAcquisitionResult.builder()
                        .success(true)
                        .sufficient(true)
                        .contextSummary("已读取 2 条聊天记录和 1 份文档摘录")
                        .selectedMessages(List.of("A：目标是生成技术方案", "B：需要给老板看"))
                        .selectedMessageIds(List.of("om_1", "om_2"))
                        .docFragments(List.of("文档《方案》摘录：背景和目标"))
                        .sourceRefs(List.of("im-search:chat:chat-1:query:方案"))
                        .build());
        PlannerContextAcquisitionTool tool = new PlannerContextAcquisitionTool(
                provider,
                sessionService,
                memoryService,
                runtimeTool
        );
        WorkspaceContext original = WorkspaceContext.builder()
                .chatId("chat-1")
                .selectedMessages(List.of("原始材料"))
                .build();

        ContextAcquisitionResult result = tool.acquireContext("task-1", "整理刚才讨论", original,
                ContextAcquisitionPlan.builder().needCollection(true).build());
        WorkspaceContext merged = tool.mergeWorkspaceContext(original, result);

        assertThat(result.isSuccess()).isTrue();
        assertThat(merged.getSelectedMessages())
                .contains("原始材料", "A：目标是生成技术方案", "文档《方案》摘录：背景和目标");
        assertThat(merged.getSelectedMessageIds()).containsExactly("om_1", "om_2");
        assertThat(merged.getInputSource()).isEqualTo("im-search:chat:chat-1:query:方案");
        verify(memoryService).appendAssistantTurn(session, "已收集上下文：已读取 2 条聊天记录和 1 份文档摘录");
    }

    @Test
    void contextAcquisitionNodeUsesToolClarificationWhenCollectionFindsNothing() {
        PlannerContextAcquisitionTool acquisitionTool = mock(PlannerContextAcquisitionTool.class);
        ContextAcquisitionNodeService service = new ContextAcquisitionNodeService(acquisitionTool);
        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .build();
        when(acquisitionTool.acquireContext(anyString(), anyString(), any(), any()))
                .thenReturn(ContextAcquisitionResult.builder()
                        .success(false)
                        .sufficient(false)
                        .message("没有读取到可用上下文。")
                        .clarificationQuestion("我按你给的条件查了一遍，但没有找到符合条件的消息。你想扩大时间范围，还是换个关键词？")
                        .build());

        ContextCollectionOutcome outcome = service.collect(
                "task-1",
                "拉取最近5分钟带有某个标记的消息并总结",
                WorkspaceContext.builder().chatId("chat-1").build(),
                plan
        );

        assertThat(outcome.contextResult().sufficient()).isFalse();
        assertThat(outcome.contextResult().clarificationQuestion())
                .contains("没有找到符合条件的消息")
                .doesNotContain("权限");
    }
}
