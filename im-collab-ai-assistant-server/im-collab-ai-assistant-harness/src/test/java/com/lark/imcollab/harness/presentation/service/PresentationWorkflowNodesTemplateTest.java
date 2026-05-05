package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import com.lark.imcollab.harness.presentation.workflow.PresentationStateKeys;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void generationOptionsPreferLatestReplanPageCountOverOriginalTaskPageCount() {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        when(support.findPptStep("task-replan")).thenReturn(Optional.of(TaskStepRecord.builder()
                .taskId("task-replan")
                .name("生成新版PPT（3页）")
                .inputSummary("整体重新规划并重跑，生成一份3页新版PPT")
                .build()));
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                support, null, null, null, null, null, new ObjectMapper());

        PresentationGenerationOptions options = localNodes.resolveGenerationOptions(
                "task-replan",
                Task.builder().rawInstruction("原始任务：生成一份2页PPT").build(),
                new OverAllState(Map.of()));

        assertThat(options.getPageCount()).isEqualTo(3);
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

    @Test
    void generateSlideXmlCallsAgentOnceForBatchAndPreservesModelXml() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PresentationExecutionSupport support = jsonSupport(mapper);
        CountingNodes localNodes = new CountingNodes(support, mapper, """
                {"slides":[
                  {"slideId":"slide-1","index":1,"title":"第一页","xml":"%s","speakerNotes":"note 1"},
                  {"slideId":"slide-2","index":2,"title":"第二页","xml":"%s","speakerNotes":"note 2"},
                  {"slideId":"slide-3","index":3,"title":"第三页","xml":"%s","speakerNotes":"note 3"}
                ]}
                """.formatted(escapeJson(validXml("AI 第一页 marker")),
                escapeJson(validXml("AI 第二页 marker")),
                escapeJson(validXml("AI 第三页 marker"))));

        Map<String, Object> result = localNodes.generateSlideXml(stateWithOutline(3), null).get();
        List<?> slides = (List<?>) result.get(PresentationStateKeys.SLIDE_XML_LIST);

        assertThat(localNodes.calls()).isEqualTo(1);
        assertThat(slides).hasSize(3);
        assertThat(mapper.writeValueAsString(slides)).contains("AI 第一页 marker", "AI 第二页 marker", "AI 第三页 marker");
    }

    @Test
    void validateSlideXmlKeepsModelXmlAndSortsByIndex() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                jsonSupport(mapper), null, null, null, null, null, mapper);
        OverAllState state = stateWithOutlineAndXml(3, List.of(
                Map.of("slideId", "slide-3", "index", 3, "title", "第三页", "xml", validXml("AI 第三页 marker")),
                Map.of("slideId", "slide-1", "index", 1, "title", "第一页", "xml", validXml("AI 第一页 marker")),
                Map.of("slideId", "slide-2", "index", 2, "title", "第二页", "xml", validXml("AI 第二页 marker"))
        ));

        Map<String, Object> result = localNodes.validateSlideXml(state, null).get();
        String json = mapper.writeValueAsString(result.get(PresentationStateKeys.SLIDE_XML_LIST));

        assertThat(json).contains("AI 第一页 marker", "AI 第二页 marker", "AI 第三页 marker");
        assertThat(json.indexOf("AI 第一页 marker")).isLessThan(json.indexOf("AI 第二页 marker"));
        assertThat(json.indexOf("AI 第二页 marker")).isLessThan(json.indexOf("AI 第三页 marker"));
    }

    @Test
    void generateSlideXmlRetriesOnceWhenBatchMissesPage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountingNodes localNodes = new CountingNodes(jsonSupport(mapper), mapper,
                """
                        {"slides":[{"slideId":"slide-1","index":1,"title":"第一页","xml":"%s"}]}
                        """.formatted(escapeJson(validXml("AI 第一页 marker"))),
                """
                        {"slides":[
                          {"slideId":"slide-1","index":1,"title":"第一页","xml":"%s"},
                          {"slideId":"slide-2","index":2,"title":"第二页","xml":"%s"}
                        ]}
                        """.formatted(escapeJson(validXml("AI 第一页 retry")),
                        escapeJson(validXml("AI 第二页 retry"))));

        Map<String, Object> result = localNodes.generateSlideXml(stateWithOutline(2), null).get();

        assertThat(localNodes.calls()).isEqualTo(2);
        assertThat(mapper.writeValueAsString(result.get(PresentationStateKeys.SLIDE_XML_LIST)))
                .contains("AI 第一页 retry", "AI 第二页 retry");
    }

    @Test
    void generateSlideXmlFailsWhenBatchStillMissesPageAfterRetry() {
        ObjectMapper mapper = new ObjectMapper();
        CountingNodes localNodes = new CountingNodes(jsonSupport(mapper), mapper,
                """
                        {"slides":[{"slideId":"slide-1","index":1,"title":"第一页","xml":"%s"}]}
                        """.formatted(escapeJson(validXml("AI 第一页 marker"))),
                """
                        {"slides":[{"slideId":"slide-1","index":1,"title":"第一页","xml":"%s"}]}
                        """.formatted(escapeJson(validXml("AI 第一页 retry"))));

        assertThatThrownBy(() -> localNodes.generateSlideXml(stateWithOutline(2), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PPT batch XML generation failed after retry");
        assertThat(localNodes.calls()).isEqualTo(2);
    }

    @Test
    void validateSlideXmlRejectsInvalidModelXml() {
        ObjectMapper mapper = new ObjectMapper();
        PresentationWorkflowNodes localNodes = new PresentationWorkflowNodes(
                jsonSupport(mapper), null, null, null, null, null, mapper);
        OverAllState state = stateWithOutlineAndXml(1, List.of(
                Map.of("slideId", "slide-1", "index", 1, "title", "第一页", "xml", "<presentation></presentation>")
        ));

        assertThatThrownBy(() -> localNodes.validateSlideXml(state, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Generated slide XML failed validation");
    }

    private PresentationExecutionSupport jsonSupport(ObjectMapper mapper) {
        PresentationExecutionSupport support = mock(PresentationExecutionSupport.class);
        when(support.writeJson(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));
        return support;
    }

    private OverAllState stateWithOutline(int pageCount) {
        return new OverAllState(Map.of(
                PresentationStateKeys.TASK_ID, "task-batch",
                PresentationStateKeys.SLIDE_OUTLINE, outline(pageCount),
                PresentationStateKeys.GENERATION_OPTIONS, PresentationGenerationOptions.builder()
                        .style("minimal-professional")
                        .density("standard")
                        .speakerNotes(true)
                        .build()
        ));
    }

    private OverAllState stateWithOutlineAndXml(int pageCount, List<Map<String, Object>> slideXml) {
        return new OverAllState(Map.of(
                PresentationStateKeys.TASK_ID, "task-validate",
                PresentationStateKeys.SLIDE_OUTLINE, outline(pageCount),
                PresentationStateKeys.SLIDE_XML_LIST, slideXml
        ));
    }

    private PresentationOutline outline(int pageCount) {
        List<PresentationSlidePlan> slides = java.util.stream.IntStream.rangeClosed(1, pageCount)
                .mapToObj(index -> PresentationSlidePlan.builder()
                        .slideId("slide-" + index)
                        .index(index)
                        .title("第" + index + "页")
                        .layout(index == 1 ? "cover" : "section")
                        .keyPoints(List.of("要点 " + index))
                        .speakerNotes("备注 " + index)
                        .build())
                .toList();
        return PresentationOutline.builder()
                .title("批量 XML 测试")
                .style("minimal-professional")
                .slides(slides)
                .build();
    }

    private static String validXml(String marker) {
        return """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <style><fill><fillColor color="rgb(255,255,255)"/></fill></style>
                  <data>
                    <shape type="text" topLeftX="64" topLeftY="48" width="820" height="88"><content><p><span fontSize="30">%s</span></p></content></shape>
                    <shape type="text" topLeftX="72" topLeftY="154" width="800" height="270"><content><p><span fontSize="20">短句要点</span></p></content></shape>
                  </data>
                </slide>
                """.formatted(marker).trim();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static class CountingNodes extends PresentationWorkflowNodes {

        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        CountingNodes(PresentationExecutionSupport support, ObjectMapper objectMapper, String... responses) {
            super(support, null, null, null, null, null, objectMapper);
            this.responses.addAll(List.of(responses));
        }

        int calls() {
            return calls.get();
        }

        @Override
        protected AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
            calls.incrementAndGet();
            return new AssistantMessage(responses.removeFirst());
        }
    }
}
