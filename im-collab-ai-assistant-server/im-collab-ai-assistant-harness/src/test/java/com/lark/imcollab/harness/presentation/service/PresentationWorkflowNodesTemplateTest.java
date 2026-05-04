package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresentationWorkflowNodesTemplateTest {

    private final PresentationWorkflowNodes nodes = new PresentationWorkflowNodes(
            null, null, null, null, null, null, new ObjectMapper());

    @Test
    void deepTechTimelineUsesGradientAndTimelineShapes() {
        String xml = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(2)
                        .title("实施路径")
                        .layout("timeline")
                        .keyPoints(List.of("完成需求澄清", "生成技术方案", "输出汇报材料"))
                        .speakerNotes("按时间顺序讲清楚推进路径。")
                        .build(),
                2,
                4,
                PresentationGenerationOptions.builder()
                        .style("deep-tech")
                        .density("standard")
                        .speakerNotes(true)
                        .build());

        assertThat(xml)
                .contains("<slide xmlns=\"http://www.larkoffice.com/sml/2.0\">")
                .contains("linear-gradient(135deg,rgba(15,23,42,1)")
                .contains("startX=\"130\" startY=\"280\"")
                .contains("<note>")
                .contains("实施路径");
    }

    @Test
    void conciseBusinessMetricCardsLimitsVisiblePoints() {
        String xml = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(3)
                        .title("关键指标")
                        .layout("metric-cards")
                        .keyPoints(List.of("预算风险需要持续跟进", "交付周期仍需确认", "合规暂无明显问题", "售后响应待补充"))
                        .build(),
                3,
                5,
                PresentationGenerationOptions.builder()
                        .style("business-light")
                        .density("concise")
                        .speakerNotes(false)
                        .build());

        assertThat(xml)
                .contains("rgb(30,60,114)")
                .contains("预算风险需要持续跟进", "交付周期仍需确认", "合规暂无明显问题")
                .doesNotContain("售后响应待补充")
                .doesNotContain("<note>");
    }

    @Test
    void generationOptionsUsePptStepSummaryAndRespectExplicitStyle() {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        when(support.findPptStep("task-1")).thenReturn(Optional.of(TaskStepRecord.builder()
                .taskId("task-1")
                .name("生成Planner上下文拉取与PPT Agent生成能力建设方案评审PPT")
                .inputSummary("生成一份5页浅色商务风中文PPT，详细版，不要演讲备注，面向产品和工程团队做方案评审")
                .build()));
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                support, null, null, null, null, null, new ObjectMapper());

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-1",
                Task.builder().rawInstruction("生成Planner能力建设PPT").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(5);
        assertThat(options.getStyle()).isEqualTo("business-light");
        assertThat(options.getDensity()).isEqualTo("detailed");
        assertThat(options.isSpeakerNotes()).isFalse();
    }

    @Test
    void generationOptionsKeepMinimalProfessionalWhenExplicitlyRequested() {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        when(support.findPptStep("task-2")).thenReturn(Optional.of(TaskStepRecord.builder()
                .taskId("task-2")
                .name("生成PPT Agent真实IM闭环测试PPT（2页简约专业风）")
                .inputSummary("生成一份2页简约专业风中文PPT，第一页说明测试目标，第二页说明生成链路和风险，无演讲备注。")
                .build()));
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                support, null, null, null, null, null, new ObjectMapper());

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-2",
                Task.builder().rawInstruction("面向团队同步，生成PPT").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(2);
        assertThat(options.getStyle()).isEqualTo("minimal-professional");
        assertThat(options.isSpeakerNotes()).isFalse();
    }

    @Test
    void generationOptionsPreferExplicitDeepTechOverManagementAudience() {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        when(support.findPptStep("task-3")).thenReturn(Optional.of(TaskStepRecord.builder()
                .taskId("task-3")
                .name("生成8页深色科技风中文PPT（面向比赛评委和管理层）")
                .inputSummary("生成8页深色科技风中文PPT，面向比赛评委和管理层，详细版，无演讲备注。")
                .build()));
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                support, null, null, null, null, null, new ObjectMapper());

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-3",
                Task.builder().rawInstruction("主题：Agent-Pilot 一键智能闭环").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(8);
        assertThat(options.getStyle()).isEqualTo("deep-tech");
        assertThat(options.getDensity()).isEqualTo("detailed");
        assertThat(options.isSpeakerNotes()).isFalse();
    }
}
