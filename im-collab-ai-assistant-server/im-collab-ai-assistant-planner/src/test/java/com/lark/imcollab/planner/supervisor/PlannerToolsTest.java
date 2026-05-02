package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.gate.PlannerCapabilityPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(result.clarificationQuestion()).contains("基于哪些内容");
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
}
