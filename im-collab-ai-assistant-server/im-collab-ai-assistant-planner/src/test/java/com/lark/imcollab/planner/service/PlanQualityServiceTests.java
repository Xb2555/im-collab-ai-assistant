package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanQualityServiceTests {

    private final PlanQualityService service = new PlanQualityService(new ObjectMapper(), List.of());

    @Test
    void shouldMergeAdditivePlanAdjustmentIntoExistingBlueprint() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("整理这周 AI 协同助手项目进展，输出给老板看的汇报材料")
                        .deliverables(List.of("DOC"))
                        .successCriteria(List.of(
                                "文档包含明确进展完成率（如X/Y里程碑已交付）",
                                "识别并陈述≥1个当前关键风险及其缓解状态",
                                "列出下周3项最高优先级行动项"
                        ))
                        .risks(List.of(
                                "未明确截止日期可能导致交付节奏偏差",
                                "缺少对关键问题和资源诉求的优先级定义，影响内容权重分配"
                        ))
                        .planCards(List.of(
                                UserPlanCard.builder()
                                        .cardId("card-1")
                                        .taskId("task-1")
                                        .type(PlanCardTypeEnum.DOC)
                                        .title("生成AI协同助手项目周报（老板版）")
                                        .description("整理本周AI协同助手项目进展，输出面向老板的精炼汇报文档")
                                        .build()
                        ))
                        .build())
                .build();

        PlanBlueprint updatedBlueprint = PlanBlueprint.builder()
                .taskBrief("成功标准再加一条，获取进展中老板的要求来作为标准")
                .deliverables(List.of("DOC"))
                .successCriteria(List.of("以进展中老板的实际要求为成功判定依据"))
                .risks(List.of("老板要求未被完整记录，导致新增标准缺乏依据"))
                .planCards(List.of(
                        UserPlanCard.builder()
                                .cardId("card-2")
                                .taskId("task-1")
                                .type(PlanCardTypeEnum.DOC)
                                .title("更新成功标准文档")
                                .description("在现有成功标准中新增一条")
                                .build()
                ))
                .build();

        service.applyPlanAdjustment(session, updatedBlueprint, "成功标准再加一条，获取进展中老板的要求来作为标准");

        assertThat(session.getPlanBlueprint().getTaskBrief())
                .isEqualTo("整理这周 AI 协同助手项目进展，输出给老板看的汇报材料");
        assertThat(session.getPlanBlueprint().getDeliverables()).containsExactly("DOC");
        assertThat(session.getPlanBlueprint().getSuccessCriteria()).containsExactly(
                "文档包含明确进展完成率（如X/Y里程碑已交付）",
                "识别并陈述≥1个当前关键风险及其缓解状态",
                "列出下周3项最高优先级行动项",
                "以进展中老板的实际要求为成功判定依据"
        );
        assertThat(session.getPlanBlueprint().getRisks()).containsExactly(
                "未明确截止日期可能导致交付节奏偏差",
                "缺少对关键问题和资源诉求的优先级定义，影响内容权重分配"
        );
        assertThat(session.getPlanBlueprint().getPlanCards()).hasSize(1);
        assertThat(session.getPlanBlueprint().getPlanCards().get(0).getTitle())
                .isEqualTo("生成AI协同助手项目周报（老板版）");
    }
}
