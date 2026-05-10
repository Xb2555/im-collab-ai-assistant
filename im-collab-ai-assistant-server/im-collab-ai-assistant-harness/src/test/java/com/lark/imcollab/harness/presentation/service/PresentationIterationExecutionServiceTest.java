package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import com.lark.imcollab.skills.lark.slides.LarkSlidesFetchResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesReplaceResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresentationIterationExecutionServiceTest {

    @Test
    void replacesRequestedSlideTitleAndVerifiesResult() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="b1" type="text" topLeftX="80" topLeftY="80" width="800" height="120"><content textType="title"><p>旧标题</p></content></shape></data></slide><slide id="s2"><data><shape id="b2" type="text" topLeftX="90" topLeftY="90" width="700" height="100"><content textType="title"><p>第二页</p></content></shape></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s2\"><data><shape id=\"b2\"><content><p>新采购评审结论</p></content></shape></data></slide></presentation>")
                        .build());
        when(intentFacade.resolve(eq("把第二页标题改成新采购评审结论"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .actionType(PresentationEditActionType.REPLACE_SLIDE_TITLE)
                .pageIndex(2)
                .replacementText("新采购评审结论")
                .targetElementType(PresentationTargetElementType.TITLE)
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第二页标题改成新采购评审结论")
                .build());

        ArgumentCaptor<List<Map<String, Object>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(slidesTool).replaceSlide(eq("slides-1"), eq("s2"), partsCaptor.capture());
        assertThat(result.getModifiedSlides()).containsExactly("s2");
        assertThat(partsCaptor.getValue().get(0))
                .containsEntry("action", "block_replace")
                .containsEntry("block_id", "b2");
        assertThat((String) partsCaptor.getValue().get(0).get("replacement")).contains("新采购评审结论");
    }

    @Test
    void executesMultipleSlideOperationsInSingleRequest() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="t1" type="text" topLeftX="80" topLeftY="80" width="800" height="120"><content textType="title"><p>旧封面</p></content></shape></data></slide><slide id="s2"><data><shape id="b2" type="text" topLeftX="90" topLeftY="180" width="700" height="220"><content textType="body"><p>旧正文</p></content></shape></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="t1"><content><p>新封面</p></content></shape></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="t1"><content><p>新封面</p></content></shape></data></slide><slide id="s2"><data><shape id="b2" type="text" topLeftX="90" topLeftY="180" width="700" height="220"><content textType="body"><p>旧正文</p></content></shape></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s2"><data><shape id="b2"><content><p>新的关键结论</p></content></shape></data></slide></presentation>
                                """)
                        .build());
        when(intentFacade.resolve(eq("把第一页标题改成新封面，第二页正文改成新的关键结论"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(
                        PresentationEditOperation.builder()
                                .actionType(PresentationEditActionType.REPLACE_SLIDE_TITLE)
                                .targetElementType(PresentationTargetElementType.TITLE)
                                .pageIndex(1)
                                .replacementText("新封面")
                                .build(),
                        PresentationEditOperation.builder()
                                .actionType(PresentationEditActionType.REPLACE_SLIDE_BODY)
                                .targetElementType(PresentationTargetElementType.BODY)
                                .pageIndex(2)
                                .replacementText("新的关键结论")
                                .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第一页标题改成新封面，第二页正文改成新的关键结论")
                .build());

        verify(slidesTool).replaceSlide(eq("slides-1"), eq("s1"), anyList());
        verify(slidesTool).replaceSlide(eq("slides-1"), eq("s2"), anyList());
        assertThat(result.getModifiedSlides()).containsExactly("s1", "s2");
        assertThat(result.getSummary()).contains("第 1 页标题改为新封面").contains("第 2 页正文改为新的关键结论");
    }

    @Test
    void insertsSlideByCloningNearestBodyTemplateAndRemovingOldIds() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation>
                                  <slide id="s1"><data><shape id="cover-title" type="text"><content textType="title"><p>封面</p></content></shape></data></slide>
                                  <slide id="s2"><data><background color="#102030"/><shape id="t2" type="text" topLeftX="80" topLeftY="80" width="800" height="120"><content textType="title"><p>模板标题</p></content></shape><shape id="b2" type="text" topLeftX="90" topLeftY="220" width="760" height="260"><content textType="body"><p>模板正文</p></content></shape></data></slide>
                                  <slide id="s3"><data><shape id="end-title" type="text"><content textType="title"><p>总结</p></content></shape></data></slide>
                                </presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"new-slide\"><data><shape><content><p>风险应对</p></content></shape><shape><content><p>预算 排期 依赖</p></content></shape></data></slide></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s2")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("""
                        <slide id="s2" slide_id="legacy-slide"><data><background color="#102030"/><shape id="t2" type="text" topLeftX="80" topLeftY="80" width="800" height="120"><content textType="title"><p>模板标题</p></content></shape><shape id="b2" block_id="legacy-body" type="text" topLeftX="90" topLeftY="220" width="760" height="260"><content textType="body"><p>模板正文</p></content></shape></data></slide>
                        """)
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), eq("s2"))).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("new-slide")
                .build());
        when(intentFacade.resolve(eq("在第1页后插入一页，标题为风险应对，正文为预算、排期、依赖"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.INSERT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.INSERT_SLIDE)
                        .insertAfterPageIndex(1)
                        .slideTitle("风险应对")
                        .slideBody("预算、排期、依赖")
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("在第1页后插入一页，标题为风险应对，正文为预算、排期、依赖")
                .build());

        ArgumentCaptor<String> slideXmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(slidesTool).fetchSlide("slides-1", "s2");
        verify(slidesTool).createSlide(eq("slides-1"), slideXmlCaptor.capture(), eq("s2"));
        String createdXml = slideXmlCaptor.getValue();
        assertThat(createdXml).contains("风险应对").contains("#102030").contains("<li><p>预算</p></li>").contains("<li><p>排期</p></li>").contains("<li><p>依赖</p></li>");
        assertThat(createdXml).doesNotContain("id=\"s2\"").doesNotContain("id=\"t2\"").doesNotContain("slide_id=").doesNotContain("block_id=");
        assertThat(result.getModifiedSlides()).containsExactly("new-slide");
        assertThat(result.getSummary()).contains("已在第 1 页后新增一页");
    }

    @Test
    void insertsAfterPageIndexWhenResolverUsesPageIndexForInsert() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation>
                                  <slide id="s1"><data><shape id="t1" type="text"><content textType="title"><p>第一页</p></content></shape></data></slide>
                                  <slide id="s2"><data><shape id="t2" type="text"><content textType="title"><p>第二页</p></content></shape><shape id="b2" type="text"><content textType="body"><p>正文模板</p></content></shape></data></slide>
                                  <slide id="s3"><data><shape id="t3" type="text"><content textType="title"><p>第三页</p></content></shape><shape id="b3" type="text"><content textType="body"><p>正文模板</p></content></shape></data></slide>
                                  <slide id="s4"><data><shape id="t4" type="text"><content textType="title"><p>第四页</p></content></shape></data></slide>
                                </presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"new-slide\"><data><shape><content><p>多端协作闭环</p></content></shape><shape><content><p>IM 发起 Planner 执行 文档沉淀 PPT 交付</p></content></shape></data></slide></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s2")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("<slide id=\"s2\"><data><shape id=\"t2\" type=\"text\"><content textType=\"title\"><p>第二页</p></content></shape><shape id=\"b2\" type=\"text\"><content textType=\"body\"><p>正文模板</p></content></shape></data></slide>")
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), eq("s3"))).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("new-slide")
                .build());
        when(intentFacade.resolve(eq("在第2页后插入一页，标题为多端协作闭环，正文为 IM 发起、Planner 执行、文档沉淀、PPT 交付"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.INSERT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.INSERT_SLIDE)
                        .pageIndex(2)
                        .slideTitle("多端协作闭环")
                        .slideBody("IM 发起、Planner 执行、文档沉淀、PPT 交付")
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("在第2页后插入一页，标题为多端协作闭环，正文为 IM 发起、Planner 执行、文档沉淀、PPT 交付")
                .build());

        verify(slidesTool).createSlide(eq("slides-1"), anyString(), eq("s3"));
        assertThat(result.getSummary()).contains("已在第 2 页后新增一页");
    }

    @Test
    void insertAtEndReplacesUntaggedBodyTextFromClonedLastSlide() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation>
                                  <slide id="s1"><data><shape id="t1" type="text"><content textType="title"><p>第一页</p></content></shape></data></slide>
                                  <slide id="s2"><data><shape id="t2" type="text"><content textType="title"><p>总结</p></content></shape><shape id="b2" type="text"><content><p>旧的最后一页正文</p></content></shape></data></slide>
                                </presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"new-slide\"><data><shape><content><p>后续行动计划</p></content></shape><shape><content><p>确认试点范围 补齐权限配置 组织演示验收</p></content></shape></data></slide></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s2")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("""
                        <slide id="s2"><data><shape id="t2" type="text"><content textType="title"><p>总结</p></content></shape><shape id="b2" type="text"><content><p>旧的最后一页正文</p></content></shape></data></slide>
                        """)
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), isNull())).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("new-slide")
                .build());
        when(intentFacade.resolve(eq("在最后插入一页，标题为后续行动计划，正文为确认试点范围、补齐权限配置、组织演示验收"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.INSERT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.INSERT_SLIDE)
                        .slideTitle("后续行动计划")
                        .slideBody("确认试点范围、补齐权限配置、组织演示验收")
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("在最后插入一页，标题为后续行动计划，正文为确认试点范围、补齐权限配置、组织演示验收")
                .build());

        ArgumentCaptor<String> slideXmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(slidesTool).createSlide(eq("slides-1"), slideXmlCaptor.capture(), isNull());
        String createdXml = slideXmlCaptor.getValue();
        assertThat(createdXml).contains("后续行动计划").contains("<li><p>确认试点范围</p></li>").contains("<li><p>补齐权限配置</p></li>").contains("<li><p>组织演示验收</p></li>");
        assertThat(createdXml).doesNotContain("旧的最后一页正文");
        assertThat(result.getSummary()).contains("已在第 2 页后新增一页");
    }

    @Test
    void insertReusesEmptyBodyPlaceholderAndRemovesOtherEmptyTextShapes() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation>
                                  <slide id="s1"><data><shape id="t1" type="text"><content textType="title"><p>模板页</p></content></shape><shape id="body-placeholder" type="text"><content/></shape><shape id="unused-empty" type="text"><content/></shape></data></slide>
                                </presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"new-slide\"><data><shape><content><p>后续行动计划</p></content></shape><shape><content><p>确认试点范围</p></content></shape></data></slide></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s1")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("""
                        <slide id="s1"><data><shape id="t1" type="text"><content textType="title"><p>模板页</p></content></shape><shape id="body-placeholder" type="text"><content/></shape><shape id="unused-empty" type="text"><content/></shape></data></slide>
                        """)
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), isNull())).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("new-slide")
                .build());
        when(intentFacade.resolve(eq("在最后插入一页，标题为后续行动计划，正文为确认试点范围"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.INSERT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.INSERT_SLIDE)
                        .slideTitle("后续行动计划")
                        .slideBody("确认试点范围")
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("在最后插入一页，标题为后续行动计划，正文为确认试点范围")
                .build());

        ArgumentCaptor<String> slideXmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(slidesTool).createSlide(eq("slides-1"), slideXmlCaptor.capture(), isNull());
        String createdXml = slideXmlCaptor.getValue();
        assertThat(createdXml).contains("后续行动计划").contains("确认试点范围");
        assertThat(createdXml).doesNotContain("<content/>");
    }

    @Test
    void deletesRequestedSlideAndRejectsOnlySlideDeck() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"/><slide id="s2"/><slide id="s3"/></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s1\"/><slide id=\"s3\"/></presentation>")
                        .build());
        when(intentFacade.resolve(eq("删除第2页"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.DELETE)
                .actionType(PresentationEditActionType.DELETE_SLIDE)
                .pageIndex(2)
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("删除第2页")
                .build());

        verify(slidesTool).deleteSlide("slides-1", "s2");
        assertThat(result.getSummary()).isEqualTo("已删除第 2 页");

        LarkSlidesTool singleSlideTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade singleIntentFacade = mock(PresentationEditIntentFacade.class);
        when(singleSlideTool.fetchPresentation("slides-2")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-2")
                .xml("<presentation><slide id=\"only\"/></presentation>")
                .build());
        when(singleIntentFacade.resolve(eq("删除第1页"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.DELETE)
                .actionType(PresentationEditActionType.DELETE_SLIDE)
                .pageIndex(1)
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService singleService = new PresentationIterationExecutionService(singleSlideTool, singleIntentFacade);

        assertThatThrownBy(() -> singleService.edit(PresentationIterationRequest.builder()
                .taskId("task-2")
                .artifactId("artifact-2")
                .presentationId("slides-2")
                .instruction("删除第1页")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能删除唯一一页");
        verify(singleSlideTool, never()).deleteSlide(eq("slides-2"), eq("only"));
    }

    @Test
    void movesSlideByCreatingCopyBeforeTargetThenDeletingSource() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"/><slide id="s2"/><slide id="s3"/></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s1\"/><slide id=\"moved\"/><slide id=\"s2\"/></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s3")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("<slide id=\"s3\"><data><shape id=\"t3\" type=\"text\"><content textType=\"title\"><p>第三页</p></content></shape></data></slide>")
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), eq("s2"))).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("moved")
                .build());
        when(intentFacade.resolve(eq("把第3页移到第1页后"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.MOVE_SLIDE)
                        .pageIndex(3)
                        .insertAfterPageIndex(1)
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第3页移到第1页后")
                .build());

        InOrder inOrder = inOrder(slidesTool);
        inOrder.verify(slidesTool).fetchPresentation("slides-1");
        inOrder.verify(slidesTool).fetchSlide("slides-1", "s3");
        inOrder.verify(slidesTool).createSlide(eq("slides-1"), anyString(), eq("s2"));
        inOrder.verify(slidesTool).deleteSlide("slides-1", "s3");
        inOrder.verify(slidesTool).fetchPresentation("slides-1");
        assertThat(result.getModifiedSlides()).containsExactly("moved");
        assertThat(result.getSummary()).isEqualTo("已移动第 3 页到第 1 页后");
    }

    @Test
    void movesSlideToEndWhenInsertAfterIndexUsesEndSentinel() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"/><slide id="s2"/><slide id="s3"/><slide id="s4"/></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s1\"/><slide id=\"s3\"/><slide id=\"s4\"/><slide id=\"moved\"/></presentation>")
                        .build());
        when(slidesTool.fetchSlide("slides-1", "s2")).thenReturn(LarkSlidesFetchResult.builder()
                .presentationId("slides-1")
                .xml("<slide id=\"s2\"><data><shape id=\"t2\" type=\"text\"><content textType=\"title\"><p>第二页</p></content></shape></data></slide>")
                .build());
        when(slidesTool.createSlide(eq("slides-1"), anyString(), isNull())).thenReturn(LarkSlidesReplaceResult.builder()
                .slideId("moved")
                .build());
        when(intentFacade.resolve(eq("把第2页移到最后"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.MOVE_SLIDE)
                        .pageIndex(2)
                        .insertAfterPageIndex(-1)
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第2页移到最后")
                .build());

        InOrder inOrder = inOrder(slidesTool);
        inOrder.verify(slidesTool).fetchPresentation("slides-1");
        inOrder.verify(slidesTool).fetchSlide("slides-1", "s2");
        inOrder.verify(slidesTool).createSlide(eq("slides-1"), anyString(), isNull());
        inOrder.verify(slidesTool).deleteSlide("slides-1", "s2");
        assertThat(result.getModifiedSlides()).containsExactly("moved");
        assertThat(result.getSummary()).isEqualTo("已移动第 2 页到第 4 页后");
    }

    @Test
    void replacesImageByElementRoleAnchor() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        PresentationEditIntentFacade intentFacade = mock(PresentationEditIntentFacade.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="t1" type="text"><content textType="title"><p>封面</p></content></shape><img id="img-1" src="boxcnOld" topLeftX="560" topLeftY="90" width="320" height="180" alt="旧图"/></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s1\"><data><img id=\"img-1\" src=\"boxcn-replaced-image\" topLeftX=\"560\" topLeftY=\"90\" width=\"320\" height=\"180\" alt=\"门店实景图\"/></data></slide></presentation>")
                        .build());
        when(intentFacade.resolve(eq("把第1页右侧图片换成门店实景图"), any())).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.REPLACE_IMAGE)
                        .targetElementType(PresentationTargetElementType.IMAGE)
                        .anchorMode(PresentationAnchorMode.BY_ELEMENT_ROLE)
                        .pageIndex(1)
                        .elementRole("right-image")
                        .replacementText("门店实景图")
                        .build()))
                .clarificationNeeded(false)
                .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool, intentFacade);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第1页右侧图片换成门店实景图")
                .build());

        verify(slidesTool).replaceSlide(eq("slides-1"), eq("s1"), anyList());
        assertThat(result.getModifiedSlides()).containsExactly("s1");
    }
}
