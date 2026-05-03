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
    void contextToolAcceptsEmbeddedMaterialInInstruction() {
        PlannerContextTool tool = new PlannerContextTool();
        String instruction = "基于这段材料整理成面向老板的文档：飞书项目协作方案要解决项目沟通分散、任务追踪困难的问题，目标是统一任务、文档和消息协作，让老板快速看到进展。";
        WorkspaceContext context = WorkspaceContext.builder()
                .selectedMessages(List.of(instruction))
                .build();

        ContextSufficiencyResult result = tool.evaluateContext(null, instruction, context);

        assertThat(result.sufficient()).isTrue();
        assertThat(result.reason()).contains("embedded instruction context");
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
    void contextNodeDoesNotLetModelOverrideMissingSourceGuard() throws Exception {
        ReactAgent contextCollectorAgent = mock(ReactAgent.class);
        ReactAgent contextAcquisitionAgent = mock(ReactAgent.class);
        PlannerRuntimeTool runtimeTool = mock(PlannerRuntimeTool.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-ctx").build();
        when(memoryService.renderContext(session)).thenReturn("");
        when(contextAcquisitionAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.empty());
        when(contextCollectorAgent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"sufficient":true,"contextSummary":"model tried to allow planning","missingItems":[],"clarificationQuestion":"","reason":"model semantic override","collectionRequired":false}
                        """)
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
                "请生成一份三步以内的项目复盘文档计划，先不要执行。",
                WorkspaceContext.builder()
                        .chatId("private-chat")
                        .chatType("p2p")
                        .inputSource("LARK_PRIVATE_CHAT")
                        .build()
        );

        assertThat(result.sufficient()).isFalse();
        assertThat(result.reason()).contains("no source material");
        assertThat(result.clarificationQuestion()).contains("项目背景");
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
