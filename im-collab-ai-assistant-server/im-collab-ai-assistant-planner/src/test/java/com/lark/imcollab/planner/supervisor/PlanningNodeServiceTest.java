package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ClarificationSourceMaterialAssessment;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.SourceArtifactRef;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.prompt.PromptContextKeys;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.ExecutionContractFactory;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PlanningNodeServiceTest {

    private final PlanningNodeService service = new PlanningNodeService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper()
    );

    @Test
    void extractsPlanBlueprintFromAssistantMessageJson() {
        AssistantMessage message = new AssistantMessage("""
                {"planCards":[{"cardId":"card-001","title":"生成技术方案文档","type":"DOC"}]}
                """);

        PlanBlueprint result = service.extractStructured(message, PlanBlueprint.class).orElseThrow();

        assertThat(result.getPlanCards()).hasSize(1);
        assertThat(result.getPlanCards().get(0).getTitle()).isEqualTo("生成技术方案文档");
    }

    @Test
    void extractsLatestAssistantJsonFromMessageList() {
        List<Object> messages = List.of(
                new UserMessage("生成技术方案文档"),
                new AssistantMessage("""
                        ```json
                        {"planCards":[{"cardId":"card-001","title":"生成技术方案文档","type":"DOC"},{"cardId":"card-002","title":"生成汇报 PPT 初稿","type":"PPT"}]}
                        ```
                        """)
        );

        PlanBlueprint result = service.extractStructured(messages, PlanBlueprint.class).orElseThrow();

        assertThat(result.getPlanCards())
                .extracting(card -> card.getType().name())
                .containsExactly("DOC", "PPT");
    }

    @Test
    void detectsEmptyBlueprintAsNeedingRepair() {
        assertThat(service.hasPlanCards(PlanBlueprint.builder().build())).isFalse();
        assertThat(service.hasPlanCards(PlanBlueprint.builder().planCards(List.of()).build())).isFalse();
        assertThat(service.hasPlanCards(PlanBlueprint.builder()
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build())).isTrue();
    }

    @Test
    void buildsBoundedExecutablePlanWhenAgentReturnsEmptyCards() {
        IntentSnapshot intent = IntentSnapshot.builder()
                .userGoal("根据飞书项目协作方案生成技术方案文档和汇报 PPT 初稿")
                .deliverableTargets(List.of("DOC", "PPT"))
                .build();

        PlanBlueprint result = service.buildBoundedExecutablePlan(
                "task-1",
                "User instruction: 根据飞书项目协作方案生成一份技术方案文档，包含 Mermaid 架构图，再生成一份汇报 PPT 初稿",
                intent
        ).orElseThrow();

        assertThat(result.getPlanCards()).hasSize(2);
        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getType)
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT);
        assertThat(result.getPlanCards().get(0).getTitle()).contains("文档");
        assertThat(result.getPlanCards().get(1).getDependsOn()).containsExactly("card-001");
        assertThat(result.getPlanCards().get(0).getAgentTaskPlanCards().get(0).getTaskType())
                .isEqualTo(AgentTaskTypeEnum.WRITE_DOC);
        assertThat(result.getPlanCards().get(1).getAgentTaskPlanCards().get(0).getTaskType())
                .isEqualTo(AgentTaskTypeEnum.WRITE_SLIDES);
    }

    @Test
    void boundedPlanTrustsIntentDeliverableWithoutKeywordRewrite() {
        IntentSnapshot intent = IntentSnapshot.builder()
                .userGoal("帮我总结群里消息并生成一个总结文档")
                .deliverableTargets(List.of("SUMMARY"))
                .build();

        PlanBlueprint result = service.buildBoundedExecutablePlan(
                "task-1",
                "User instruction: 帮我总结群里消息并生成一个总结文档",
                intent
        ).orElseThrow();

        assertThat(result.getPlanCards()).hasSize(1);
        assertThat(result.getPlanCards().get(0).getType()).isEqualTo(PlanCardTypeEnum.SUMMARY);
        assertThat(result.getPlanCards().get(0).getAgentTaskPlanCards().get(0).getTaskType())
                .isEqualTo(AgentTaskTypeEnum.GENERATE_SUMMARY);
    }

    @Test
    void persistsPlanBeforeGraphReviewReloadsSession() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("生成技术方案文档和 PPT")
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"生成技术方案文档和 PPT","deliverableTargets":["DOC","PPT"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成技术方案文档","type":"DOC"},{"cardId":"card-002","title":"生成 PPT","type":"PPT","dependsOn":["card-001"]}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan("task-1", "生成技术方案文档和 PPT", null, "");

        assertThat(result.getPlanCards()).hasSize(2);
        verify(sessionService, atLeast(2)).saveWithoutVersionChange(session);
    }

    @Test
    void passesSelectedMessagesIntoAgentPromptAndRunnableMetadata() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-context")
                .rawInstruction("基于选中消息生成项目复盘文档")
                .build();
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .selectedMessages(List.of("客户反馈：移动端审批入口太深", "方案结论：先优化首页快捷入口"))
                .timeRange("2026-05-01T00:00:00Z/2026-05-02T00:00:00Z")
                .build();
        when(sessionService.getOrCreate("task-context")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("用户刚刚选择了两条消息");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"基于选中消息生成项目复盘文档","deliverableTargets":["DOC"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成项目复盘文档","type":"DOC"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        planningService.plan("task-context", "基于选中消息生成项目复盘文档", workspaceContext, "");

        verify(intentAgent).invoke(contains("客户反馈：移动端审批入口太深"), any());
        ArgumentCaptor<com.alibaba.cloud.ai.graph.RunnableConfig> configCaptor = forClass(com.alibaba.cloud.ai.graph.RunnableConfig.class);
        verify(planningAgent).invoke(contains("方案结论：先优化首页快捷入口"), configCaptor.capture());
        Map<String, Object> metadata = configCaptor.getValue().metadata().orElse(Map.of());
        assertThat(metadata.get(PromptContextKeys.AGENT_NAME)).isEqualTo("planning-agent");
        assertThat((String) metadata.get(PromptContextKeys.CONTEXT))
                .contains("客户反馈：移动端审批入口太深")
                .contains("方案结论：先优化首页快捷入口");
    }

    @Test
    void asksUserWhenIntentReportsMissingSlots() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我整理一下，给老板看")
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"整理信息给老板看","deliverableTargets":["SUMMARY"],"missingSlots":["具体需要整理的内容范围","期望的输出格式"]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan("task-1", "帮我整理一下，给老板看", null, "");

        assertThat(result).isSameAs(session);
        verify(questionTool).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent, never()).invoke(anyString(), any());
    }

    @Test
    void sourceArtifactsSatisfySummaryMissingMaterialSlot() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("生成一段可直接发送的任务摘要")
                .build();
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .sourceArtifacts(List.of(SourceArtifactRef.builder()
                        .artifactId("artifact-ppt")
                        .taskId("task-1")
                        .artifactType(ArtifactTypeEnum.PPT)
                        .title("9191项目启动会汇报")
                        .preview("已有 DOC 和 PPT 产物，可作为任务摘要来源。")
                        .usage("PRIMARY_SOURCE")
                        .build()))
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"生成任务摘要","deliverableTargets":["SUMMARY"],"missingSlots":["需要整理的具体内容或材料"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成任务摘要","type":"SUMMARY"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        planningService.plan("task-1", "生成一段可直接发送的任务摘要", workspaceContext, "");

        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(contains("Source artifacts:"), any());
        assertThat(session.getPlanCards()).hasSize(1);
        assertThat(session.getPlanCards().get(0).getType()).isEqualTo(PlanCardTypeEnum.SUMMARY);
    }

    @Test
    void missingSlotQuestionDoesNotAskOutputTypeAgainWhenDeliverableKnown() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我整理一下，给老板看")
                .clarificationAnswers(List.of("文档"))
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"整理内容给老板看","deliverableTargets":["DOC"],"missingSlots":["需要整理的具体内容或材料"]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        planningService.plan("task-1", "文档", null, "文档");

        verify(questionTool).askUser(any(PlanTaskSession.class), argThat(questions ->
                questions != null
                        && questions.size() == 1
                        && questions.get(0).contains("产物形式我记下了")
                        && questions.get(0).contains("材料、文档链接")
                        && !questions.get(0).contains("输出成文档、PPT 还是摘要")
        ));
        verify(planningAgent, never()).invoke(anyString(), any());
    }

    @Test
    void unsupportedDeliverableQuestionOffersSupportedConversion() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我做个白板流程图")
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"制作白板流程图","deliverableTargets":["WHITEBOARD"],"missingSlots":["白板流程图的具体内容"]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        planningService.plan("task-1", "帮我做个白板流程图", null, "");

        verify(questionTool).askUser(any(PlanTaskSession.class), argThat(questions ->
                questions != null
                        && questions.size() == 1
                        && questions.get(0).contains("不能直接稳定生成")
                        && questions.get(0).contains("文档里的 Mermaid 图")
                        && questions.get(0).contains("PPT 页面")
        ));
        verify(planningAgent, never()).invoke(anyString(), any());
    }

    @Test
    void continuesPlanningWhenClarificationAnswerProvidesEnoughContext() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我整理一下，给老板看")
                .clarificationAnswers(List.of("基于这段材料整理成文档：飞书项目协作方案要解决沟通分散的问题。"))
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"整理飞书项目协作方案文档给老板看","deliverableTargets":["DOC"],"missingSlots":["老板的格式偏好"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成老板汇报文档","type":"DOC"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan("task-1", "帮我整理一下，给老板看", null, "补充材料");

        assertThat(result.getPlanCards()).hasSize(1);
        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(anyString(), any());
    }

    @Test
    void skipsIntentSourceCollectionWhenResumeFeedbackProvidesContext() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        ReactAgent clarificationSourceMaterialJudgeAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        ContextAcquisitionNodeService contextAcquisitionNodeService = mock(ContextAcquisitionNodeService.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-resume")
                .rawInstruction("根据指定材料生成采购评审文档")
                .clarificationAnswers(List.of("新建一版，要买qq糖，100块钱的QQ糖"))
                .build();
        when(sessionService.getOrCreate("task-resume")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"生成采购评审文档","deliverableTargets":["DOC"],"sourceScope":{"docRefs":["https://invalid-doc"]}}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成采购评审文档","type":"DOC"}]}
                        """)
        ))));
        when(clarificationSourceMaterialJudgeAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"canReplaceExternalSourceCollection":true,"reason":"clarification already includes concrete inline facts to draft from"}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                contextAcquisitionNodeService,
                clarificationSourceMaterialJudgeAgent,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan(
                "task-resume",
                "根据指定材料生成采购评审文档",
                null,
                "新建一版，要买qq糖，100块钱的QQ糖"
        );

        assertThat(result.getPlanCards()).hasSize(1);
        verify(contextAcquisitionNodeService, never()).collect(anyString(), anyString(), any(), any());
        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(contains("100块钱的QQ糖"), any());
    }

    @Test
    void doesNotSkipIntentSourceCollectionWhenFeedbackOnlyAdjustsPlan() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        ReactAgent clarificationSourceMaterialJudgeAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        ContextAcquisitionNodeService contextAcquisitionNodeService = mock(ContextAcquisitionNodeService.class);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-im-source")
                .rawInstruction("根据5月7号凌晨2点到2点06分关于智能工作流项目的消息，总结成给老板看的汇报")
                .clarificationAnswers(List.of("然后根据摘要还要生成一份汇报文档，要5小节"))
                .build();
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("oc_group_1")
                .chatType("group")
                .messageId("om_current")
                .build();
        when(sessionService.getOrCreate("task-im-source")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"生成给老板看的汇报","deliverableTargets":["SUMMARY"],"sourceScope":{"timeRange":"2025-05-07 02:00:00 - 02:06:00"}}
                        """)
        ))));
        when(clarificationSourceMaterialJudgeAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"canReplaceExternalSourceCollection":false,"reason":"feedback only adjusts the downstream deliverable and does not contain source material"}
                        """)
        ))));
        when(contextAcquisitionNodeService.collect(anyString(), anyString(), any(), any())).thenReturn(
                new ContextCollectionOutcome(
                        ContextSufficiencyResult.sufficient("已读取 2 条聊天记录", "collected from im"),
                        WorkspaceContext.builder()
                                .chatId("oc_group_1")
                                .chatType("group")
                                .selectedMessages(List.of("A：智能工作流项目昨晚完成了联调。", "B：剩余风险是权限收口。"))
                                .selectedMessageIds(List.of("om_1", "om_2"))
                                .build()
                )
        );
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成老板汇报摘要","type":"SUMMARY"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                contextAcquisitionNodeService,
                clarificationSourceMaterialJudgeAgent,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan(
                "task-im-source",
                "根据5月7号凌晨2点到2点06分关于智能工作流项目的消息，总结成给老板看的汇报",
                workspaceContext,
                "然后根据摘要还要生成一份汇报文档，要5小节"
        );

        assertThat(result.getPlanCards()).hasSize(1);
        verify(contextAcquisitionNodeService).collect(anyString(), anyString(), any(), any());
        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(contains("Selected messages:"), any());
    }

    @Test
    void continuesPlanningWhenInstructionEmbedsUsableMaterial() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        String instruction = "基于这段材料整理成面向老板的文档：飞书项目协作方案要解决项目沟通分散、任务追踪困难的问题，目标是统一任务、文档和消息协作，让老板快速看到进展。";
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction(instruction)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"整理飞书项目协作方案文档给老板看","deliverableTargets":["DOC"],"missingSlots":["具体材料内容未提供","文档格式要求未明确"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成面向老板的项目协作文档","type":"DOC"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan("task-1", instruction, null, "");

        assertThat(result.getPlanCards()).hasSize(1);
        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(anyString(), any());
    }

    @Test
    void continuesPlanningWhenImInstructionEmbedsMultilineMaterial() throws Exception {
        ReactAgent intentAgent = mock(ReactAgent.class);
        ReactAgent planningAgent = mock(ReactAgent.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        String instruction = """
                请根据下面材料生成一份3页以内的中文项目汇报PPT，直接生成飞书幻灯片，不要生成文档。
                材料：Planner已经支持GUI接口创建任务、确认执行、生成飞书Slides artifact；本轮要验证IM入口也能从自然语言到PPT链接完成闭环。
                """;
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-im-ppt")
                .rawInstruction(instruction)
                .build();
        when(sessionService.getOrCreate("task-im-ppt")).thenReturn(session);
        when(memoryService.renderContext(session)).thenReturn("");
        when(intentAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"userGoal":"生成项目汇报PPT","deliverableTargets":["PPT"],"missingSlots":["缺少可整理材料"]}
                        """)
        ))));
        when(planningAgent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"planCards":[{"cardId":"card-001","title":"生成项目汇报PPT","type":"PPT"}]}
                        """)
        ))));
        PlanningNodeService planningService = new PlanningNodeService(
                intentAgent,
                planningAgent,
                sessionService,
                new PlanQualityService(new ObjectMapper(), List.of(), new ExecutionContractFactory()),
                projectionService,
                memoryService,
                questionTool,
                new ObjectMapper()
        );

        PlanTaskSession result = planningService.plan("task-im-ppt", instruction, null, "");

        assertThat(result.getPlanCards()).hasSize(1);
        verify(questionTool, never()).askUser(any(PlanTaskSession.class), any());
        verify(planningAgent).invoke(contains("生成飞书Slides artifact"), any());
    }
}
