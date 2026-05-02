package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerConversationCancelTests {

    @Test
    void shouldReplyToUnknownNewConversationWithoutCreatingTaskOrRunningGraph() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-chat", false, "LARK:chat-1"));
        when(intakeService.decide(any(PlanTaskSession.class), eq("哈哈哈"), eq(null), eq(false)))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.UNKNOWN,
                        "哈哈哈",
                        "small talk",
                        "我在。你把想整理的材料或目标发我就行。"
                ));

        PlanTaskSession result = service.handlePlanRequest("哈哈哈", workspaceContext, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-chat");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.UNKNOWN);
        assertThat(result.getIntakeState().getAssistantReply()).contains("我在");
        verifyNoInteractions(sessionService, graphRunner, taskBridgeService);
    }

    @Test
    void shouldRouteCancelTaskToAbortSession() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession abortedSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(session);
        when(intakeService.decide(session, "\u53d6\u6d88\u4efb\u52a1", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CANCEL_TASK, "\u53d6\u6d88\u4efb\u52a1"));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("\u53d6\u6d88\u4efb\u52a1"), eq(workspaceContext), eq(null)))
                .thenReturn(abortedSession);

        PlanTaskSession result = service.handlePlanRequest("\u53d6\u6d88\u4efb\u52a1", workspaceContext, null, null);

        assertThat(result).isSameAs(abortedSession);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("\u53d6\u6d88\u4efb\u52a1"), eq(workspaceContext), eq(null));
        verify(taskBridgeService).ensureTask(abortedSession);
    }

    @Test
    void shouldPassOriginalUserInputToGraphWhenIntentClassifierNormalizesText() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-raw")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession readySession = PlanTaskSession.builder()
                .taskId("task-raw")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        String original = "根据我没发给你的那份客户合同，整理一份风险摘要给法务看";
        String normalized = "根据客户合同整理风险摘要给法务";

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-raw", false, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-raw")).thenReturn(session);
        when(intakeService.decide(any(PlanTaskSession.class), eq(original), eq(null), eq(false)))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, normalized, "llm normalized input", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-raw"), eq(original), eq(workspaceContext), eq(null)))
                .thenReturn(readySession);

        PlanTaskSession result = service.handlePlanRequest(original, workspaceContext, null, null);

        assertThat(result).isSameAs(readySession);
        assertThat(session.getRawInstruction()).isEqualTo(original);
        assertThat(session.getIntakeState().getLastUserMessage()).isEqualTo(original);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-raw"), eq(original), eq(workspaceContext), eq(null));
    }
}
