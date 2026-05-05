package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PresentationReviewResult;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXml;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXmlBatch;
import com.lark.imcollab.harness.presentation.model.PresentationStoryline;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.lark.imcollab.harness.presentation.workflow.PresentationStateKeys;
import com.lark.imcollab.skills.lark.slides.LarkSlidesCreateResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PresentationWorkflowNodes {

    private static final Logger log = LoggerFactory.getLogger(PresentationWorkflowNodes.class);
    private static final int MAX_SLIDES = 10;
    private static final int BATCH_XML_MAX_ATTEMPTS = 2;
    private static final Pattern SLIDE_XML_PATTERN = Pattern.compile("(?s)<slide\\b.*?</slide>");
    private static final Pattern PAGE_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(页|p|P|slides?|Slides?)");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("(?s)<p(?:\\s[^>]*)?>(.*?)</p>");

    private final PresentationExecutionSupport support;
    private final ReactAgent storylineAgent;
    private final ReactAgent outlineAgent;
    private final ReactAgent slideXmlAgent;
    private final ReactAgent reviewAgent;
    private final LarkSlidesTool larkSlidesTool;
    private final ObjectMapper objectMapper;

    public PresentationWorkflowNodes(
            PresentationExecutionSupport support,
            @Qualifier("presentationStorylineAgent") ReactAgent storylineAgent,
            @Qualifier("presentationOutlineAgent") ReactAgent outlineAgent,
            @Qualifier("presentationSlideXmlAgent") ReactAgent slideXmlAgent,
            @Qualifier("presentationReviewAgent") ReactAgent reviewAgent,
            LarkSlidesTool larkSlidesTool,
            ObjectMapper objectMapper) {
        this.support = support;
        this.storylineAgent = storylineAgent;
        this.outlineAgent = outlineAgent;
        this.slideXmlAgent = slideXmlAgent;
        this.reviewAgent = reviewAgent;
        this.larkSlidesTool = larkSlidesTool;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Map<String, Object>> dispatchPresentationTask(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        Task task = support.loadTask(taskId);
        support.publishEvent(taskId, null, TaskEventType.STEP_STARTED, "开始生成汇报 PPT");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PresentationStateKeys.UPSTREAM_CONTEXT, buildUpstreamContext(task, state));
        result.put(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, summarizeArtifacts(taskId));
        result.put(PresentationStateKeys.GENERATION_OPTIONS, resolveGenerationOptions(taskId, task, state));
        result.put(PresentationStateKeys.WAITING_HUMAN_REVIEW, false);
        result.put(PresentationStateKeys.PRESENTATION_TITLE, presentationTitle(task));
        return CompletableFuture.completedFuture(result);
    }

    public CompletableFuture<Map<String, Object>> buildStoryline(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_STORYLINE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        String prompt = """
                请生成 PPT 叙事主线。
                任务要求：%s
                生成参数：%s
                用户反馈：%s
                输入上下文：
                %s
                已有产物摘要：
                %s
                """.formatted(instruction(state), support.writeJson(generationOptions(state)), state.value(PresentationStateKeys.USER_FEEDBACK, ""), state.value(PresentationStateKeys.UPSTREAM_CONTEXT, ""), state.value(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, ""));
        PresentationStoryline storyline = invokeStoryline(prompt, taskId);
        normalizeStoryline(storyline, state);
        recordStageElapsed(taskId, "build_storyline", startedAt, "pageCount=" + storyline.getPageCount());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.STORYLINE, storyline,
                PresentationStateKeys.PRESENTATION_TITLE, blankToDefault(storyline.getTitle(), state.value(PresentationStateKeys.PRESENTATION_TITLE, "汇报 PPT")),
                PresentationStateKeys.DONE_STORYLINE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> generateSlideOutline(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_OUTLINE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationStoryline storyline = requireValue(state, PresentationStateKeys.STORYLINE, PresentationStoryline.class);
        String prompt = """
                请基于叙事主线生成 PPT 页面大纲。
                任务要求：%s
                生成参数：%s
                叙事主线 JSON：
                %s
                输入上下文：
                %s
                """.formatted(instruction(state), support.writeJson(generationOptions(state)), support.writeJson(storyline), state.value(PresentationStateKeys.UPSTREAM_CONTEXT, ""));
        PresentationOutline outline = invokeOutline(prompt, storyline, taskId);
        normalizeOutline(outline, storyline, generationOptions(state));
        recordStageElapsed(taskId, "generate_slide_outline", startedAt, "pageCount=" + safeSlides(outline).size());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.SLIDE_OUTLINE, outline,
                PresentationStateKeys.DONE_OUTLINE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> generateSlideXml(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_XML, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        List<PresentationSlidePlan> slides = safeSlides(outline);
        List<PresentationSlideXml> slideXmlList = invokeSlideXmlBatch(buildSlideXmlBatchPrompt(outline, slides, state), slides, taskId);
        recordStageElapsed(taskId, "generate_slide_xml_batch", startedAt, "pageCount=" + slideXmlList.size());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.SLIDE_XML_LIST, slideXmlList,
                PresentationStateKeys.DONE_XML, true
        ));
    }

    public CompletableFuture<Map<String, Object>> validateSlideXml(OverAllState state, RunnableConfig config) {
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        List<PresentationSlideXml> slideXmlList = readSlideXmlList(state);
        if (slideXmlList.isEmpty()) {
            throw new IllegalStateException("No slides generated");
        }
        if (slideXmlList.size() > MAX_SLIDES) {
            throw new IllegalStateException("PPT slide count exceeds limit: " + slideXmlList.size());
        }
        List<PresentationSlideXml> validated = new ArrayList<>();
        List<PresentationSlidePlan> plans = safeSlides(outline);
        List<PresentationSlideXml> ordered = alignSlideXmlToPlans(slideXmlList, plans);
        for (int i = 0; i < ordered.size(); i++) {
            PresentationSlideXml slideXml = ordered.get(i);
            PresentationSlidePlan plan = plans.get(i);
            String xml = extractSlideXml(slideXml.getXml());
            String validationFailure = validateSlideXmlReason(xml);
            if (validationFailure != null) {
                String slideId = blankToDefault(slideXml.getSlideId(), "slide-" + (i + 1));
                log.warn("Generated slide XML failed validation: taskId={}, slideId={}, reason={}, xmlPreview={}",
                        taskId, slideId, validationFailure, xmlPreview(xml));
                throw new IllegalStateException("Generated slide XML failed validation: "
                        + slideId + " (" + validationFailure + ")");
            }
            slideXml.setSlideId(blankToDefault(slideXml.getSlideId(), plan.getSlideId()));
            slideXml.setTitle(blankToDefault(slideXml.getTitle(), plan.getTitle()));
            slideXml.setIndex(i + 1);
            slideXml.setXml(xml);
            validated.add(slideXml);
        }
        recordStageElapsed(taskId, "validate_slide_xml", startedAt, "pageCount=" + validated.size());
        return CompletableFuture.completedFuture(Map.of(PresentationStateKeys.SLIDE_XML_LIST, validated));
    }

    public CompletableFuture<Map<String, Object>> reviewPresentation(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        List<PresentationSlideXml> slideXmlList = readSlideXmlList(state);
        String prompt = """
                请审查这份 PPT 初稿是否可发布。
                任务要求：%s
                页面大纲 JSON：
                %s
                页面 XML 摘要：
                %s
                输入上下文：
                %s
                """.formatted(instruction(state), support.writeJson(outline), summarizeSlideXml(slideXmlList), state.value(PresentationStateKeys.UPSTREAM_CONTEXT, ""));
        PresentationReviewResult reviewResult = invokeReview(prompt, taskId);
        if (!reviewResult.isAccepted() && reviewResult.getMissingItems() != null && !reviewResult.getMissingItems().isEmpty()) {
            reviewResult.setSummary(blankToDefault(reviewResult.getSummary(), "PPT 已通过结构校验，仍存在内容审查建议：" + String.join("；", reviewResult.getMissingItems())));
        }
        recordStageElapsed(taskId, "review_presentation", startedAt, "accepted=" + reviewResult.isAccepted());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.REVIEW_RESULT, reviewResult,
                PresentationStateKeys.DONE_REVIEW, true
        ));
    }

    public CompletableFuture<Map<String, Object>> writeSlidesAndSync(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_WRITE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long startedAt = System.nanoTime();
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        String title = blankToDefault(state.value(PresentationStateKeys.PRESENTATION_TITLE, ""), "汇报 PPT");
        List<String> xmlPages = readSlideXmlList(state).stream()
                .map(PresentationSlideXml::getXml)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (xmlPages.isEmpty()) {
            throw new IllegalStateException("No valid slide XML pages to create");
        }
        LarkSlidesCreateResult result = larkSlidesTool.createPresentation(title, xmlPages);
        String stepId = support.findPptStep(taskId).map(com.lark.imcollab.common.model.entity.TaskStepRecord::getStepId).orElse(null);
        support.saveArtifact(taskId, stepId, title, state.value(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, ""), result.getPresentationId(), result.getPresentationUrl());
        support.publishEvent(taskId, stepId, TaskEventType.ARTIFACT_CREATED, support.writeJson(result));
        support.publishEvent(taskId, stepId, TaskEventType.STEP_COMPLETED, "PPT 已生成：" + blankToDefault(result.getPresentationUrl(), result.getPresentationId()));
        recordStageElapsed(taskId, "write_slides_and_sync", startedAt, "pageCount=" + xmlPages.size());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.PRESENTATION_ID, blankToDefault(result.getPresentationId(), ""),
                PresentationStateKeys.PRESENTATION_URL, blankToDefault(result.getPresentationUrl(), ""),
                PresentationStateKeys.DONE_WRITE, true
        ));
    }

    private PresentationStoryline invokeStoryline(String prompt, String taskId) {
        AssistantMessage response = callAgent(storylineAgent, prompt, taskId + ":presentation:storyline");
        try {
            return objectMapper.readValue(response.getText(), PresentationStoryline.class);
        } catch (Exception exception) {
            return PresentationStoryline.builder()
                    .title("汇报 PPT")
                    .audience("团队成员")
                    .goal("清晰汇报任务进展与关键结论")
                    .narrativeArc("背景与目标 -> 核心内容 -> 方案与风险 -> 下一步")
                    .style("简约专业")
                    .pageCount(5)
                    .sourceSummary(response.getText())
                    .keyMessages(List.of(truncate(response.getText(), 80)))
                    .build();
        }
    }

    private PresentationOutline invokeOutline(String prompt, PresentationStoryline storyline, String taskId) {
        AssistantMessage response = callAgent(outlineAgent, prompt, taskId + ":presentation:outline");
        try {
            return objectMapper.readValue(response.getText(), PresentationOutline.class);
        } catch (Exception exception) {
            return fallbackOutline(storyline);
        }
    }

    private PresentationSlideXml invokeSlideXml(String prompt, PresentationSlidePlan slide, String taskId) {
        AssistantMessage response = callAgent(slideXmlAgent, prompt, taskId + ":presentation:slide:" + slide.getIndex());
        try {
            PresentationSlideXml generated = objectMapper.readValue(response.getText(), PresentationSlideXml.class);
            generated.setXml(extractSlideXml(generated.getXml()));
            if (generated.getSlideId() == null || generated.getSlideId().isBlank()) {
                generated.setSlideId(slide.getSlideId());
            }
            if (generated.getTitle() == null || generated.getTitle().isBlank()) {
                generated.setTitle(slide.getTitle());
            }
            if (generated.getIndex() <= 0) {
                generated.setIndex(slide.getIndex());
            }
            return generated;
        } catch (Exception exception) {
            String xml = extractSlideXml(response.getText());
            return PresentationSlideXml.builder()
                    .slideId(blankToDefault(slide.getSlideId(), "slide-" + slide.getIndex()))
                    .index(slide.getIndex())
                    .title(slide.getTitle())
                    .xml(xml == null || xml.isBlank() ? buildSlideXmlTemplate(slide, slide.getIndex(), 1,
                            PresentationGenerationOptions.builder().style("minimal-professional").density("standard").build()) : xml)
                    .speakerNotes(slide.getSpeakerNotes())
                    .build();
        }
    }

    private String buildSlideXmlBatchPrompt(
            PresentationOutline outline,
            List<PresentationSlidePlan> slides,
            OverAllState state
    ) {
        return """
                请一次性为整份 PPT 生成飞书 Slides XML。
                PPT 标题：%s
                全局风格：%s
                生成参数：%s
                可用 XML 元素：slide/style/data/note、shape(type=text/rect)、line、content/p/ul/li。
                输出要求：
                - 返回 JSON 对象，根字段只能是 slides。
                - slides 数组长度必须等于页面计划数量：%d。
                - 每个 slides[] 元素包含 slideId、index、title、xml、speakerNotes。
                - 每个 xml 都必须是完整 <slide xmlns="http://www.larkoffice.com/sml/2.0">...</slide>。
                - 不要漏页、不要新增页、不要合并页。
                - xml 必须作为 JSON 字符串返回，双引号和换行必须正确转义。
                - 每页必须包含 <style>、<data> 和至少 2 个 <shape type="text">。
                - 所有文字必须写在 <content><p><span ...>文字</span></p></content> 或 <content><ul><li><p>...</p></li></ul></content> 内，不允许把文字直接放在 shape 节点文本里。
                - 不允许使用 markdown、html、presentation 根节点、image/table/chart/动画。
                XML 示例：
                {"slides":[{"slideId":"slide-1","index":1,"title":"示例","xml":"<slide xmlns=\\"http://www.larkoffice.com/sml/2.0\\"><style><fill><fillColor color=\\"rgb(255,255,255)\\"/></fill></style><data><shape type=\\"text\\" topLeftX=\\"64\\" topLeftY=\\"48\\" width=\\"820\\" height=\\"88\\"><content textType=\\"title\\"><p><strong><span color=\\"rgb(20,35,60)\\" fontSize=\\"32\\">示例标题</span></strong></p></content></shape><shape type=\\"text\\" topLeftX=\\"80\\" topLeftY=\\"160\\" width=\\"760\\" height=\\"220\\"><content><ul><li><p><span color=\\"rgb(20,35,60)\\" fontSize=\\"20\\">示例要点</span></p></li></ul></content></shape></data></slide>","speakerNotes":"示例备注"}]}
                页面大纲 JSON：
                %s
                全部页面计划 JSON：
                %s
                """.formatted(
                blankToDefault(outline.getTitle(), "汇报 PPT"),
                blankToDefault(outline.getStyle(), "简约专业"),
                support.writeJson(generationOptions(state)),
                slides.size(),
                support.writeJson(outline),
                support.writeJson(slides)
        );
    }

    private List<PresentationSlideXml> invokeSlideXmlBatch(
            String prompt,
            List<PresentationSlidePlan> slides,
            String taskId
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= BATCH_XML_MAX_ATTEMPTS; attempt++) {
            try {
                AssistantMessage response = callAgent(slideXmlAgent, prompt, taskId + ":presentation:slides:batch");
                PresentationSlideXmlBatch batch = parseSlideXmlBatch(response.getText());
                return alignSlideXmlToPlans(batch.getSlides(), slides);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("PPT batch XML generation attempt failed: taskId={}, attempt={}, error={}",
                        taskId, attempt, exception.getMessage());
            }
        }
        throw new IllegalStateException("PPT batch XML generation failed after retry", lastFailure);
    }

    private PresentationSlideXmlBatch parseSlideXmlBatch(String text) {
        String sanitized = stripJsonFence(text);
        try {
            return objectMapper.readValue(sanitized, PresentationSlideXmlBatch.class);
        } catch (Exception batchException) {
            try {
                List<PresentationSlideXml> slides = objectMapper.readValue(sanitized, new TypeReference<>() { });
                return PresentationSlideXmlBatch.builder().slides(slides).build();
            } catch (Exception listException) {
                throw new IllegalStateException("Invalid PPT batch XML JSON", batchException);
            }
        }
    }

    private String stripJsonFence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replaceFirst("^```(?:json)?\\s*", "");
            sanitized = sanitized.replaceFirst("\\s*```$", "");
        }
        return sanitized.trim();
    }

    private List<PresentationSlideXml> alignSlideXmlToPlans(
            List<PresentationSlideXml> generatedSlides,
            List<PresentationSlidePlan> plans
    ) {
        List<PresentationSlidePlan> safePlans = plans == null ? List.of() : plans;
        if (safePlans.isEmpty()) {
            throw new IllegalStateException("No slide plans available for generated XML");
        }
        if (generatedSlides == null || generatedSlides.size() != safePlans.size()) {
            throw new IllegalStateException("PPT batch XML page count mismatch: expected="
                    + safePlans.size() + ", actual=" + (generatedSlides == null ? 0 : generatedSlides.size()));
        }
        Map<Integer, PresentationSlideXml> byIndex = generatedSlides.stream()
                .filter(Objects::nonNull)
                .filter(slide -> slide.getIndex() > 0)
                .collect(Collectors.toMap(
                        PresentationSlideXml::getIndex,
                        slide -> slide,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<PresentationSlideXml> ordered = new ArrayList<>();
        for (int i = 0; i < safePlans.size(); i++) {
            PresentationSlidePlan plan = safePlans.get(i);
            int expectedIndex = i + 1;
            PresentationSlideXml slideXml = byIndex.get(expectedIndex);
            if (slideXml == null && i < generatedSlides.size()) {
                slideXml = generatedSlides.get(i);
            }
            if (slideXml == null) {
                throw new IllegalStateException("PPT batch XML missing page: index=" + expectedIndex);
            }
            slideXml.setSlideId(blankToDefault(slideXml.getSlideId(), plan.getSlideId()));
            slideXml.setTitle(blankToDefault(slideXml.getTitle(), plan.getTitle()));
            slideXml.setIndex(expectedIndex);
            ordered.add(slideXml);
        }
        return ordered;
    }

    private PresentationReviewResult invokeReview(String prompt, String taskId) {
        AssistantMessage response = callAgent(reviewAgent, prompt, taskId + ":presentation:review");
        try {
            PresentationReviewResult result = objectMapper.readValue(response.getText(), PresentationReviewResult.class);
            if (result.getSummary() == null || result.getSummary().isBlank()) {
                result.setSummary("PPT 初稿已完成审查");
            }
            return result;
        } catch (Exception exception) {
            return PresentationReviewResult.builder()
                    .accepted(true)
                    .missingItems(List.of())
                    .problemSlideIds(List.of())
                    .summary("PPT 初稿已通过基础审查")
                    .build();
        }
    }

    protected AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
        try {
            return agent.call(prompt, RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            throw new IllegalStateException("Agent call failed for thread: " + threadId, e);
        }
    }

    private void normalizeStoryline(PresentationStoryline storyline, OverAllState state) {
        PresentationGenerationOptions options = generationOptions(state);
        if (storyline.getTitle() == null || storyline.getTitle().isBlank()) {
            storyline.setTitle(state.value(PresentationStateKeys.PRESENTATION_TITLE, "汇报 PPT"));
        }
        if (storyline.getAudience() == null || storyline.getAudience().isBlank()) {
            storyline.setAudience(blankToDefault(options.getAudience(), "团队成员"));
        }
        if (storyline.getGoal() == null || storyline.getGoal().isBlank()) {
            storyline.setGoal(instruction(state));
        }
        storyline.setStyle(canonicalStyle(blankToDefault(options.getStyle(), storyline.getStyle())));
        int requestedPageCount = options.getPageCount();
        int pageCount = requestedPageCount > 0 ? requestedPageCount : storyline.getPageCount() <= 0 ? 5 : storyline.getPageCount();
        storyline.setPageCount(Math.max(1, Math.min(MAX_SLIDES, pageCount)));
        if (storyline.getKeyMessages() == null || storyline.getKeyMessages().isEmpty()) {
            storyline.setKeyMessages(List.of(truncate(blankToDefault(storyline.getSourceSummary(), storyline.getGoal()), 80)));
        }
    }

    private void normalizeOutline(PresentationOutline outline, PresentationStoryline storyline, PresentationGenerationOptions options) {
        if (outline.getTitle() == null || outline.getTitle().isBlank()) {
            outline.setTitle(storyline.getTitle());
        }
        if (outline.getAudience() == null || outline.getAudience().isBlank()) {
            outline.setAudience(storyline.getAudience());
        }
        outline.setStyle(canonicalStyle(blankToDefault(options == null ? null : options.getStyle(),
                blankToDefault(outline.getStyle(), storyline.getStyle()))));
        List<PresentationSlidePlan> slides = outline.getSlides() == null ? List.of() : new ArrayList<>(outline.getSlides());
        if (slides.isEmpty()) {
            slides = fallbackOutline(storyline).getSlides();
        }
        int requestedPageCount = options == null ? 0 : options.getPageCount();
        int targetCount = Math.max(1, Math.min(MAX_SLIDES,
                requestedPageCount > 0 ? requestedPageCount : storyline.getPageCount() <= 0 ? slides.size() : storyline.getPageCount()));
        slides = slides.stream()
                .filter(Objects::nonNull)
                .limit(targetCount)
                .collect(Collectors.toCollection(ArrayList::new));
        while (slides.size() < targetCount) {
            int index = slides.size() + 1;
            slides.add(PresentationSlidePlan.builder()
                    .slideId("slide-" + index)
                    .index(index)
                    .title(index == targetCount ? "总结与下一步" : "核心内容 " + index)
                    .keyPoints(storyline.getKeyMessages())
                    .layout(index == 1 ? "cover" : index == targetCount ? "summary" : "section")
                    .speakerNotes("围绕本页要点进行简洁说明。")
                    .build());
        }
        for (int i = 0; i < slides.size(); i++) {
            PresentationSlidePlan slide = slides.get(i);
            int index = i + 1;
            slide.setIndex(index);
            if (slide.getSlideId() == null || slide.getSlideId().isBlank()) {
                slide.setSlideId("slide-" + index);
            }
            if (slide.getTitle() == null || slide.getTitle().isBlank()) {
                slide.setTitle(index == 1 ? outline.getTitle() : "第 " + index + " 页");
            }
            slide.setKeyPoints(normalizeKeyPoints(slide.getKeyPoints(), storyline.getKeyMessages()));
            if (slide.getLayout() == null || slide.getLayout().isBlank()) {
                slide.setLayout(index == 1 ? "cover" : index == slides.size() ? "summary" : "section");
            }
            slide.setLayout(normalizeLayout(slide.getLayout(), index, slides.size()));
            if (options != null && !options.isSpeakerNotes()) {
                slide.setSpeakerNotes("");
            }
        }
        outline.setSlides(slides);
    }

    private PresentationOutline fallbackOutline(PresentationStoryline storyline) {
        int pageCount = Math.max(1, Math.min(MAX_SLIDES, storyline.getPageCount() <= 0 ? 5 : storyline.getPageCount()));
        List<String> keyMessages = normalizeKeyPoints(storyline.getKeyMessages(), List.of(storyline.getGoal()));
        List<PresentationSlidePlan> slides = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            slides.add(PresentationSlidePlan.builder()
                    .slideId("slide-" + i)
                    .index(i)
                    .title(i == 1 ? storyline.getTitle() : i == pageCount ? "总结与下一步" : "核心要点 " + (i - 1))
                    .keyPoints(keyMessages)
                    .layout(i == 1 ? "cover" : i == pageCount ? "summary" : "section")
                    .speakerNotes("用本页要点串联汇报主线。")
                    .build());
        }
        return PresentationOutline.builder()
                .title(storyline.getTitle())
                .audience(storyline.getAudience())
                .style(storyline.getStyle())
                .slides(slides)
                .build();
    }

    PresentationGenerationOptions resolveGenerationOptions(String taskId, Task task, OverAllState state) {
        ExecutionContract contract = task == null ? null : task.getExecutionContract();
        TaskStepRecord pptStep = hasText(taskId) ? support.findPptStep(taskId).orElse(null) : null;
        String merged = String.join("\n", List.of(
                blankToDefault(task == null ? null : task.getRawInstruction(), ""),
                blankToDefault(task == null ? null : task.getClarifiedInstruction(), ""),
                blankToDefault(task == null ? null : task.getTaskBrief(), ""),
                blankToDefault(pptStep == null ? null : pptStep.getName(), ""),
                blankToDefault(pptStep == null ? null : pptStep.getInputSummary(), ""),
                blankToDefault(state.value(PresentationStateKeys.USER_FEEDBACK, ""), ""),
                contract == null || contract.getConstraints() == null ? "" : String.join("；", contract.getConstraints())
        ));
        return PresentationGenerationOptions.builder()
                .pageCount(resolvePageCount(merged))
                .style(resolveStyle(merged, contract == null ? null : contract.getAudience()))
                .density(resolveDensity(merged))
                .audience(blankToDefault(contract == null ? null : contract.getAudience(), "团队成员"))
                .tone(resolveTone(merged))
                .speakerNotes(resolveSpeakerNotes(merged))
                .build();
    }

    private PresentationGenerationOptions generationOptions(OverAllState state) {
        Object raw = state.data().get(PresentationStateKeys.GENERATION_OPTIONS);
        if (raw == null) {
            return PresentationGenerationOptions.builder()
                    .style("minimal-professional")
                    .density("standard")
                    .audience("团队成员")
                    .tone("professional")
                    .speakerNotes(true)
                    .build();
        }
        PresentationGenerationOptions options = objectMapper.convertValue(raw, PresentationGenerationOptions.class);
        if (options.getStyle() == null || options.getStyle().isBlank()) {
            options.setStyle("minimal-professional");
        }
        if (options.getDensity() == null || options.getDensity().isBlank()) {
            options.setDensity("standard");
        }
        if (options.getAudience() == null || options.getAudience().isBlank()) {
            options.setAudience("团队成员");
        }
        if (options.getTone() == null || options.getTone().isBlank()) {
            options.setTone("professional");
        }
        return options;
    }

    private int resolvePageCount(String text) {
        String normalized = normalize(text);
        Matcher matcher = PAGE_COUNT_PATTERN.matcher(text == null ? "" : text);
        Integer requestedPageCount = null;
        while (matcher.find()) {
            int requested = Integer.parseInt(matcher.group(1));
            if (requested > 0) {
                requestedPageCount = requested;
            }
        }
        if (requestedPageCount != null) {
            return Math.min(MAX_SLIDES, requestedPageCount);
        }
        if (normalized.matches(".*(^|[^第每])一页(PPT|ppt|幻灯片|演示稿|$).*")
                || normalized.matches(".*(单页|1页|1p|1slide).*")) {
            return 1;
        }
        return 0;
    }

    private String resolveStyle(String text, String audience) {
        String normalized = normalize(blankToDefault(text, "") + " " + blankToDefault(audience, ""));
        if (normalized.matches(".*(简约专业风|简约风|简约专业|minimal|professional).*")) {
            return "minimal-professional";
        }
        if (normalized.matches(".*(深色科技风|科技风|深色|暗色|蓝黑|deep|tech).*")) {
            return "deep-tech";
        }
        if (normalized.matches(".*(浅色商务风|商务风|商务|business).*")) {
            return "business-light";
        }
        if (normalized.matches(".*(清新培训风|培训风|培训|教育|课程|清新|绿色).*")) {
            return "fresh-training";
        }
        if (normalized.matches(".*(活力创意风|创意风|创意|活力|设计|紫|粉|品牌).*")) {
            return "vibrant-creative";
        }
        if (normalized.matches(".*(科技|ai|AI|产品|技术).*")) {
            return "deep-tech";
        }
        if (normalized.matches(".*(管理层|老板|季度|经营|汇报|专业).*")) {
            return "business-light";
        }
        return "minimal-professional";
    }

    private boolean resolveSpeakerNotes(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*(不要演讲稿|不要备注|不要演讲备注|无演讲稿|无备注|无演讲备注|不需要演讲稿|不需要备注|不带备注).*")) {
            return false;
        }
        return normalized.matches(".*(演讲稿|演讲备注|备注|speakernotes?|讲稿).*");
    }

    private String resolveDensity(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*(简洁|极简|少字|大字|轻量|不要太多字).*")) {
            return "concise";
        }
        if (normalized.matches(".*(详细|信息量|多一点|完整|细一点).*")) {
            return "detailed";
        }
        return "standard";
    }

    private String resolveTone(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*(正式|管理层|老板|汇报).*")) {
            return "executive";
        }
        if (normalized.matches(".*(轻松|活泼|口语).*")) {
            return "casual";
        }
        return "professional";
    }

    private List<String> normalizeKeyPoints(List<String> source, List<String> fallback) {
        List<String> values = source == null || source.isEmpty() ? fallback : source;
        return values == null || values.isEmpty()
                ? List.of("明确目标", "聚焦重点", "推进落地")
                : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> compactSlidePoint(value.trim(), 56))
                .limit(5)
                .toList();
    }

    private List<String> normalizeKeyPointsForDensity(List<String> source, List<String> fallback, String density) {
        int limit = switch (blankToDefault(density, "standard")) {
            case "concise" -> 3;
            case "detailed" -> 5;
            default -> 4;
        };
        List<String> normalized = normalizeKeyPoints(source, fallback);
        return normalized.stream().limit(limit).toList();
    }

    private String normalizeLayout(String layout, int index, int total) {
        if (index == 1) {
            return "cover";
        }
        if (index == total) {
            return "summary";
        }
        String normalized = normalize(layout);
        if (normalized.contains("two") || normalized.contains("双栏") || normalized.contains("分栏")) {
            return "two-column";
        }
        if (normalized.contains("comparison") || normalized.contains("对比")) {
            return "comparison";
        }
        if (normalized.contains("timeline") || normalized.contains("时间线") || normalized.contains("路径")) {
            return "timeline";
        }
        if (normalized.contains("risk") || normalized.contains("风险")) {
            return "risk-list";
        }
        if (normalized.contains("metric") || normalized.contains("指标") || normalized.contains("卡片") || normalized.contains("数据")) {
            return "metric-cards";
        }
        return "section";
    }

    private String canonicalStyle(String style) {
        String normalized = normalize(style);
        if (normalized.contains("deep") || normalized.contains("tech") || normalized.contains("科技") || normalized.contains("深色")) {
            return "deep-tech";
        }
        if (normalized.contains("business") || normalized.contains("商务") || normalized.contains("管理")) {
            return "business-light";
        }
        if (normalized.contains("fresh") || normalized.contains("training") || normalized.contains("清新") || normalized.contains("培训")) {
            return "fresh-training";
        }
        if (normalized.contains("vibrant") || normalized.contains("creative") || normalized.contains("创意") || normalized.contains("活力")) {
            return "vibrant-creative";
        }
        return "minimal-professional";
    }

    String buildSlideXmlTemplate(PresentationSlidePlan slide, int index, int total, PresentationGenerationOptions options) {
        StyleProfile profile = styleProfile(options == null ? "" : options.getStyle());
        String title = blankToDefault(slide == null ? null : slide.getTitle(), index == 1 ? "汇报 PPT" : "核心内容");
        List<String> points = normalizeKeyPointsForDensity(
                slide == null ? List.of() : slide.getKeyPoints(),
                List.of("明确目标", "梳理进展", "推进下一步"),
                options == null ? "standard" : options.getDensity());
        String layout = normalizeLayout(slide == null ? "" : slide.getLayout(), index, total);
        String note = options != null && options.isSpeakerNotes() && slide != null && hasText(slide.getSpeakerNotes())
                ? "\n  <note><content textType=\"body\"><p>" + escapeXml(slide.getSpeakerNotes()) + "</p></content></note>"
                : "";
        if (index == 1) {
            return """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="0" topLeftY="0" width="960" height="18"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="72" topLeftY="96" width="70" height="8"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="72" topLeftY="118" width="810" height="138">%s</shape>
                        <shape type="text" topLeftX="78" topLeftY="286" width="760" height="166"><content textType="body" lineSpacing="multiple:1.35"><ul>%s</ul></content></shape>
                        <shape type="text" topLeftX="74" topLeftY="474" width="760" height="28">%s</shape>
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.accent(), profile.accent(),
                    titleContent(title, profile.text(), 42), bulletList(points, profile.text(), 21),
                    plainContent(profile.name(), profile.muted(), 14, false, "left"), note).trim();
        }
        if ("two-column".equals(layout) || "comparison".equals(layout)) {
            return """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="44" width="820" height="78">%s</shape>
                        <shape type="rect" topLeftX="70" topLeftY="150" width="390" height="250"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="rect" topLeftX="500" topLeftY="150" width="390" height="250"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="100" topLeftY="184" width="320" height="188"><content textType="body" lineSpacing="multiple:1.3"><ul>%s</ul></content></shape>
                        <shape type="text" topLeftX="530" topLeftY="184" width="320" height="188"><content textType="body" lineSpacing="multiple:1.3"><ul>%s</ul></content></shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile), headlineContent(title, profile.text(), 31),
                    profile.cardFill(), profile.cardBorder(), profile.cardFill(), profile.cardBorder(),
                    bulletList(firstHalf(points), profile.text(), 19), bulletList(secondHalf(points), profile.text(), 19),
                    pageNumber(index, total, profile), note).trim();
        }
        if ("timeline".equals(layout)) {
            return """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="44" width="820" height="78">%s</shape>
                        <line startX="130" startY="280" endX="830" endY="280"><border color="%s" width="3"/></line>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile), headlineContent(title, profile.text(), 31), profile.accent(),
                    timelineItems(points, profile), pageNumber(index, total, profile), note).trim();
        }
        if ("metric-cards".equals(layout) || "risk-list".equals(layout)) {
            return """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile), headlineContent(title, profile.text(), 31),
                    metricCards(points, profile), pageNumber(index, total, profile), note).trim();
        }
        return """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <style><fill><fillColor color="%s"/></fill></style>
                  <data>
                    %s
                    <shape type="text" topLeftX="64" topLeftY="48" width="820" height="88">%s</shape>
                    <shape type="rect" topLeftX="72" topLeftY="154" width="800" height="270"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                    <shape type="text" topLeftX="108" topLeftY="188" width="720" height="206"><content textType="body" lineSpacing="multiple:1.35"><ul>%s</ul></content></shape>
                    %s
                  </data>%s
                </slide>
                """.formatted(profile.background(), accentRail(profile), headlineContent(title, profile.text(), 31),
                profile.cardFill(), profile.cardBorder(), bulletList(points, profile.text(), 20), pageNumber(index, total, profile), note).trim();
    }

    private String bulletList(List<String> points, String color, int fontSize) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("明确目标", "推进落地") : points;
        StringBuilder builder = new StringBuilder();
        for (String point : safePoints) {
            builder.append("<li><p><span color=\"")
                    .append(color)
                    .append("\" fontSize=\"")
                    .append(fontSize)
                    .append("\">")
                    .append(escapeXml(point))
                    .append("</span></p></li>");
        }
        return builder.toString();
    }

    private String titleContent(String text, String color, int fontSize) {
        return "<content textType=\"title\"><p><strong><span color=\"%s\" fontSize=\"%d\">%s</span></strong></p></content>"
                .formatted(color, fontSize, escapeXml(text));
    }

    private String headlineContent(String text, String color, int fontSize) {
        return "<content><p><strong><span color=\"%s\" fontSize=\"%d\">%s</span></strong></p></content>"
                .formatted(color, fontSize, escapeXml(text));
    }

    private String plainContent(String text, String color, int fontSize, boolean bold, String align) {
        String content = "<span color=\"%s\" fontSize=\"%d\">%s</span>".formatted(color, fontSize, escapeXml(text));
        if (bold) {
            content = "<strong>" + content + "</strong>";
        }
        return "<content><p textAlign=\"%s\">%s</p></content>".formatted(align, content);
    }

    private List<String> firstHalf(List<String> points) {
        if (points == null || points.isEmpty()) {
            return List.of("明确目标");
        }
        int split = Math.max(1, (points.size() + 1) / 2);
        return points.subList(0, split);
    }

    private List<String> secondHalf(List<String> points) {
        if (points == null || points.size() <= 1) {
            return List.of("推进下一步");
        }
        int split = Math.max(1, (points.size() + 1) / 2);
        return points.subList(split, points.size());
    }

    private String accentRail(StyleProfile profile) {
        return """
                <shape type="rect" topLeftX="0" topLeftY="0" width="18" height="540"><fill><fillColor color="%s"/></fill></shape>
                <line startX="64" startY="132" endX="880" endY="132"><border color="%s" width="2"/></line>
                """.formatted(profile.accent(), profile.cardBorder()).trim();
    }

    private String pageNumber(int index, int total, StyleProfile profile) {
        return """
                <shape type="text" topLeftX="770" topLeftY="472" width="120" height="28">%s</shape>
                """.formatted(plainContent(index + "/" + Math.max(1, total), profile.muted(), 14, false, "right")).trim();
    }

    private String timelineItems(List<String> points, StyleProfile profile) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("明确目标", "梳理方案", "推进落地") : points;
        int count = Math.min(5, safePoints.size());
        int gap = count <= 1 ? 0 : 700 / (count - 1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int x = 110 + gap * i;
            builder.append("""
                    <shape type="rect" topLeftX="%d" topLeftY="262" width="36" height="36"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="%d" topLeftY="322" width="142" height="92">%s</shape>
                    """.formatted(x, profile.accent(), x - 52,
                    plainContent(safePoints.get(i), profile.text(), 16, false, "center")));
        }
        return builder.toString().trim();
    }

    private String metricCards(List<String> points, StyleProfile profile) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("明确目标", "关键进展", "下一步") : points;
        int count = Math.min(4, safePoints.size());
        int width = count <= 2 ? 350 : 180;
        int gap = count <= 2 ? 42 : 24;
        int startX = count <= 2 ? 118 : 82;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int x = startX + i * (width + gap);
            builder.append("""
                    <shape type="rect" topLeftX="%d" topLeftY="168" width="%d" height="210"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                    <shape type="rect" topLeftX="%d" topLeftY="168" width="%d" height="8"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="%d" topLeftY="202" width="%d" height="138">%s</shape>
                    """.formatted(x, width, profile.cardFill(), profile.cardBorder(), x, width, profile.accent(),
                    x + 18, width - 36, plainContent(safePoints.get(i), profile.text(), 20, true, "center")));
        }
        return builder.toString().trim();
    }

    private boolean isValidSlideXml(String xml) {
        return validateSlideXmlReason(xml) == null;
    }

    private String validateSlideXmlReason(String xml) {
        if (xml == null || xml.isBlank() || !xml.contains("<slide") || !xml.contains("<data")) {
            return "missing slide or data element";
        }
        if (xml.length() > 16000 || containsOverlongText(xml)) {
            return xml.length() > 16000 ? "xml too long" : "paragraph text too long";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (root == null || !"slide".equals(root.getLocalName() == null ? root.getNodeName() : root.getLocalName())) {
                return "root element is not slide";
            }
            if (root.getElementsByTagNameNS("*", "data").getLength() == 0) {
                return "missing data element";
            }
            if (root.getElementsByTagNameNS("*", "content").getLength() == 0) {
                return "missing content element";
            }
            if (root.getElementsByTagNameNS("*", "shape").getLength() == 0) {
                return "missing shape element";
            }
            return null;
        } catch (Exception exception) {
            return "xml parse error: " + exception.getMessage();
        }
    }

    private boolean containsOverlongText(String xml) {
        Matcher matcher = PARAGRAPH_PATTERN.matcher(xml);
        while (matcher.find()) {
            String text = matcher.group(1).replaceAll("<[^>]+>", "");
            if (text.length() > 120) {
                return true;
            }
        }
        return false;
    }

    private String extractSlideXml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = SLIDE_XML_PATTERN.matcher(raw.trim());
        return matcher.find() ? matcher.group().trim() : raw.trim();
    }

    private String xmlPreview(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        return truncate(xml.replaceAll("\\s+", " ").trim(), 600);
    }

    private PresentationSlidePlan planFromXml(PresentationSlideXml slideXml) {
        return PresentationSlidePlan.builder()
                .slideId(slideXml.getSlideId())
                .index(slideXml.getIndex())
                .title(slideXml.getTitle())
                .keyPoints(List.of(blankToDefault(slideXml.getTitle(), "核心内容")))
                .speakerNotes(slideXml.getSpeakerNotes())
                .build();
    }

    private String buildUpstreamContext(Task task, OverAllState state) {
        StringBuilder builder = new StringBuilder();
        if (task.getTaskBrief() != null && !task.getTaskBrief().isBlank()) {
            builder.append("任务摘要：").append(task.getTaskBrief()).append('\n');
        }
        ExecutionContract contract = task.getExecutionContract();
        if (contract != null) {
            appendIfPresent(builder, "受众", contract.getAudience());
            appendIfPresent(builder, "领域上下文", contract.getDomainContext());
            appendIfPresent(builder, "时间范围", contract.getTimeScope());
            if (contract.getConstraints() != null && !contract.getConstraints().isEmpty()) {
                builder.append("约束：").append(String.join("；", contract.getConstraints())).append('\n');
            }
            WorkspaceContext sourceScope = contract.getSourceScope();
            if (sourceScope != null) {
                appendWorkspaceContext(builder, sourceScope);
            }
        }
        String upstream = state.value(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, "");
        if (upstream != null && !upstream.isBlank()) {
            builder.append("上游产物：\n").append(upstream).append('\n');
        }
        return builder.isEmpty() ? "无额外上下文；请严格基于用户任务生成通用但不臆造事实的 PPT。" : builder.toString();
    }

    private void appendWorkspaceContext(StringBuilder builder, WorkspaceContext context) {
        appendIfPresent(builder, "聊天范围", context.getTimeRange());
        appendIfPresent(builder, "文档引用", context.getDocRefs() == null ? "" : String.join("；", context.getDocRefs()));
        if (context.getSelectedMessages() != null && !context.getSelectedMessages().isEmpty()) {
            builder.append("已选择消息：\n");
            context.getSelectedMessages().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .limit(20)
                    .forEach(value -> builder.append("- ").append(truncate(value, 180)).append('\n'));
        }
    }

    private String summarizeArtifacts(String taskId) {
        return support.findArtifacts(taskId).stream()
                .sorted(Comparator.comparing(Artifact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .filter(artifact -> artifact.getType() == ArtifactType.DOC_LINK
                        || artifact.getType() == ArtifactType.DOC_DRAFT
                        || artifact.getType() == ArtifactType.DOC_OUTLINE
                        || artifact.getType() == ArtifactType.SUMMARY)
                .map(artifact -> "- " + artifact.getType() + " | " + blankToDefault(artifact.getTitle(), "未命名")
                        + (artifact.getExternalUrl() == null ? "" : " | " + artifact.getExternalUrl())
                        + (artifact.getContent() == null ? "" : " | " + truncate(artifact.getContent(), 600)))
                .collect(Collectors.joining("\n"));
    }

    private String presentationTitle(Task task) {
        if (task.getExecutionContract() != null && task.getExecutionContract().getTaskBrief() != null
                && !task.getExecutionContract().getTaskBrief().isBlank()) {
            return truncate(task.getExecutionContract().getTaskBrief(), 40);
        }
        return truncate(blankToDefault(task.getClarifiedInstruction(), task.getRawInstruction()), 40);
    }

    private String instruction(OverAllState state) {
        return blankToDefault(
                state.value(PresentationStateKeys.CLARIFIED_INSTRUCTION, ""),
                state.value(PresentationStateKeys.RAW_INSTRUCTION, "生成汇报 PPT")
        );
    }

    private List<PresentationSlidePlan> safeSlides(PresentationOutline outline) {
        return outline.getSlides() == null ? List.of() : outline.getSlides().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(PresentationSlidePlan::getIndex))
                .toList();
    }

    private List<PresentationSlideXml> readSlideXmlList(OverAllState state) {
        Object raw = state.data().get(PresentationStateKeys.SLIDE_XML_LIST);
        if (raw == null) {
            return List.of();
        }
        return objectMapper.convertValue(raw, new TypeReference<>() { });
    }

    private <T> T requireValue(OverAllState state, String key, Class<T> type) {
        Object raw = state.data().get(key);
        if (raw == null) {
            throw new IllegalStateException("Missing state value: " + key);
        }
        return objectMapper.convertValue(raw, type);
    }

    private String summarizeSlideXml(List<PresentationSlideXml> slideXmlList) {
        return slideXmlList.stream()
                .map(slide -> "- " + blankToDefault(slide.getSlideId(), "slide-" + slide.getIndex())
                        + " | " + blankToDefault(slide.getTitle(), "")
                        + " | xmlChars=" + (slide.getXml() == null ? 0 : slide.getXml().length()))
                .collect(Collectors.joining("\n"));
    }

    private void recordStageElapsed(String taskId, String stage, long startedAtNanos, String detail) {
        long elapsedMs = Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
        String message = "PPT stage " + stage + " completed in " + elapsedMs + "ms"
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        log.info("{}: taskId={}", message, taskId);
        if (support != null && taskId != null && !taskId.isBlank()) {
            support.publishEvent(taskId, null, TaskEventType.PLANNING_STARTED, message);
        }
    }

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append("：").append(value).append('\n');
        }
    }

    private StyleProfile styleProfile(String style) {
        return switch (canonicalStyle(style)) {
            case "deep-tech" -> new StyleProfile(
                    "高对比科技风",
                    "rgb(246,249,255)",
                    "rgb(37,99,235)",
                    "rgb(15,23,42)",
                    "rgb(71,85,105)",
                    "rgb(255,255,255)",
                    "rgb(147,197,253)");
            case "business-light" -> new StyleProfile(
                    "浅色商务风",
                    "rgb(248,250,252)",
                    "rgb(30,60,114)",
                    "rgb(30,41,59)",
                    "rgb(100,116,139)",
                    "rgb(255,255,255)",
                    "rgb(203,213,225)");
            case "fresh-training" -> new StyleProfile(
                    "清新培训风",
                    "rgb(250,253,250)",
                    "rgb(34,197,94)",
                    "rgb(31,41,55)",
                    "rgb(71,85,105)",
                    "rgb(255,255,255)",
                    "rgb(187,247,208)");
            case "vibrant-creative" -> new StyleProfile(
                    "活力创意风",
                    "linear-gradient(135deg,rgba(88,28,135,1) 0%,rgba(190,24,93,1) 100%)",
                    "rgb(251,191,36)",
                    "rgb(255,255,255)",
                    "rgb(253,244,255)",
                    "rgba(255,255,255,0.16)",
                    "rgba(255,255,255,0.32)");
            default -> new StyleProfile(
                    "简约专业风",
                    "rgb(248,250,252)",
                    "rgb(59,130,246)",
                    "rgb(15,23,42)",
                    "rgb(100,116,139)",
                    "rgb(255,255,255)",
                    "rgb(226,232,240)");
        };
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String compactSlidePoint(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ")
                .replaceAll("\\.{3,}|…", "")
                .trim();
        if (normalized.length() <= maxLength) {
            return trimTrailingPunctuation(normalized);
        }
        int cut = -1;
        String separators = "，,；;。:：、/（(";
        for (int i = Math.min(maxLength, normalized.length() - 1); i >= Math.max(8, maxLength / 2); i--) {
            if (separators.indexOf(normalized.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            cut = maxLength;
            while (cut < normalized.length()
                    && cut < maxLength + 16
                    && isAsciiWordChar(normalized.charAt(cut - 1))
                    && isAsciiWordChar(normalized.charAt(cut))) {
                cut++;
            }
        }
        return trimTrailingPunctuation(normalized.substring(0, cut));
    }

    private boolean isAsciiWordChar(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
    }

    private String trimTrailingPunctuation(String value) {
        return value == null ? "" : value.replaceAll("[,，;；:：、。\\s]+$", "").trim();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record StyleProfile(
            String name,
            String background,
            String accent,
            String text,
            String muted,
            String cardFill,
            String cardBorder
    ) {
    }
}
