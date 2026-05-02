package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerConversationMemoryServiceTest {

    @Test
    void appendsUserAndAssistantTurnsIntoTaskSession() {
        PlannerProperties properties = new PlannerProperties();
        PlannerConversationMemoryService service = new PlannerConversationMemoryService(properties);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        service.appendUserTurn(session, "文档", TaskIntakeTypeEnum.CLARIFICATION_REPLY, "IM");
        service.appendAssistantTurn(session, "计划已生成：1.生成文档");

        assertThat(session.getConversationTurns()).hasSize(2);
        assertThat(session.getConversationTurns())
                .extracting(turn -> turn.getRole() + ":" + turn.getContent())
                .containsExactly("USER:文档", "ASSISTANT:计划已生成：1.生成文档");
    }

    @Test
    void compactsOldTurnsAndKeepsRecentConversation() {
        PlannerProperties properties = new PlannerProperties();
        properties.getMemory().setRecentTurns(2);
        PlannerConversationMemoryService service = new PlannerConversationMemoryService(properties);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        service.appendUserTurn(session, "第一轮", TaskIntakeTypeEnum.NEW_TASK, "GUI");
        service.appendAssistantTurn(session, "第一轮回复");
        service.appendUserTurn(session, "第二轮", TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "GUI");

        assertThat(session.getConversationTurns()).hasSize(2);
        assertThat(session.getConversationSummary()).contains("第一轮");
        assertThat(service.renderContext(session)).contains("Memory summary").contains("第二轮");
    }

    @Test
    void renderedContextContainsGoalCurrentPlanAndClarification() {
        PlannerProperties properties = new PlannerProperties();
        PlannerConversationMemoryService service = new PlannerConversationMemoryService(properties);
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我整理一下，给老板看")
                .clarifiedInstruction("帮我整理一下，给老板看，输出为文档")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .clarificationQuestions(List.of("你希望输出为文档、PPT，还是摘要？"))
                .clarificationAnswers(List.of("文档"))
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .type(PlanCardTypeEnum.DOC)
                        .title("生成整理文档")
                        .build()))
                .build();
        service.appendUserTurn(session, "文档", TaskIntakeTypeEnum.CLARIFICATION_REPLY, "IM");

        String context = service.renderContext(session);

        assertThat(context)
                .contains("Original goal: 帮我整理一下，给老板看")
                .contains("Clarified goal: 帮我整理一下，给老板看，输出为文档")
                .contains("Current plan: 1. card-001 DOC 生成整理文档")
                .contains("USER [PLAN_READY]: 文档");
    }
}
