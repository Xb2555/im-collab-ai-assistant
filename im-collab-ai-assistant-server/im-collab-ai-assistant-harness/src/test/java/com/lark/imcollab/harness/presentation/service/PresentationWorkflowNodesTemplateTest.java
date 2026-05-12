package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.model.entity.PresentationAssetRef;
import com.lark.imcollab.common.model.entity.PresentationElementIR;
import com.lark.imcollab.common.model.entity.PresentationLayoutSpec;
import com.lark.imcollab.common.model.entity.PresentationSlideIR;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.harness.presentation.config.PresentationConcurrencySettings;
import com.lark.imcollab.harness.presentation.model.PresentationAssetPlan;
import com.lark.imcollab.harness.presentation.model.PresentationAssetResources;
import com.lark.imcollab.harness.presentation.model.PresentationImageResources;
import com.lark.imcollab.harness.presentation.model.PresentationImagePlan;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXml;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.lark.imcollab.skills.lark.slides.LarkSlidesMediaUploadResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresentationWorkflowNodesTemplateTest {

    private static final ReactAgent AGENT = mock(ReactAgent.class);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final PresentationConcurrencySettings CONCURRENCY_SETTINGS =
            new PresentationConcurrencySettings(8, 4, 6, 3);

    private final PresentationWorkflowNodes nodes = new PresentationWorkflowNodes(
            null, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

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
                .contains("<ellipse topLeftX=\"110\" topLeftY=\"262\" width=\"36\" height=\"36\">")
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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

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
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

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
    void autoPageCountExpandsWhenUserDidNotSpecifyPageLimit() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "defaultAutoPageCount",
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.class);
        method.setAccessible(true);

        int pageCount = (int) method.invoke(nodes,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                        .keyMessages(List.of("背景", "方案", "风险", "下一步"))
                        .build());

        assertThat(pageCount).isGreaterThanOrEqualTo(10);
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
    void tocStripsPrefixedNumbersBeforeOlAutoNumbering() {
        String xml = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(2)
                        .title("目录")
                        .layout("section")
                        .pageType("TOC")
                        .templateVariant("headline-panel")
                        .keyPoints(List.of("1.1.项目背景", "2.2.核心方案", "3.风险与计划"))
                        .build(),
                2,
                5,
                PresentationGenerationOptions.builder()
                        .style("business-light")
                        .themeFamily("business-light")
                        .density("standard")
                        .speakerNotes(false)
                        .templateDiversity("balanced")
                        .allowVariantMixing(true)
                        .build());

        assertThat(xml).contains("<ol>");
        assertThat(xml).contains("项目背景", "核心方案", "风险与计划");
        assertThat(xml).doesNotContain("1.1.项目背景", "2.2.核心方案", "3.风险与计划");
    }

    @Test
    void fallbackOutlineKeepsTransitionPageWhenPageBudgetAllowsSectionStructure() throws Exception {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

        var method = PresentationWorkflowNodes.class.getDeclaredMethod("fallbackOutline", com.lark.imcollab.harness.presentation.model.PresentationStoryline.class);
        method.setAccessible(true);
        var outline = (com.lark.imcollab.harness.presentation.model.PresentationOutline) method.invoke(localNodes,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                .title("方案汇报")
                .audience("团队")
                .style("business-light")
                .pageCount(11)
                .goal("汇报方案")
                .keyMessages(List.of("背景", "方案", "风险", "下一步"))
                .build());

        assertThat(outline.getSlides().stream().anyMatch(slide -> "TRANSITION".equals(slide.getPageType()))).isTrue();
    }

    @Test
    void fallbackOutlineRotatesTemplateVariants() throws Exception {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

        var method = PresentationWorkflowNodes.class.getDeclaredMethod("fallbackOutline", com.lark.imcollab.harness.presentation.model.PresentationStoryline.class);
        method.setAccessible(true);
        var outline = (com.lark.imcollab.harness.presentation.model.PresentationOutline) method.invoke(localNodes,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                .title("方案汇报")
                .audience("团队")
                .style("business-light")
                .pageCount(7)
                .goal("汇报方案")
                .keyMessages(List.of("背景", "方案"))
                .build());

        assertThat(outline.getSlides()).hasSize(7);
        assertThat(outline.getSlides().get(0).getTemplateVariant()).isEqualTo("hero-band");
        assertThat(outline.getSlides().get(1).getPageType()).isEqualTo("TOC");
        assertThat(outline.getSlides().get(2).getPageType()).isEqualTo("TRANSITION");
        assertThat(outline.getSlides().get(3).getPageType()).isEqualTo("CONTENT");
        assertThat(outline.getSlides().get(4).getPageType()).isEqualTo("TRANSITION");
        assertThat(outline.getSlides().get(6).getPageType()).isEqualTo("THANKS");
        assertThat(outline.getSlides().get(6).getTemplateVariant()).isEqualTo("next-step-board");
    }

    @Test
    void fallbackOutlineForFifteenPagesEndsWithThanksAndIncludesFiveTransitions() throws Exception {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

        var method = PresentationWorkflowNodes.class.getDeclaredMethod("fallbackOutline", com.lark.imcollab.harness.presentation.model.PresentationStoryline.class);
        method.setAccessible(true);
        var outline = (com.lark.imcollab.harness.presentation.model.PresentationOutline) method.invoke(localNodes,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                        .title("杭州旅游")
                        .audience("团队")
                        .style("business-light")
                        .pageCount(15)
                        .goal("生成杭州旅游PPT")
                        .keyMessages(List.of("景点", "美食", "行程", "文化", "历史"))
                        .build());

        assertThat(outline.getSlides()).hasSize(15);
        assertThat(outline.getSlides().get(14).getPageType()).isEqualTo("THANKS");
        assertThat(outline.getSlides().stream().filter(slide -> "TRANSITION".equals(slide.getPageType()))).hasSize(5);
    }

    @Test
    void tocNormalizationAllowsUpToSixAgendaPoints() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod("normalizeTocPoints", List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> points = (List<String>) method.invoke(nodes,
                List.of("1. 景点", "2. 美食", "3. 行程", "4. 文化", "5. 历史", "6. 住宿", "7. 预算"));

        assertThat(points).containsExactly("景点", "美食", "行程", "文化", "历史", "住宿");
    }

    @Test
    void validateSlideXmlPreservesImgBlock() {
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");
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

    @Test
    void compileSlideXmlUsesSharedBackgroundAndContentImageForContentPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-3")
                .pageIndex(3)
                .slideRole("two-column")
                .pageType("CONTENT")
                .pageSubType("CONTENT.HALF_IMAGE_HALF_TEXT")
                .title("核心方案")
                .visualIntent("balance")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-3-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("headline-panel").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("要点一；要点二")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnContentImage").altText("正文配图").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-background")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("background-image")
                                .targetElementType(com.lark.imcollab.common.model.enums.PresentationTargetElementType.IMAGE)
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnSharedBackground").altText("统一正文背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnSharedBackground\"");
        assertThat(xml).doesNotContain("src=\"boxcnContentImage\"");
    }

    @Test
    void compileSlideXmlDoesNotUseHeroImageAsBackgroundForContentPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-3")
                .pageIndex(3)
                .slideRole("two-column")
                .pageType("CONTENT")
                .pageSubType("CONTENT.HALF_IMAGE_HALF_TEXT")
                .title("核心方案")
                .visualIntent("balance")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-3-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("headline-panel").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("要点一；要点二")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnContentImage").altText("正文配图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnContentImage\"");
        assertThat(xml).doesNotContain("alt=\"统一正文背景图\"");
    }

    @Test
    void compileSlideXmlUsesCoverStyleTemplateForThanksPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-6")
                .pageIndex(6)
                .slideRole("summary")
                .pageType("THANKS")
                .pageSubType("THANKS.CLOSING")
                .title("感谢聆听")
                .visualIntent("title")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-6-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("next-step-board").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-6-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("Thank you for listening；汇报人：张三；汇报时间：2026年05月10日")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-6-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnThanksBackground").altText("结束页背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnThanksBackground\"");
        assertThat(xml).contains("感谢聆听");
        assertThat(xml).contains("Thank you for listening");
        assertThat(xml).contains("topLeftX=\"88\" topLeftY=\"82\" width=\"748\" height=\"320\"");
    }

    @Test
    void compileSlideXmlUsesBackgroundImageForCoverPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-1")
                .pageIndex(1)
                .slideRole("cover")
                .pageType("COVER")
                .pageSubType("COVER.HERO")
                .title("封面")
                .visualIntent("title")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-1-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("hero-band").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-1-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("副标题；汇报人：张三；汇报时间：2026年05月10日")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-1-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnCoverBackground").altText("封面背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnCoverBackground\"");
    }

    @Test
    void compileSlideXmlAllowsBackgroundImageForTocPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-2")
                .pageIndex(2)
                .slideRole("section")
                .pageType("TOC")
                .pageSubType("TOC.AGENDA")
                .title("目录")
                .visualIntent("balance")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-2-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("headline-panel").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-2-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("章节一；章节二；章节三")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-2-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnTocBackground").altText("目录背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnTocBackground\"");
    }

    @Test
    void compileSlideXmlAllowsBackgroundImageForTransitionPage() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-3")
                .pageIndex(3)
                .slideRole("section")
                .pageType("TRANSITION")
                .pageSubType("TRANSITION.SECTION_BREAK")
                .title("文化特色")
                .visualIntent("title")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-3-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("rail-notes").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("文化特色")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-3-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnTransitionBackground").altText("过渡页背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 8, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnTransitionBackground\"");
    }

    @Test
    void resolveSlideImagePrefersContentSlotWhileBackgroundStaysSeparate() throws Exception {
        Method resolveSlideImage = PresentationWorkflowNodes.class.getDeclaredMethod(
                "resolveSlideImage",
                PresentationSlidePlan.class,
                PresentationAssetResources.class);
        resolveSlideImage.setAccessible(true);
        Method resolveUnifiedContentBackground = PresentationWorkflowNodes.class.getDeclaredMethod(
                "resolveUnifiedContentBackground",
                PresentationSlidePlan.class,
                PresentationAssetResources.class);
        resolveUnifiedContentBackground.setAccessible(true);

        PresentationSlidePlan slide = PresentationSlidePlan.builder()
                .slideId("slide-3")
                .pageType("CONTENT")
                .layout("section")
                .templateVariant("section-frame")
                .build();
        PresentationAssetResources resources = PresentationAssetResources.builder()
                .slides(List.of(PresentationAssetResources.SlideAssetResource.builder()
                        .slideId("slide-3")
                        .sharedBackgroundImage(PresentationAssetResources.AssetResource.builder()
                                .assetId("bg-1")
                                .fileToken("boxcnSharedBackground")
                                .purpose("统一正文背景图")
                                .build())
                        .images(List.of(PresentationAssetResources.AssetResource.builder()
                                .assetId("img-1")
                                .fileToken("boxcnContentImage")
                                .purpose("正文配图")
                                .build()))
                        .build()))
                .build();

        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> image =
                (Optional<PresentationAssetResources.AssetResource>) resolveSlideImage.invoke(nodes, slide, resources);
        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> background =
                (Optional<PresentationAssetResources.AssetResource>) resolveUnifiedContentBackground.invoke(nodes, slide, resources);

        assertThat(image).isPresent();
        assertThat(image.get().getFileToken()).isEqualTo("boxcnContentImage");
        assertThat(background).isPresent();
        assertThat(background.get().getFileToken()).isEqualTo("boxcnSharedBackground");
    }

    @Test
    void resolveSlideImageUsesCoverGroupImageForCoverTocAndThanks() throws Exception {
        Method resolveSlideImage = PresentationWorkflowNodes.class.getDeclaredMethod(
                "resolveSlideImage",
                PresentationSlidePlan.class,
                PresentationAssetResources.class);
        resolveSlideImage.setAccessible(true);

        PresentationAssetResources resources = PresentationAssetResources.builder()
                .slides(List.of(
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-1")
                                .coverGroupImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("cover-1")
                                        .fileToken("boxcnCoverGroup")
                                        .purpose("封面目录感谢共享图")
                                        .build())
                                .build(),
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-2")
                                .coverGroupImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("cover-2")
                                        .fileToken("boxcnCoverGroup")
                                        .purpose("封面目录感谢共享图")
                                        .build())
                                .build(),
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-10")
                                .coverGroupImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("cover-3")
                                        .fileToken("boxcnCoverGroup")
                                        .purpose("封面目录感谢共享图")
                                        .build())
                                .build(),
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-4")
                                .coverGroupImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("cover-4")
                                        .fileToken("boxcnCoverGroup")
                                        .purpose("封面目录感谢共享图")
                                        .build())
                                .build()))
                .build();

        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> cover =
                (Optional<PresentationAssetResources.AssetResource>) resolveSlideImage.invoke(nodes, PresentationSlidePlan.builder()
                        .slideId("slide-1")
                        .pageType("COVER")
                        .layout("cover")
                        .templateVariant("hero-band")
                        .build(), resources);
        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> toc =
                (Optional<PresentationAssetResources.AssetResource>) resolveSlideImage.invoke(nodes, PresentationSlidePlan.builder()
                        .slideId("slide-2")
                        .pageType("TOC")
                        .layout("section")
                        .templateVariant("headline-panel")
                        .build(), resources);
        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> thanks =
                (Optional<PresentationAssetResources.AssetResource>) resolveSlideImage.invoke(nodes, PresentationSlidePlan.builder()
                        .slideId("slide-10")
                        .pageType("THANKS")
                        .layout("summary")
                        .templateVariant("next-step-board")
                        .build(), resources);
        @SuppressWarnings("unchecked")
        Optional<PresentationAssetResources.AssetResource> transition =
                (Optional<PresentationAssetResources.AssetResource>) resolveSlideImage.invoke(nodes, PresentationSlidePlan.builder()
                        .slideId("slide-4")
                        .pageType("TRANSITION")
                        .layout("section")
                        .templateVariant("rail-notes")
                        .build(), resources);

        assertThat(cover).isPresent();
        assertThat(toc).isPresent();
        assertThat(thanks).isPresent();
        assertThat(transition).isPresent();
        assertThat(cover.get().getFileToken()).isEqualTo("boxcnCoverGroup");
        assertThat(toc.get().getFileToken()).isEqualTo("boxcnCoverGroup");
        assertThat(thanks.get().getFileToken()).isEqualTo("boxcnCoverGroup");
        assertThat(transition.get().getFileToken()).isEqualTo("boxcnCoverGroup");
    }

    @Test
    void timelineWithoutImageDoesNotRenderWhitePlaceholderRectangles() {
        String xml = nodes.buildSlideXmlTemplate(PresentationSlidePlan.builder()
                        .index(3)
                        .title("时间轴")
                        .layout("timeline")
                        .templateVariant("horizontal-milestones")
                        .keyPoints(List.of("阶段一", "阶段二", "阶段三"))
                        .build(),
                3,
                6,
                PresentationGenerationOptions.builder()
                        .style("business-light")
                        .themeFamily("business-light")
                        .density("standard")
                        .speakerNotes(false)
                        .templateDiversity("balanced")
                        .allowVariantMixing(true)
                        .build());

        assertThat(xml).doesNotContain("topLeftY=\"328\" width=\"110\" height=\"82\"");
    }

    @Test
    void compileSlideXmlRendersTimelineImageIntoTimelineItemsSlot() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-4")
                .pageIndex(4)
                .slideRole("timeline")
                .pageType("TIMELINE")
                .pageSubType("TIMELINE.HORIZONTAL")
                .title("实施路径")
                .visualIntent("action")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-4-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("horizontal-milestones").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-4-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("需求澄清；方案设计；上线验证")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-4-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnTimelineImage").altText("时间轴节点配图").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-4-background")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("background-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnSharedBackground").altText("统一正文背景图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnSharedBackground\"");
        assertThat(xml).doesNotContain("src=\"boxcnTimelineImage\"");
        assertThat(xml).doesNotContain("topLeftY=\"328\" width=\"110\" height=\"82\"");
        assertThat(xml).doesNotContain("{{TIMELINE_ITEMS}}");
    }

    @Test
    void compileSlideXmlRendersDistinctTimelineNodeImagesWhenProvided() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-5")
                .pageIndex(5)
                .slideRole("timeline")
                .pageType("TIMELINE")
                .pageSubType("TIMELINE.HORIZONTAL")
                .title("推进路线")
                .visualIntent("action")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-5-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("horizontal-milestones").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("阶段一；阶段二；阶段三")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-node-1")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("timeline-node-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnNode1").altText("节点一").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-node-2")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("timeline-node-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnNode2").altText("节点二").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-node-3")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("timeline-node-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnNode3").altText("节点三").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 7, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).contains("src=\"boxcnNode1\"");
        assertThat(xml).contains("src=\"boxcnNode2\"");
        assertThat(xml).contains("src=\"boxcnNode3\"");
    }

    @Test
    void compileSlideXmlLogsTimelineMissingWhenHorizontalMilestonesHasNoRenderableNodeImages() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-5")
                .pageIndex(5)
                .slideRole("timeline")
                .pageType("TIMELINE")
                .pageSubType("TIMELINE.HORIZONTAL")
                .title("推进路线")
                .visualIntent("action")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-5-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("horizontal-milestones").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("阶段一；阶段二；阶段三")
                                .build()))
                .build();

        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));
        try {
            method.invoke(nodes, slideIr, 7, PresentationGenerationOptions.builder()
                    .style("business-light")
                    .themeFamily("business-light")
                    .density("standard")
                    .speakerNotes(false)
                    .templateDiversity("balanced")
                    .allowVariantMixing(true)
                    .build());
        } finally {
            System.setErr(originalErr);
        }

        assertThat(err.toString()).contains("ppt.timeline.image.missing");
        assertThat(err.toString()).contains("slideId=slide-5");
        assertThat(err.toString()).doesNotContain("heroImageToken=boxcnTimelineImage");
    }

    @Test
    void compileSlideXmlDoesNotFallbackToHeroImageForTimelineNodesWhenNodeAssetsMissing() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-5")
                .pageIndex(5)
                .slideRole("timeline")
                .pageType("TIMELINE")
                .pageSubType("TIMELINE.HORIZONTAL")
                .title("推进路线")
                .visualIntent("action")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-5-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("horizontal-milestones").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("阶段一；阶段二；阶段三")
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-5-image")
                                .elementKind(PresentationElementKind.IMAGE)
                                .semanticRole("hero-image")
                                .assetRef(PresentationAssetRef.builder().fileToken("boxcnTimelineImage").altText("时间轴内容图").build())
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 7, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).doesNotContain("src=\"boxcnTimelineImage\" topLeftX=\"72\"");
        assertThat(xml).doesNotContain("width=\"110\" height=\"82\"");
        assertThat(xml).contains("<ellipse topLeftX=\"110\" topLeftY=\"262\" width=\"36\" height=\"36\">");
    }

    @Test
    void contentSectionWithoutRenderableImageDowngradesToExplicitNoImageTemplate() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-7")
                .pageIndex(7)
                .slideRole("section")
                .pageType("CONTENT")
                .pageSubType("CONTENT.HALF_IMAGE_HALF_TEXT")
                .title("正文无图降级")
                .visualIntent("balance")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-7-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("section-frame").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-7-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("要点一；要点二")
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 8, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).doesNotContain("{{CONTENT_IMAGE}}");
        assertThat(xml).doesNotContain("<img src=");
        assertThat(xml).contains("topLeftX=\"82\" topLeftY=\"164\" width=\"748\" height=\"250\"");
    }

    @Test
    void compileSlideXmlDoesNotRenderRightImagePlaceholderWhenNoImageExists() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "compileSlideXml",
                PresentationSlideIR.class,
                int.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationSlideIR slideIr = PresentationSlideIR.builder()
                .slideId("slide-2")
                .pageIndex(2)
                .slideRole("two-column")
                .pageType("CONTENT")
                .pageSubType("CONTENT.HALF_IMAGE_HALF_TEXT")
                .title("无图页")
                .visualIntent("balance")
                .elements(List.of(
                        PresentationElementIR.builder()
                                .elementId("slide-2-title")
                                .elementKind(PresentationElementKind.TITLE)
                                .layoutBox(PresentationLayoutSpec.builder().templateVariant("dual-cards").build())
                                .build(),
                        PresentationElementIR.builder()
                                .elementId("slide-2-body")
                                .elementKind(PresentationElementKind.BODY)
                                .textContent("要点一；要点二")
                                .build()))
                .build();

        String xml = (String) method.invoke(nodes, slideIr, 6, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        assertThat(xml).doesNotContain("<img src=");
        assertThat(xml).doesNotContain("{{RIGHT_IMAGE}}");
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
        void stableSearchQueryIgnoresPurposeProseAndKeepsSemanticKey() throws Exception {
            Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                    "buildStableSearchQuery",
                    PresentationAssetPlan.SlideAssetPlan.class,
                    PresentationAssetPlan.AssetTask.class,
                    String.class);
            method.setAccessible(true);

            PresentationAssetPlan.SlideAssetPlan slide = PresentationAssetPlan.SlideAssetPlan.builder()
                    .slideId("slide-2")
                    .build();
            Object left = method.invoke(nodes, slide, PresentationAssetPlan.AssetTask.builder()
                    .query("揭阳学宫 卡片配图")
                    .purpose("增强可信度，避免喧宾夺主")
                    .build(), "image");
            Object right = method.invoke(nodes, slide, PresentationAssetPlan.AssetTask.builder()
                    .query("揭阳学宫 右侧图片")
                    .purpose("做视觉陪衬，不要太花")
                    .build(), "image");

            Method normalizedQuery = left.getClass().getDeclaredMethod("normalizedQuery");
            normalizedQuery.setAccessible(true);
            assertThat(normalizedQuery.invoke(left)).isEqualTo(normalizedQuery.invoke(right));
        }

        @Test
        void expectedImageSlotsFollowsTemplateVariantRules() throws Exception {
            Method method = PresentationWorkflowNodes.class.getDeclaredMethod("expectedImageSlots", PresentationSlidePlan.class);
            method.setAccessible(true);

            int timelineWithImage = (int) method.invoke(nodes, PresentationSlidePlan.builder()
                    .pageType("TIMELINE")
                    .layout("timeline")
                    .templateVariant("horizontal-milestones")
                    .build());
            int timelineWithoutImage = (int) method.invoke(nodes, PresentationSlidePlan.builder()
                    .pageType("TIMELINE")
                    .layout("timeline")
                    .templateVariant("stacked-steps")
                    .build());
            int tocSlots = (int) method.invoke(nodes, PresentationSlidePlan.builder()
                    .pageType("TOC")
                    .layout("section")
                    .templateVariant("toc-list")
                    .build());

            assertThat(timelineWithImage).isEqualTo(2);
            assertThat(timelineWithoutImage).isEqualTo(1);
            assertThat(tocSlots).isEqualTo(1);
        }

        @Test
        void selectTimelineImageTasksBuildsThreeDistinctNodeTasks() throws Exception {
            Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                    "selectTimelineImageTasks",
                    PresentationImagePlan.PageImagePlan.class,
                    PresentationSlidePlan.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<PresentationAssetPlan.TimelineImageTask> tasks =
                    (List<PresentationAssetPlan.TimelineImageTask>) method.invoke(nodes, PresentationImagePlan.PageImagePlan.builder()
                            .contentImageTasks(List.of(PresentationAssetPlan.AssetTask.builder()
                                    .query("杭州 行程")
                                    .purpose("时间轴节点配图")
                                    .build()))
                            .build(), PresentationSlidePlan.builder()
                            .slideId("slide-6")
                            .title("杭州三日行程")
                            .pageType("TIMELINE")
                            .layout("timeline")
                            .templateVariant("horizontal-milestones")
                            .keyPoints(List.of("西湖漫步", "灵隐探访", "河坊街收尾"))
                            .build());

            assertThat(tasks).hasSize(3);
            assertThat(tasks).extracting(PresentationAssetPlan.TimelineImageTask::getNodeText)
                    .containsExactly("西湖漫步", "灵隐探访", "河坊街收尾");
            assertThat(tasks).extracting(task -> task.getAssetTask().getQuery())
                    .allMatch(query -> query.contains("杭州 行程"));
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

        @Test
        void toResolvedSlideResourcesKeepsSharedSlotsSeparatedFromContentSlots() throws Exception {
            Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                    "toResolvedSlideResources",
                    PresentationAssetPlan.class,
                    PresentationImageResources.class,
                    Class.forName("com.lark.imcollab.harness.presentation.service.PresentationWorkflowNodes$AssetResolutionContext"));
            method.setAccessible(true);
            Class<?> contextClass = Class.forName("com.lark.imcollab.harness.presentation.service.PresentationWorkflowNodes$AssetResolutionContext");
            var constructor = contextClass.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object context = constructor.newInstance("task-test");

            PresentationAssetPlan assetPlan = PresentationAssetPlan.builder()
                    .slides(List.of(PresentationAssetPlan.SlideAssetPlan.builder()
                            .slideId("slide-6")
                            .pageType("TIMELINE")
                            .layout("timeline")
                            .templateVariant("horizontal-milestones")
                            .build()))
                    .build();
            PresentationImageResources imageResources = PresentationImageResources.builder()
                    .resources(List.of(PresentationImageResources.PageImageResource.builder()
                            .slideId("slide-6")
                            .sharedBackgroundImage(PresentationImageResources.ResourceItem.builder()
                                    .candidateUrls(List.of())
                                    .build())
                            .timelineNodeImages(List.of(PresentationImageResources.TimelineNodeResource.builder()
                                    .nodeId("slide-6-timeline-image")
                                    .nodeIndex(0)
                                    .nodeText("时间轴配图")
                                    .resource(PresentationImageResources.ResourceItem.builder()
                                            .candidateUrls(List.of())
                                            .build())
                                    .build()))
                            .build()))
                    .build();

            @SuppressWarnings("unchecked")
            List<PresentationAssetResources.SlideAssetResource> slides =
                    (List<PresentationAssetResources.SlideAssetResource>) method.invoke(nodes, assetPlan, imageResources, context);

            assertThat(slides).hasSize(1);
            PresentationAssetResources.SlideAssetResource slide = slides.get(0);
            assertThat(slide.getSharedBackgroundImage()).isNull();
            assertThat(slide.getImages()).isEmpty();
            assertThat(slide.getTimelineNodeImages()).hasSize(1);
        }
    }

    @Test
    void normalizeOutlineBuildsTocFromBusinessSectionSlides() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "normalizeOutline",
                PresentationOutline.class,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationOutline outline = PresentationOutline.builder()
                .title("方案汇报")
                .slides(List.of(
                        PresentationSlidePlan.builder().slideId("slide-1").index(1).title("封面").layout("cover").pageType("COVER").build(),
                        PresentationSlidePlan.builder().slideId("slide-2").index(2).title("目录").layout("section").pageType("TOC").keyPoints(List.of("旧目录1", "旧目录2")).build(),
                        PresentationSlidePlan.builder().slideId("slide-3").index(3).title("背景分析").layout("two-column").pageType("CONTENT").build(),
                        PresentationSlidePlan.builder().slideId("slide-4").index(4).title("实施路径").layout("timeline").pageType("TIMELINE").build(),
                        PresentationSlidePlan.builder().slideId("slide-5").index(5).title("方案对比").layout("comparison").pageType("COMPARISON").build(),
                        PresentationSlidePlan.builder().slideId("slide-6").index(6).title("指标跟踪").layout("metric-cards").pageType("CHART").build(),
                        PresentationSlidePlan.builder().slideId("slide-7").index(7).title("感谢聆听").layout("summary").pageType("THANKS").build()))
                .build();

        com.lark.imcollab.harness.presentation.model.PresentationStoryline storyline =
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                        .title("方案汇报")
                        .goal("统一方案")
                        .audience("团队")
                        .style("business-light")
                        .keyMessages(List.of("旧目录A", "旧目录B", "旧目录C", "旧目录D"))
                        .pageCount(7)
                        .build();

        method.invoke(nodes, outline, storyline, PresentationGenerationOptions.builder()
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .build());

        PresentationSlidePlan tocSlide = outline.getSlides().get(1);
        assertThat(tocSlide.getKeyPoints())
                .containsExactly("背景分析", "实施路径", "方案对比", "指标跟踪");
    }

    @Test
    void uploadResolvedAssetsReusesSharedBackgroundUploadAcrossSlides() throws Exception {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        when(slidesTool.uploadMedia(anyString(), anyString())).thenReturn(LarkSlidesMediaUploadResult.builder()
                .presentationId("slides-1")
                .fileToken("boxcnSharedBackground")
                .build());
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                mock(PresentationExecutionSupport.class), AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, slidesTool, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "uploadResolvedAssets",
                String.class,
                PresentationAssetResources.class);
        method.setAccessible(true);

        PresentationAssetResources resources = PresentationAssetResources.builder()
                .slides(List.of(
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-4")
                                .sharedBackgroundImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("bg-1")
                                        .assetType("shared-background-image")
                                        .sourceUrl("https://example.com/background.jpg")
                                        .localTempPath("C:\\temp\\background.jpg")
                                        .downloadStatus("DOWNLOADED")
                                        .build())
                                .images(List.of())
                                .timelineNodeImages(List.of())
                                .illustrations(List.of())
                                .diagrams(List.of())
                                .charts(List.of())
                                .build(),
                        PresentationAssetResources.SlideAssetResource.builder()
                                .slideId("slide-5")
                                .sharedBackgroundImage(PresentationAssetResources.AssetResource.builder()
                                        .assetId("bg-2")
                                        .assetType("shared-background-image")
                                        .sourceUrl("https://example.com/background.jpg")
                                        .localTempPath("C:\\temp\\background.jpg")
                                        .downloadStatus("DOWNLOADED")
                                        .build())
                                .images(List.of())
                                .timelineNodeImages(List.of())
                                .illustrations(List.of())
                                .diagrams(List.of())
                                .charts(List.of())
                                .build()))
                .build();

        PresentationAssetResources uploaded = (PresentationAssetResources) method.invoke(localNodes, "slides-1", resources);

        verify(slidesTool, times(1)).uploadMedia("slides-1", "C:\\temp\\background.jpg");
        assertThat(uploaded.getSlides()).hasSize(2);
        assertThat(uploaded.getSlides()).allSatisfy(slide ->
                assertThat(slide.getSharedBackgroundImage().getFileToken()).isEqualTo("boxcnSharedBackground"));
    }

    @Test
    void normalizeOutlinePreservesTransitionsAndOnlyTrimsContentSlides() throws Exception {
        Method method = PresentationWorkflowNodes.class.getDeclaredMethod(
                "normalizeOutline",
                PresentationOutline.class,
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.class,
                PresentationGenerationOptions.class);
        method.setAccessible(true);

        PresentationOutline outline = PresentationOutline.builder()
                .title("方案汇报")
                .slides(List.of(
                        PresentationSlidePlan.builder().title("封面").layout("cover").pageType("COVER").build(),
                        PresentationSlidePlan.builder().title("目录").layout("section").pageType("TOC").build(),
                        PresentationSlidePlan.builder().title("背景").layout("section").pageType("TRANSITION").sectionId("s1").sectionTitle("背景").sectionOrder(1).build(),
                        PresentationSlidePlan.builder().title("背景正文1").layout("two-column").pageType("CONTENT").sectionId("s1").sectionTitle("背景").sectionOrder(1).build(),
                        PresentationSlidePlan.builder().title("背景正文2").layout("two-column").pageType("CONTENT").sectionId("s1").sectionTitle("背景").sectionOrder(1).build(),
                        PresentationSlidePlan.builder().title("方案").layout("section").pageType("TRANSITION").sectionId("s2").sectionTitle("方案").sectionOrder(2).build(),
                        PresentationSlidePlan.builder().title("方案正文").layout("timeline").pageType("TIMELINE").sectionId("s2").sectionTitle("方案").sectionOrder(2).build(),
                        PresentationSlidePlan.builder().title("感谢聆听").layout("summary").pageType("THANKS").build()))
                .build();

        com.lark.imcollab.harness.presentation.model.PresentationStoryline storyline =
                com.lark.imcollab.harness.presentation.model.PresentationStoryline.builder()
                        .title("方案汇报")
                        .goal("统一方案")
                        .audience("团队")
                        .style("business-light")
                        .keyMessages(List.of("背景", "方案"))
                        .pageCount(7)
                        .build();

        method.invoke(nodes, outline, storyline, PresentationGenerationOptions.builder()
                .pageCount(7)
                .style("business-light")
                .themeFamily("business-light")
                .density("standard")
                .speakerNotes(false)
                .templateDiversity("balanced")
                .allowVariantMixing(true)
                .includeToc(true)
                .includeTransitions(true)
                .build());

        assertThat(outline.getSlides()).hasSize(7);
        assertThat(outline.getSlides().stream().filter(slide -> "TRANSITION".equalsIgnoreCase(slide.getPageType()))).hasSize(2);
        assertThat(outline.getSlides().get(2).getPageType()).isEqualTo("TRANSITION");
        assertThat(outline.getSlides().get(3).getPageType()).isEqualTo("CONTENT");
        assertThat(outline.getSlides().get(4).getPageType()).isEqualTo("TRANSITION");
        assertThat(outline.getSlides().get(5).getPageType()).isIn("CONTENT", "TIMELINE", "COMPARISON", "CHART");
        assertThat(outline.getSlides().get(6).getPageType()).isEqualTo("THANKS");
    }

    @Test
    void resolveGenerationOptionsDisablesTocAndTransitionsWhenUserExplicitlyRequestsSo() {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                support, AGENT, AGENT, AGENT, AGENT, AGENT, AGENT, null, new ObjectMapper(), EXECUTOR, CONCURRENCY_SETTINGS, "");

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-4",
                Task.builder().rawInstruction("生成一份8页PPT，不要目录页，也不要过渡页").build(),
                new OverAllState(Map.of()));

        assertThat(options.isIncludeToc()).isFalse();
        assertThat(options.isIncludeTransitions()).isFalse();
    }
}
