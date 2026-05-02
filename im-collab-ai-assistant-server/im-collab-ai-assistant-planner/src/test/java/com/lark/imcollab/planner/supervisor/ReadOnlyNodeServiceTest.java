package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyNodeServiceTest {

    @Test
    void unknownRoutePreservesNaturalIntakeReply() {
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        ReadOnlyNodeService service = new ReadOnlyNodeService(
                sessionService,
                memoryService,
                mock(PlannerRuntimeTool.class),
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-ack")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("生成技术方案文档")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .lastUserMessage("这个方案还行")
                        .assistantReply("好的，这版计划我先保留着。准备推进时回复“开始执行”，要改也可以直接说。")
                        .build())
                .build();
        when(sessionService.get("task-ack")).thenReturn(session);

        PlanTaskSession result = service.readOnly(
                "task-ack",
                "这个方案还行",
                PlannerSupervisorDecisionResult.builder()
                        .action(PlannerSupervisorAction.UNKNOWN)
                        .confidence(0.2d)
                        .reason("weak approval")
                        .needsClarification(true)
                        .clarificationQuestion("我没完全判断清楚你的意思。你是想查看计划、调整计划，还是开始执行？")
                        .build()
        );

        assertThat(result.getIntakeState().getAssistantReply()).contains("计划我先保留");
        assertThat(result.getIntakeState().getAssistantReply()).doesNotContain("没完全判断清楚");
        verify(memoryService).appendAssistantTurn(session, result.getIntakeState().getAssistantReply());
        verify(sessionService).saveWithoutVersionChange(session);
    }
}
