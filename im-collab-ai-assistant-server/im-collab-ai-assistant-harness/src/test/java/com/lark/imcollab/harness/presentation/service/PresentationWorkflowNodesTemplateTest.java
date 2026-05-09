package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.harness.presentation.model.PresentationAssetPlan;
import com.lark.imcollab.harness.presentation.model.PresentationAssetResources;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXml;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresentationWorkflowNodesTemplateTest {

    private static final ReactAgent AGENT = mock(ReactAgent.class);

    private final PresentationWorkflowNodes nodes = new PresentationWorkflowNodes(
            null, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");

    @Test
    void deepTechTimelineUsesGradientAndTimelineShapes() {
        String xml = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(2)
                        .title("实施路径")
                        .layout("timeline")
                        .templateVariant("horizontal-milestones")
                        .keyPoints(List.of("完成需求澄清", "生成技术方案", "输出汇报材料"))
                        .speakerNotes("按时间顺序讲清楚推进路径。")
                        .build(),
                2,
                4,
                PresentationGenerationOptions.builder()
                        .style("deep-tech")
                        .themeFamily("deep-tech")
                        .density("standard")
                        .speakerNotes(true)
                        .templateDiversity("balanced")
                        .allowVariantMixing(true)
                        .build());

        assertThat(xml)
                .contains("<slide xmlns=\"http://www.larkoffice.com/sml/2.0\">")
                .contains("rgb(246,249,255)")
                .contains("fontSize=\"31\"")
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
                        .templateVariant("top-stripe-cards")
                        .keyPoints(List.of("预算风险需要持续跟进", "交付周期仍需确认", "合规暂无明显问题", "售后响应待补充"))
                        .build(),
                3,
                5,
                PresentationGenerationOptions.builder()
                        .style("business-light")
                        .themeFamily("business-light")
                        .density("concise")
                        .speakerNotes(false)
                        .templateDiversity("balanced")
                        .allowVariantMixing(true)
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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-1",
                Task.builder().rawInstruction("生成Planner能力建设PPT").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(5);
        assertThat(options.getStyle()).isEqualTo("business-light");
        assertThat(options.getThemeFamily()).isEqualTo("business-light");
        assertThat(options.getDensity()).isEqualTo("detailed");
        assertThat(options.isSpeakerNotes()).isFalse();
        assertThat(options.getTemplateDiversity()).isEqualTo("balanced");
        assertThat(options.isAllowVariantMixing()).isTrue();
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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-2",
                Task.builder().rawInstruction("面向团队同步，生成PPT").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(2);
        assertThat(options.getStyle()).isEqualTo("minimal-professional");
        assertThat(options.getThemeFamily()).isEqualTo("minimal-professional");
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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-3",
                Task.builder().rawInstruction("主题：Agent-Pilot 一键智能闭环").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(8);
        assertThat(options.getStyle()).isEqualTo("deep-tech");
        assertThat(options.getThemeFamily()).isEqualTo("deep-tech");
        assertThat(options.getDensity()).isEqualTo("detailed");
        assertThat(options.isSpeakerNotes()).isFalse();
    }

    @Test
    void sameThemeDifferentVariantsProduceDifferentStructures() {
        PresentationGenerationOptions options = PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("rich")
                .allowVariantMixing(true)
                .build();

        String railNotes = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(2)
                        .title("推进节奏")
                        .layout("section")
                        .templateVariant("rail-notes")
                        .keyPoints(List.of("完成对齐", "锁定方案", "组织评审"))
                        .build(),
                2, 5, options);
        String splitBand = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(3)
                        .title("推进节奏")
                        .layout("section")
                        .templateVariant("split-band")
                        .keyPoints(List.of("完成对齐", "锁定方案", "组织评审"))
                        .build(),
                3, 5, options);

        assertThat(railNotes).contains("topLeftX=\"44\" topLeftY=\"38\" width=\"12\" height=\"442\"");
        assertThat(splitBand).contains("topLeftX=\"0\" topLeftY=\"74\" width=\"960\" height=\"92\"");
        assertThat(railNotes).isNotEqualTo(splitBand);
    }

    @Test
    void fallbackOutlineRotatesTemplateVariants() throws Exception {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");

        var method = PresentationWorkflowNodes.class.getDeclaredMethod("fallbackOutline", com.lark.imcollab.harness.presentation.model.PresentationStoryline.class);
        method.setAccessible(true);
        var outline = (com.lark.imcollab.harness.presentation.model.PresentationOutline) method.invoke(localNodes,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                        .title("方案汇报")
                        .audience("团队")
                        .style("business-light")
                        .pageCount(5)
                        .goal("汇报方案")
                        .keyMessages(List.of("背景", "方案", "风险", "下一步"))
                        .build());

        assertThat(outline.getSlides()).hasSize(6);
        assertThat(outline.getSlides().get(0).getTemplateVariant()).isEqualTo("hero-band");
        assertThat(outline.getSlides().get(1).getPageType()).isEqualTo("TOC");
        assertThat(outline.getSlides().get(2).getPageType()).isEqualTo("TRANSITION");
        assertThat(outline.getSlides().get(3).getPageType()).isEqualTo("CONTENT");
        assertThat(outline.getSlides().get(5).getPageType()).isEqualTo("THANKS");
        assertThat(outline.getSlides().get(5).getTemplateVariant()).isEqualTo("next-step-board");
    }

    @Test
    void validateSlideXmlPreservesImgBlock() {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), "");
        String xml = """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <data>
                    <shape type="text" topLeftX="60" topLeftY="40" width="500" height="50">
                      <content><p><span fontSize="28">页面标题</span></p></content>
                    </shape>
                    <img src="boxcnRealImageToken" topLeftX="540" topLeftY="120" width="320" height="180"/>
                  </data>
                </slide>
                """;
        OverAllState state = new OverAllState(Map.of(
                "slideOutline", PresentationOutline.builder().slides(List.of(PresentationSlidePlan.builder()
                        .slideId("slide-1")
                        .index(1)
                        .title("页面标题")
                        .layout("two-column")
                        .templateVariant("dual-cards")
                        .keyPoints(List.of("要点"))
                        .build())).build(),
                "slideXmlList", List.of(PresentationSlideXml.builder()
                        .slideId("slide-1")
                        .index(1)
                        .title("页面标题")
                        .xml(xml)
                        .build())
        ));

        Map<String, Object> result = localNodes.validateSlideXml(state, null).join();
        @SuppressWarnings("unchecked")
        List<PresentationSlideXml> validated = (List<PresentationSlideXml>) result.get("slideXmlList");
        assertThat(validated).hasSize(1);
        assertThat(validated.get(0).getXml()).contains("<img src=\"boxcnRealImageToken\"");
    }

    @Nested
    class AssetResolution {

        @Test
        void safeImageUrlRejectsUndrawApiEndpoint() throws Exception {
            Method method = PresentationWorkflowNodes.class.getDeclaredMethod("isSafeImageUrl", String.class);
            method.setAccessible(true);

            boolean safe = (boolean) method.invoke(nodes,
                    "https://undraw.co/api/illustrations/choose?query=travel+planning");

            assertThat(safe).isFalse();
        }

        @Test
        void resolveSlideAssetsFallsBackToNoImageWhenSearchUnavailable() {
            PresentationAssetPlan assetPlan = PresentationAssetPlan.builder()
                    .slides(List.of(PresentationAssetPlan.SlideAssetPlan.builder()
                            .slideId("slide-1")
                            .contentImageTasks(List.of(PresentationAssetPlan.AssetTask.builder()
                                    .query("modern retail store")
                                    .purpose("主视觉")
                                    .preferredSourceType("CONTENT_IMAGE")
                                    .build()))
                            .illustrationTasks(List.of(PresentationAssetPlan.AssetTask.builder()
                                    .query("workflow collaboration illustration")
                                    .purpose("辅助插画")
                                    .preferredSourceType("ILLUSTRATION")
                                    .build()))
                            .build()))
                    .build();

            OverAllState state = new OverAllState(Map.of(
                    "taskId", "task-asset-1",
                    "assetPlan", assetPlan
            ));

            Map<String, Object> result = nodes.resolveSlideAssets(state, null).join();
            PresentationAssetResources resources = (PresentationAssetResources) result.get("assetResources");

            assertThat(resources.getSlides()).hasSize(1);
            assertThat(resources.getSlides().get(0).getImages()).isEmpty();
            assertThat(resources.getSlides().get(0).getIllustrations()).isEmpty();
        }
    }
}
