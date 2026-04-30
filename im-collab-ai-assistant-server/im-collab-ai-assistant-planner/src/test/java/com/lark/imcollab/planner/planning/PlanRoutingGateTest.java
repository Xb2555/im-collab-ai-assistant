package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRoutingGateTest {

    private final PlanRoutingGate gate = new PlanRoutingGate();

    @Test
    void simpleExplicitDeliverablesAllowFastPlan() {
        PlanRoutingDecision decision = gate.decide("生成技术方案文档（含 Mermaid 架构图），并准备配套 PPT", null);

        assertThat(decision.route()).isEqualTo(PlanRoute.FAST_PLAN);
        assertThat(decision.allowsFastPlan()).isTrue();
        assertThat(decision.needsContextCollection()).isFalse();
    }

    @Test
    void contextCollectionRequestBlocksFastPlanEvenWithDocAndPpt() {
        PlanRoutingDecision decision = gate.decide(
                "请自动收集最近两周飞书群聊、相关项目文档和历史决策记录，对比三种技术推进方案，最后输出决策分析文档和管理层汇报 PPT",
                null
        );

        assertThat(decision.route()).isEqualTo(PlanRoute.COLLECT_CONTEXT);
        assertThat(decision.allowsFastPlan()).isFalse();
        assertThat(decision.needsContextCollection()).isTrue();
        assertThat(decision.reasons()).contains("requires collecting external or workspace context");
    }

    @Test
    void deepPlanningRequestBlocksFastPlan() {
        PlanRoutingDecision decision = gate.decide("对比三个技术方案，评估成本、风险、资源依赖，并给老板一个决策建议", null);

        assertThat(decision.route()).isEqualTo(PlanRoute.DEEP_PLANNING);
        assertThat(decision.allowsFastPlan()).isFalse();
        assertThat(decision.reasons()).contains("requires comparing alternatives", "requires strategic analysis");
    }

    @Test
    void workspaceContextWithMultipleReferencesBlocksFastPlan() {
        WorkspaceContext context = WorkspaceContext.builder()
                .docRefs(List.of("doc-1", "doc-2"))
                .selectedMessages(List.of("m1", "m2", "m3", "m4"))
                .build();

        PlanRoutingDecision decision = gate.decide("生成技术方案文档和 PPT", context);

        assertThat(decision.route()).isEqualTo(PlanRoute.COLLECT_CONTEXT);
        assertThat(decision.allowsFastPlan()).isFalse();
        assertThat(decision.reasons()).contains("contains multiple selected messages", "contains multiple document references");
    }
}
