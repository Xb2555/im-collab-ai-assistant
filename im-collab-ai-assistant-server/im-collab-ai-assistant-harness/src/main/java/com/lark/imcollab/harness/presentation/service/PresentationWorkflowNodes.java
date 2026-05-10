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
import com.lark.imcollab.common.model.entity.PresentationAssetRef;
import com.lark.imcollab.common.model.entity.PresentationElementIR;
import com.lark.imcollab.common.model.entity.PresentationIR;
import com.lark.imcollab.common.model.entity.PresentationLayoutSpec;
import com.lark.imcollab.common.model.entity.PresentationSlideIR;
import com.lark.imcollab.common.model.enums.PresentationEditability;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.harness.presentation.model.PresentationAssetPlan;
import com.lark.imcollab.harness.presentation.model.PresentationAssetResources;
import com.lark.imcollab.harness.presentation.model.PresentationGenerationOptions;
import com.lark.imcollab.harness.presentation.model.PresentationImagePlan;
import com.lark.imcollab.harness.presentation.model.PresentationImageResources;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PexelsSearchResponse;
import com.lark.imcollab.harness.presentation.model.PresentationPreflightResult;
import com.lark.imcollab.harness.presentation.model.PresentationReviewResult;
import com.lark.imcollab.harness.presentation.model.PresentationSlidePlan;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXml;
import com.lark.imcollab.harness.presentation.model.PresentationStoryline;
import com.lark.imcollab.harness.presentation.model.PresentationVisualPlan;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.lark.imcollab.harness.presentation.workflow.PresentationStateKeys;
import com.lark.imcollab.skills.lark.slides.LarkSlidesCreateResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesMediaUploadResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PresentationWorkflowNodes {

    private static final Logger log = LoggerFactory.getLogger(PresentationWorkflowNodes.class);

    private static final int MAX_SLIDES = 12;
    private static final Pattern SLIDE_XML_PATTERN = Pattern.compile("(?s)<slide\\b.*?</slide>");
    private static final Pattern PAGE_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(页|p|P|slides?|Slides?)");
    private static final List<String> COVER_VARIANTS = List.of("hero-band", "center-stack", "asymmetric-title");
    private static final List<String> SECTION_VARIANTS = List.of("headline-panel", "rail-notes", "split-band");
    private static final List<String> TWO_COLUMN_VARIANTS = List.of("dual-cards", "offset-columns");
    private static final List<String> TIMELINE_VARIANTS = List.of("horizontal-milestones", "stacked-steps");
    private static final List<String> METRIC_VARIANTS = List.of("top-stripe-cards", "compact-grid", "spotlight-metric");
    private static final List<String> SUMMARY_VARIANTS = List.of("closing-checklist", "next-step-board");
    private static final List<String> TOC_VARIANTS = List.of("toc-list");
    private static final List<String> TRANSITION_VARIANTS = List.of("section-break");
    private static final List<String> THANKS_VARIANTS = List.of("closing-thanks");
    private static final String DEFAULT_REPORT_DATE = "2026年05月10日";
    private static final Pattern REPORTER_PATTERN = Pattern.compile("(?:汇报人|汇报人员|报告人|主讲人)[:：\\s]*([^\\n，,；;]{1,32})");
    private static final Pattern REPORT_DATE_PATTERN = Pattern.compile("(?:汇报时间|报告时间|日期|时间)[:：\\s]*((?:20\\d{2}|\\d{2})[年\\-/\\.\\s]*\\d{1,2}[月\\-/\\.\\s]*\\d{1,2}日?)");
    private static final String PEXELS_SEARCH_API = "https://api.pexels.com/v1/search?per_page=6&page=1&query=";
    private static final Set<String> SAFE_IMAGE_DOMAINS = Set.of(
            "unsplash.com", "images.unsplash.com",
            "pexels.com", "images.pexels.com",
            "pixabay.com", "cdn.pixabay.com",
            "undraw.co",
            "storyset.com",
            "manypixels.co", "www.manypixels.co",
            "svgrepo.com", "www.svgrepo.com"
    );

    private final PresentationExecutionSupport support;
    private final ReactAgent storylineAgent;
    private final ReactAgent outlineAgent;
    private final ReactAgent imagePlannerAgent;
    private final ReactAgent imageFetcherAgent;
    private final ReactAgent slideXmlAgent;
    private final ReactAgent reviewAgent;
    private final LarkSlidesTool larkSlidesTool;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Path assetWorkspaceDirectory;
    private final String pexelsApiKey;

    public PresentationWorkflowNodes(
            PresentationExecutionSupport support,
            @Qualifier("presentationStorylineAgent") ReactAgent storylineAgent,
            @Qualifier("presentationOutlineAgent") ReactAgent outlineAgent,
            @Qualifier("presentationImagePlannerAgent") ReactAgent imagePlannerAgent,
            @Qualifier("presentationImageFetcherAgent") ReactAgent imageFetcherAgent,
            @Qualifier("presentationSlideXmlAgent") ReactAgent slideXmlAgent,
            @Qualifier("presentationReviewAgent") ReactAgent reviewAgent,
            LarkSlidesTool larkSlidesTool,
            ObjectMapper objectMapper,
            @Value("${pexels.api-key:}") String pexelsApiKey) {
        this.support = support;
        this.storylineAgent = storylineAgent;
        this.outlineAgent = outlineAgent;
        this.imagePlannerAgent = imagePlannerAgent;
        this.imageFetcherAgent = imageFetcherAgent;
        this.slideXmlAgent = slideXmlAgent;
        this.reviewAgent = reviewAgent;
        this.larkSlidesTool = larkSlidesTool;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.assetWorkspaceDirectory = Path.of("").toAbsolutePath().normalize().resolve(".ppt-generated-assets");
        this.pexelsApiKey = blankToDefault(pexelsApiKey, "");
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
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
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
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
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
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.SLIDE_OUTLINE, outline,
                PresentationStateKeys.DONE_OUTLINE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> planSlideVisuals(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_VISUAL_PLAN, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        PresentationGenerationOptions options = generationOptions(state);
        List<PresentationVisualPlan.SlideVisualSpec> slides = safeSlides(outline).stream()
                .map(slide -> PresentationVisualPlan.SlideVisualSpec.builder()
                        .slideId(slide.getSlideId())
                        .templateVariant(slide.getTemplateVariant())
                        .density(options.getDensity())
                        .imageSlots(usesImageSlot(slide) ? 1 : 0)
                        .chartSlots("data".equalsIgnoreCase(slide.getVisualEmphasis()) ? 1 : 0)
                        .backgroundStyle(effectiveThemeFamily(options))
                        .accentStyle(blankToDefault(slide.getVisualEmphasis(), "balance"))
                        .build())
                .toList();
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.VISUAL_PLAN, PresentationVisualPlan.builder().slides(slides).build(),
                PresentationStateKeys.DONE_VISUAL_PLAN, true
        ));
    }

    public CompletableFuture<Map<String, Object>> planSlideAssets(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_ASSET_PLAN, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        String prompt = """
                请为这份 PPT 规划图片资源。
                PPT 标题：%s
                风格：%s
                页面计划：%s
                上游摘要：%s
                """.formatted(
                blankToDefault(outline.getTitle(), "汇报 PPT"),
                effectiveThemeFamily(generationOptions(state)),
                support.writeJson(safeSlides(outline)),
                state.value(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, "")
        );
        PresentationImagePlan imagePlan = invokeImagePlan(prompt, taskId);
        log.info("presentation asset plan generated: taskId={}, pagePlanCount={}",
                taskId,
                imagePlan == null || imagePlan.getPagePlans() == null ? 0 : imagePlan.getPagePlans().size());
        Map<String, PresentationImagePlan.PageImagePlan> planBySlide = imagePlan.getPagePlans() == null ? Map.of() : imagePlan.getPagePlans().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(PresentationImagePlan.PageImagePlan::getSlideId, value -> value, (left, right) -> left, LinkedHashMap::new));
        List<PresentationAssetPlan.SlideAssetPlan> slides = safeSlides(outline).stream()
                .map(slide -> {
                    PresentationImagePlan.PageImagePlan pagePlan = planBySlide.get(slide.getSlideId());
                    return PresentationAssetPlan.SlideAssetPlan.builder()
                            .slideId(slide.getSlideId())
                            .contentImageTasks(pagePlan == null ? List.of() : safeAssetTasks(pagePlan.getContentImageTasks()))
                            .illustrationTasks(pagePlan == null ? List.of() : safeAssetTasks(pagePlan.getIllustrationTasks()))
                            .diagramTasks(pagePlan == null ? List.of() : safeDiagramTasks(pagePlan.getDiagramTasks()))
                            .chartTasks("data".equalsIgnoreCase(slide.getVisualEmphasis())
                                    ? List.of(PresentationAssetPlan.AssetTask.builder()
                                    .query(blankToDefault(slide.getTitle(), "key metric chart"))
                                    .purpose("用于数据表达")
                                    .preferredSourceType("CHART")
                                    .preferredDomains(List.of())
                                    .build())
                                    : List.of())
                            .build();
                })
                .toList();
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.ASSET_PLAN, PresentationAssetPlan.builder().slides(slides).build(),
                PresentationStateKeys.DONE_ASSET_PLAN, true
        ));
    }

    public CompletableFuture<Map<String, Object>> resolveSlideAssets(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_ASSET_RESOLVE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        PresentationAssetPlan assetPlan = requireValue(state, PresentationStateKeys.ASSET_PLAN, PresentationAssetPlan.class);
        PresentationImageResources imageResources = resolveImageResources(assetPlan);
        log.info("presentation asset resources fetched: taskId={}, resourcePageCount={}",
                taskId,
                imageResources == null || imageResources.getResources() == null ? 0 : imageResources.getResources().size());
        List<PresentationAssetResources.SlideAssetResource> slides = toResolvedSlideResources(
                assetPlan,
                imageResources);
        log.info("presentation asset resources resolved: taskId={}, resolvedSlides={}, downloadedImageCount={}, downloadedIllustrationCount={}",
                taskId,
                slides.size(),
                slides.stream().filter(Objects::nonNull).map(PresentationAssetResources.SlideAssetResource::getImages).filter(Objects::nonNull).mapToLong(List::size).sum(),
                slides.stream().filter(Objects::nonNull).map(PresentationAssetResources.SlideAssetResource::getIllustrations).filter(Objects::nonNull).mapToLong(List::size).sum());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.ASSET_RESOURCES, PresentationAssetResources.builder().slides(slides).build(),
                PresentationStateKeys.DONE_ASSET_RESOLVE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> buildPresentationIr(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_IR, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        PresentationAssetResources resources = requireValue(state, PresentationStateKeys.ASSET_RESOURCES, PresentationAssetResources.class);
        PresentationGenerationOptions options = generationOptions(state);
        List<PresentationSlideIR> slides = safeSlides(outline).stream()
                .map(slide -> buildSlideIr(slide, options, resources))
                .toList();
        PresentationIR ir = PresentationIR.builder()
                .title(blankToDefault(outline.getTitle(), state.value(PresentationStateKeys.PRESENTATION_TITLE, "汇报 PPT")))
                .themeFamily(effectiveThemeFamily(options))
                .styleMode(blankToDefault(options.getStyle(), "minimal-professional"))
                .width(960)
                .height(540)
                .slides(slides)
                .build();
        log.info("presentation ir built: slideCount={}, imageElementCount={}",
                slides.size(),
                slides.stream()
                        .filter(Objects::nonNull)
                        .map(PresentationSlideIR::getElements)
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .filter(Objects::nonNull)
                        .filter(element -> element.getElementKind() == PresentationElementKind.IMAGE)
                        .count());
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.PRESENTATION_IR, ir,
                PresentationStateKeys.DONE_IR, true
        ));
    }

    public CompletableFuture<Map<String, Object>> generateSlideXml(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_XML, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        PresentationIR ir = requireValue(state, PresentationStateKeys.PRESENTATION_IR, PresentationIR.class);
        List<PresentationSlideXml> slideXmlList = new ArrayList<>();
        List<PresentationSlideIR> irSlides = ir.getSlides() == null ? List.of() : ir.getSlides();
        for (int index = 0; index < safeSlides(outline).size(); index++) {
            PresentationSlidePlan slide = safeSlides(outline).get(index);
            String prompt = """
                    请为下面这页生成飞书 Slides XML。
                    PPT 标题：%s
                    全局风格：%s
                    生成参数：%s
                    可用 XML 元素：slide/style/data/note、shape(type=text/rect)、line、content/p/ul/li、img。
                    页面计划 JSON：
                    %s
                    你必须遵守页面计划中的 layout、templateVariant、visualEmphasis，生成与该模板变体一致的结构。
                    """.formatted(blankToDefault(outline.getTitle(), "汇报 PPT"), effectiveThemeFamily(generationOptions(state)), support.writeJson(generationOptions(state)), support.writeJson(slide));
            PresentationSlideXml generated = invokeSlideXml(prompt, slide, taskId);
            String compiledXml = index < irSlides.size() ? compileSlideXml(irSlides.get(index), safeSlides(outline).size(), generationOptions(state)) : null;
            generated.setXml(hasText(compiledXml) ? compiledXml : generated.getXml());
            log.info("slide xml generated: taskId={}, slideId={}, hasCompiledXml={}, containsImg={}",
                    taskId,
                    generated.getSlideId(),
                    hasText(compiledXml),
                    hasText(generated.getXml()) && generated.getXml().contains("<img "));
            slideXmlList.add(generated);
        }
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.SLIDE_XML_LIST, slideXmlList,
                PresentationStateKeys.DONE_XML, true
        ));
    }

    public CompletableFuture<Map<String, Object>> validateSlideXml(OverAllState state, RunnableConfig config) {
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
        PresentationGenerationOptions options = generationOptions(state);
        for (int i = 0; i < slideXmlList.size(); i++) {
            PresentationSlideXml slideXml = slideXmlList.get(i);
            String xml = slideXml.getXml();
            if (!isValidSlideXml(xml)) {
                throw new IllegalStateException("Generated slide XML failed validation: " + blankToDefault(slideXml.getSlideId(), "slide-" + (i + 1)));
            }
            log.info("slide xml validated: slideId={}, containsImg={}",
                    slideXml.getSlideId(),
                    hasText(xml) && xml.contains("<img "));
            slideXml.setXml(xml);
            validated.add(slideXml);
        }
        PresentationPreflightResult preflightResult = PresentationPreflightResult.builder()
                .passed(true)
                .warnings(List.of())
                .build();
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.SLIDE_XML_LIST, validated,
                PresentationStateKeys.PREFLIGHT_RESULT, preflightResult,
                PresentationStateKeys.DONE_PREFLIGHT, true
        ));
    }

    public CompletableFuture<Map<String, Object>> reviewPresentation(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
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
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.REVIEW_RESULT, reviewResult,
                PresentationStateKeys.DONE_REVIEW, true
        ));
    }

    public CompletableFuture<Map<String, Object>> writeSlidesAndSync(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PresentationStateKeys.TASK_ID, "");
        try (ExecutionAttemptContext.Scope ignored = bindExecutionAttempt(state, taskId)) {
            return doWriteSlidesAndSync(state, taskId);
        }
    }

    private CompletableFuture<Map<String, Object>> doWriteSlidesAndSync(OverAllState state, String taskId) {
        if (Boolean.TRUE.equals(state.value(PresentationStateKeys.DONE_WRITE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        support.ensureExecutionCanContinue(taskId);
        String title = blankToDefault(state.value(PresentationStateKeys.PRESENTATION_TITLE, ""), "汇报 PPT");
        PresentationOutline outline = requireValue(state, PresentationStateKeys.SLIDE_OUTLINE, PresentationOutline.class);
        PresentationAssetResources resources = requireValue(state, PresentationStateKeys.ASSET_RESOURCES, PresentationAssetResources.class);
        PresentationGenerationOptions options = generationOptions(state);
        support.ensureExecutionCanContinue(taskId);
        LarkSlidesCreateResult result = larkSlidesTool.createPresentation(title, List.of());
        log.info("empty presentation created before media upload: taskId={}, presentationId={}, presentationUrl={}",
                taskId, result.getPresentationId(), result.getPresentationUrl());
        PresentationAssetResources uploadedResources = uploadResolvedAssets(result.getPresentationId(), resources);
        PresentationIR finalIr = PresentationIR.builder()
                .title(title)
                .themeFamily(effectiveThemeFamily(options))
                .styleMode(blankToDefault(options.getStyle(), "minimal-professional"))
                .width(960)
                .height(540)
                .slides(safeSlides(outline).stream()
                        .map(slide -> buildSlideIr(slide, options, uploadedResources))
                        .toList())
                .build();
        List<String> xmlPages = finalIr.getSlides() == null ? List.of() : finalIr.getSlides().stream()
                .map(slide -> compileSlideXml(slide, finalIr.getSlides().size(), options))
                .filter(this::hasText)
                .toList();
        log.info("presentation xml compiled for create: taskId={}, slideCount={}, imgSlideCount={}",
                taskId,
                xmlPages.size(),
                xmlPages.stream().filter(xml -> xml.contains("<img ")).count());
        if (xmlPages.isEmpty()) {
            throw new IllegalStateException("No valid slide XML pages to create");
        }
        support.ensureExecutionCanContinue(taskId);
        for (String xmlPage : xmlPages) {
            larkSlidesTool.createSlide(result.getPresentationId(), xmlPage, null);
        }
        log.info("presentation slides written: taskId={}, presentationId={}, slideCount={}",
                taskId, result.getPresentationId(), xmlPages.size());
        support.ensureExecutionCanContinue(taskId);
        String stepId = support.findPptStep(taskId).map(com.lark.imcollab.common.model.entity.TaskStepRecord::getStepId).orElse(null);
        support.saveArtifact(taskId, stepId, title, state.value(PresentationStateKeys.UPSTREAM_ARTIFACT_SUMMARY, ""), result.getPresentationId(), result.getPresentationUrl());
        support.publishEvent(taskId, stepId, TaskEventType.ARTIFACT_CREATED, support.writeJson(result));
        support.publishEvent(taskId, stepId, TaskEventType.STEP_COMPLETED, "PPT 已生成：" + blankToDefault(result.getPresentationUrl(), result.getPresentationId()));
        return CompletableFuture.completedFuture(Map.of(
                PresentationStateKeys.PRESENTATION_ID, blankToDefault(result.getPresentationId(), ""),
                PresentationStateKeys.PRESENTATION_URL, blankToDefault(result.getPresentationUrl(), ""),
                PresentationStateKeys.ASSET_RESOURCES, uploadedResources,
                PresentationStateKeys.PRESENTATION_IR, finalIr,
                PresentationStateKeys.SLIDE_XML_LIST, finalIr.getSlides() == null ? List.of() : finalIr.getSlides().stream()
                        .map(slide -> PresentationSlideXml.builder()
                                .slideId(slide.getSlideId())
                                .index(slide.getPageIndex() == null ? 0 : slide.getPageIndex())
                                .title(slide.getTitle())
                                .xml(compileSlideXml(slide, finalIr.getSlides().size(), options))
                                .speakerNotes(slide.getMessage())
                                .build())
                        .toList(),
                PresentationStateKeys.DONE_WRITE, true
        ));
    }

    private ExecutionAttemptContext.Scope bindExecutionAttempt(OverAllState state, String taskId) {
        String attemptId = state.value(PresentationStateKeys.EXECUTION_ATTEMPT_ID, "");
        return ExecutionAttemptContext.open(taskId, attemptId);
    }

    private PresentationStoryline invokeStoryline(String prompt, String taskId) {
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        AssistantMessage response = callAgent(storylineAgent, prompt, taskId + ":presentation:storyline");
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        try {
            return objectMapper.readValue(response.getText(), PresentationStoryline.class);
        } catch (Exception exception) {
            return PresentationStoryline.builder()
                    .title("汇报 PPT")
                    .audience("团队成员")
                    .goal("清晰汇报任务进展与关键结论")
                    .narrativeArc("背景与目标 -> 核心内容 -> 方案与风险 -> 下一步")
                    .style("简约专业")
                    .pageCount(8)
                    .sourceSummary(response.getText())
                    .keyMessages(List.of(truncate(response.getText(), 80)))
                    .build();
        }
    }

    private PresentationOutline invokeOutline(String prompt, PresentationStoryline storyline, String taskId) {
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        AssistantMessage response = callAgent(outlineAgent, prompt, taskId + ":presentation:outline");
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        try {
            return objectMapper.readValue(response.getText(), PresentationOutline.class);
        } catch (Exception exception) {
            return fallbackOutline(storyline);
        }
    }

    private PresentationSlideXml invokeSlideXml(String prompt, PresentationSlidePlan slide, String taskId) {
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        AssistantMessage response = callAgent(slideXmlAgent, prompt, taskId + ":presentation:slide:" + slide.getIndex());
        support.ensureExecutionCanContinue(rootTaskId(taskId));
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
                            PresentationGenerationOptions.builder()
                                    .style("minimal-professional")
                                    .themeFamily("minimal-professional")
                                    .density("standard")
                                    .templateDiversity("balanced")
                                    .allowVariantMixing(true)
                                    .build()) : xml)
                    .speakerNotes(slide.getSpeakerNotes())
                    .build();
        }
    }

    private PresentationReviewResult invokeReview(String prompt, String taskId) {
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        AssistantMessage response = callAgent(reviewAgent, prompt, taskId + ":presentation:review");
        support.ensureExecutionCanContinue(rootTaskId(taskId));
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
        support.ensureExecutionCanContinue(rootTaskId(threadId));
        try {
            AssistantMessage response = agent.call(prompt, RunnableConfig.builder().threadId(threadId).build());
            if (looksInterrupted(response == null ? null : response.getText())) {
                Thread.currentThread().interrupt();
            }
            support.ensureExecutionCanContinue(rootTaskId(threadId));
            return response;
        } catch (Exception e) {
            if (isInterruptedFailure(e)) {
                Thread.currentThread().interrupt();
                support.ensureExecutionCanContinue(rootTaskId(threadId));
            }
            throw new IllegalStateException("Agent call failed for thread: " + threadId, e);
        }
    }

    private String rootTaskId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "";
        }
        int separator = threadId.indexOf(':');
        return separator < 0 ? threadId : threadId.substring(0, separator);
    }

    private boolean looksInterrupted(String text) {
        return text != null && text.toLowerCase().contains("thread interrupted");
    }

    private boolean isInterruptedFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (looksInterrupted(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        storyline.setStyle(effectiveThemeFamily(options, storyline.getStyle()));
        int requestedPageCount = options.getPageCount();
        int pageCount = requestedPageCount > 0 ? requestedPageCount : storyline.getPageCount() <= 0 ? 8 : storyline.getPageCount();
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
        outline.setStyle(effectiveThemeFamily(options, blankToDefault(outline.getStyle(), storyline.getStyle())));
        List<PresentationSlidePlan> slides = outline.getSlides() == null ? List.of() : new ArrayList<>(outline.getSlides());
        if (slides.isEmpty()) {
            slides = fallbackOutline(storyline).getSlides();
        }
        PresentationCoverMeta coverMeta = resolveCoverMeta(storyline);
        int requestedPageCount = options == null ? 0 : options.getPageCount();
        int targetCount = Math.max(1, Math.min(MAX_SLIDES,
                requestedPageCount > 0 ? requestedPageCount : storyline.getPageCount() <= 0 ? slides.size() : storyline.getPageCount()));
        slides = slides.stream()
                .filter(Objects::nonNull)
                .limit(targetCount)
                .collect(Collectors.toCollection(ArrayList::new));
        while (slides.size() < targetCount) {
            int index = slides.size() + 1;
            slides.add(defaultSlide(index, targetCount, storyline.getKeyMessages(), options));
        }
        slides = repairOutlineStructure(slides, storyline, targetCount, coverMeta);
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
            slide.setTitle(normalizeSlideTitle(slide.getTitle(), index, slides.size()));
            slide.setKeyPoints(normalizeSlideKeyPoints(slide, storyline, coverMeta));
            if (slide.getLayout() == null || slide.getLayout().isBlank()) {
                slide.setLayout(index == 1 ? "cover" : index == slides.size() ? "summary" : "section");
            }
            if (!hasText(slide.getPageType())) {
                slide.setPageType(defaultPageType(slide.getLayout(), index, slides.size()));
            }
            if (!hasText(slide.getPageSubType())) {
                slide.setPageSubType(defaultPageSubType(slide.getPageType()));
            }
            if (!hasText(slide.getSectionId()) && index > 1 && index < slides.size()) {
                slide.setSectionId("section-" + Math.max(1, index - 1));
            }
            if (!hasText(slide.getSectionTitle()) && hasText(slide.getSectionId())) {
                slide.setSectionTitle(slide.getTitle());
            }
            if (slide.getSectionOrder() == null && hasText(slide.getSectionId())) {
                slide.setSectionOrder(Math.max(1, index - 1));
            }
            slide.setLayout(normalizeLayout(slide.getLayout(), index, slides.size()));
            slide.setTemplateVariant(normalizeTemplateVariant(
                    slide.getLayout(),
                    slide.getTemplateVariant(),
                    index,
                    slides.size(),
                    options));
            slide.setVisualEmphasis(normalizeVisualEmphasis(
                    slide.getVisualEmphasis(),
                    slide.getLayout(),
                    slide.getTemplateVariant(),
                    index,
                    slides.size()));
            if (options != null && !options.isSpeakerNotes()) {
                slide.setSpeakerNotes("");
            }
        }
        outline.setSlides(slides);
    }

    private PresentationOutline fallbackOutline(PresentationStoryline storyline) {
        List<String> keyMessages = normalizeKeyPoints(storyline.getKeyMessages(), List.of(storyline.getGoal()));
        List<PresentationSlidePlan> slides = buildStructuredFallbackSlides(storyline, keyMessages);
        return PresentationOutline.builder()
                .title(storyline.getTitle())
                .audience(storyline.getAudience())
                .style(storyline.getStyle())
                .slides(slides)
                .build();
    }

    private List<PresentationSlidePlan> buildStructuredFallbackSlides(PresentationStoryline storyline, List<String> keyMessages) {
        int requested = Math.max(6, Math.min(MAX_SLIDES, storyline.getPageCount() <= 0 ? 8 : storyline.getPageCount()));
        List<PresentationSlidePlan> slides = new ArrayList<>();
        PresentationCoverMeta coverMeta = resolveCoverMeta(storyline);
        String coverSubtitle = firstNonBlank(
                keyMessages.isEmpty() ? null : keyMessages.get(0),
                storyline.getGoal(),
                storyline.getNarrativeArc(),
                "围绕主题进行结构化汇报");
        slides.add(PresentationSlidePlan.builder()
                .slideId("slide-1")
                .index(1)
                .title(storyline.getTitle())
                .keyPoints(List.of(
                        compactSlidePoint(coverSubtitle, 28),
                        "汇报人：" + coverMeta.presenter(),
                        "汇报时间：" + coverMeta.reportDate()))
                .layout("cover")
                .pageType("COVER")
                .pageSubType("COVER.HERO")
                .templateVariant("hero-band")
                .visualEmphasis("title")
                .speakerNotes("介绍标题与汇报目标。")
                .build());
        slides.add(PresentationSlidePlan.builder()
                .slideId("slide-2")
                .index(2)
                .title("目录")
                .keyPoints(keyMessages.stream().limit(Math.min(4, keyMessages.size())).toList())
                .layout("section")
                .pageType("TOC")
                .pageSubType("TOC.AGENDA")
                .templateVariant("headline-panel")
                .visualEmphasis("balance")
                .speakerNotes("说明本次汇报的章节结构。")
                .build());
        int remainingSlides = Math.max(1, requested - slides.size() - 1);
        int transitionBudget = Math.max(0, remainingSlides / 3);
        int contentBudget = remainingSlides;
        for (int i = 0; i < keyMessages.size() && contentBudget > 0; i++) {
            int sectionOrder = i + 1;
            String sectionId = "section-" + sectionOrder;
            String sectionTitle = compactSlidePoint(keyMessages.get(Math.min(i, keyMessages.size() - 1)), 14);
            if (transitionBudget > 0 && contentBudget >= 2) {
                slides.add(PresentationSlidePlan.builder()
                        .slideId("slide-" + (slides.size() + 1))
                        .index(slides.size() + 1)
                        .title(sectionTitle)
                        .keyPoints(List.of(""))
                        .layout("section")
                        .pageType("TRANSITION")
                        .pageSubType("TRANSITION.SECTION_BREAK")
                        .sectionId(sectionId)
                        .sectionTitle(sectionTitle)
                        .sectionOrder(sectionOrder)
                        .templateVariant("rail-notes")
                        .visualEmphasis("title")
                        .speakerNotes("做章节过渡。")
                        .build());
                transitionBudget--;
                contentBudget--;
            }
            slides.add(PresentationSlidePlan.builder()
                    .slideId("slide-" + (slides.size() + 1))
                    .index(slides.size() + 1)
                    .title(sectionTitle)
                    .keyPoints(normalizeKeyPointsForDensity(keyMessages, List.of(sectionTitle), "standard"))
                    .layout(sectionOrder % 4 == 1 ? "two-column"
                            : sectionOrder % 4 == 2 ? "timeline"
                            : sectionOrder % 4 == 3 ? "comparison" : "metric-cards")
                    .pageType(sectionOrder % 4 == 1 ? "CONTENT"
                            : sectionOrder % 4 == 2 ? "TIMELINE"
                            : sectionOrder % 4 == 3 ? "COMPARISON" : "CHART")
                    .pageSubType(sectionOrder % 4 == 1 ? "CONTENT.HALF_IMAGE_HALF_TEXT"
                            : sectionOrder % 4 == 2 ? "TIMELINE.HORIZONTAL_ARROW"
                            : sectionOrder % 4 == 3 ? "COMPARISON.COMPETITOR_ANALYSIS" : "CHART.LINE")
                    .sectionId(sectionId)
                    .sectionTitle(sectionTitle)
                    .sectionOrder(sectionOrder)
                    .templateVariant(sectionOrder % 4 == 1 ? (sectionOrder % 2 == 0 ? "offset-columns" : "dual-cards")
                            : sectionOrder % 4 == 2 ? (sectionOrder % 2 == 0 ? "stacked-steps" : "horizontal-milestones")
                            : sectionOrder % 4 == 3 ? "offset-columns" : (sectionOrder % 2 == 0 ? "compact-grid" : "spotlight-metric"))
                    .visualEmphasis(sectionOrder % 4 == 2 || sectionOrder % 4 == 0 ? "data" : "balance")
                    .speakerNotes("围绕章节重点展开。")
                    .build());
            contentBudget--;
        }
        slides.add(PresentationSlidePlan.builder()
                .slideId("slide-" + (slides.size() + 1))
                .index(slides.size() + 1)
                .title("感谢聆听")
                .keyPoints(List.of("期待交流指正"))
                .layout("summary")
                .pageType("THANKS")
                .pageSubType("THANKS.CLOSING")
                .templateVariant("next-step-board")
                .visualEmphasis("action")
                .speakerNotes("结束致谢。")
                .build());
        return slides.stream().limit(MAX_SLIDES).toList();
    }

    private boolean shouldRebuildStructuredOutline(
            List<PresentationSlidePlan> slides,
            PresentationStoryline storyline,
            PresentationGenerationOptions options) {
        int targetCount = Math.max(1, Math.min(MAX_SLIDES,
                options != null && options.getPageCount() > 0
                        ? options.getPageCount()
                        : storyline.getPageCount() <= 0 ? slides.size() : storyline.getPageCount()));
        if (targetCount < 6) {
            return false;
        }
        if (slides.size() < Math.min(5, targetCount)) {
            return true;
        }
        boolean hasToc = slides.stream().anyMatch(slide -> "TOC".equalsIgnoreCase(blankToDefault(slide.getPageType(), "")));
        boolean hasThanks = slides.stream().anyMatch(slide -> "THANKS".equalsIgnoreCase(blankToDefault(slide.getPageType(), "")));
        boolean hasTransition = slides.stream().anyMatch(slide -> "TRANSITION".equalsIgnoreCase(blankToDefault(slide.getPageType(), "")));
        return !hasToc || !hasThanks || !hasTransition;
    }

    private PresentationSlidePlan defaultSlide(int index, int total, List<String> keyMessages, PresentationGenerationOptions options) {
        String layout = index == 1 ? "cover" : index == total ? "summary" : "section";
        return PresentationSlidePlan.builder()
                .slideId("slide-" + index)
                .index(index)
                .title(index == total ? "感谢聆听" : "核心内容 " + index)
                .keyPoints(keyMessages)
                .layout(layout)
                .pageType(defaultPageType(layout, index, total))
                .pageSubType(defaultPageSubType(defaultPageType(layout, index, total)))
                .templateVariant(defaultTemplateVariant(layout, index, total, options))
                .visualEmphasis(defaultVisualEmphasis(layout, index, total))
                .speakerNotes("围绕本页要点进行简洁说明。")
                .build();
    }

    private String defaultPageType(String layout, int index, int total) {
        if (index == 1) {
            return "COVER";
        }
        if (index == total) {
            return "THANKS";
        }
        return switch (normalizeLayout(layout, index, total)) {
            case "timeline" -> "TIMELINE";
            case "comparison" -> "COMPARISON";
            case "metric-cards", "risk-list" -> "CHART";
            case "two-column" -> "CONTENT";
            default -> "BACKGROUND";
        };
    }

    private String defaultPageSubType(String pageType) {
        return switch (blankToDefault(pageType, "CONTENT")) {
            case "COVER" -> "COVER.HERO";
            case "TOC" -> "TOC.AGENDA";
            case "TRANSITION" -> "TRANSITION.SECTION_BREAK";
            case "BACKGROUND" -> "CONTENT.USER_INTENT_BACKGROUND";
            case "TIMELINE" -> "TIMELINE.HORIZONTAL_ARROW";
            case "COMPARISON" -> "COMPARISON.COMPETITOR_ANALYSIS";
            case "CHART" -> "CHART.LINE";
            case "THANKS" -> "THANKS.CLOSING";
            default -> "CONTENT.HALF_IMAGE_HALF_TEXT";
        };
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
                .themeFamily(resolveStyle(merged, contract == null ? null : contract.getAudience()))
                .density(resolveDensity(merged))
                .audience(blankToDefault(contract == null ? null : contract.getAudience(), "团队成员"))
                .tone(resolveTone(merged))
                .speakerNotes(resolveSpeakerNotes(merged))
                .templateDiversity(resolveTemplateDiversity(merged))
                .allowVariantMixing(resolveAllowVariantMixing(merged))
                .build();
    }

    private PresentationGenerationOptions generationOptions(OverAllState state) {
        Object raw = state.data().get(PresentationStateKeys.GENERATION_OPTIONS);
        if (raw == null) {
            return PresentationGenerationOptions.builder()
                    .style("minimal-professional")
                    .themeFamily("minimal-professional")
                    .density("standard")
                    .audience("团队成员")
                    .tone("professional")
                    .speakerNotes(true)
                    .templateDiversity("balanced")
                    .allowVariantMixing(true)
                    .build();
        }
        PresentationGenerationOptions options = objectMapper.convertValue(raw, PresentationGenerationOptions.class);
        if (options.getStyle() == null || options.getStyle().isBlank()) {
            options.setStyle("minimal-professional");
        }
        if (options.getThemeFamily() == null || options.getThemeFamily().isBlank()) {
            options.setThemeFamily(canonicalStyle(options.getStyle()));
        } else {
            options.setThemeFamily(canonicalStyle(options.getThemeFamily()));
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
        if (options.getTemplateDiversity() == null || options.getTemplateDiversity().isBlank()) {
            options.setTemplateDiversity("balanced");
        }
        return options;
    }

    private int resolvePageCount(String text) {
        String normalized = normalize(text);
        Matcher matcher = PAGE_COUNT_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            int requested = Integer.parseInt(matcher.group(1));
            if (requested > 0) {
                return Math.min(MAX_SLIDES, requested);
            }
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

    private String resolveTemplateDiversity(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*(稳定|统一|保守|规整|一致).*")) {
            return "stable";
        }
        if (normalized.matches(".*(多样|丰富|变化|活泼|灵活).*")) {
            return "rich";
        }
        return "balanced";
    }

    private boolean resolveAllowVariantMixing(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*(统一模板|同一模板|不要混用|不混用|模板统一).*")) {
            return false;
        }
        return true;
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

    private String effectiveThemeFamily(PresentationGenerationOptions options) {
        return effectiveThemeFamily(options, null);
    }

    private String effectiveThemeFamily(PresentationGenerationOptions options, String fallback) {
        if (options != null && hasText(options.getThemeFamily())) {
            return canonicalStyle(options.getThemeFamily());
        }
        if (options != null && hasText(options.getStyle())) {
            return canonicalStyle(options.getStyle());
        }
        return canonicalStyle(fallback);
    }

    private String normalizeTemplateVariant(String layout, String templateVariant, int index, int total, PresentationGenerationOptions options) {
        String normalizedLayout = normalizeLayout(layout, index, total);
        List<String> variants = variantsForLayout(normalizedLayout);
        if (templateVariant != null) {
            String requested = templateVariant.trim();
            for (String variant : variants) {
                if (variant.equalsIgnoreCase(requested)) {
                    return variant;
                }
            }
        }
        return defaultTemplateVariant(normalizedLayout, index, total, options);
    }

    private String defaultTemplateVariant(String layout, int index, int total, PresentationGenerationOptions options) {
        String normalizedLayout = normalizeLayout(layout, index, total);
        if ("cover".equals(normalizedLayout)) {
            return "hero-band";
        }
        if ("summary".equals(normalizedLayout)) {
            return "next-step-board";
        }
        List<String> variants = variantsForLayout(normalizedLayout);
        if (variants.isEmpty()) {
            return "headline-panel";
        }
        if (options != null && !options.isAllowVariantMixing()) {
            return variants.get(0);
        }
        int variantIndex = switch (blankToDefault(options == null ? null : options.getTemplateDiversity(), "balanced")) {
            case "stable" -> 0;
            case "rich" -> Math.floorMod(index * 2 + total, variants.size());
            default -> Math.floorMod(index - 1, variants.size());
        };
        return variants.get(variantIndex);
    }

    private String defaultVisualEmphasis(String layout, int index, int total) {
        String normalizedLayout = normalizeLayout(layout, index, total);
        return switch (normalizedLayout) {
            case "cover" -> "title";
            case "metric-cards" -> "data";
            case "risk-list", "summary" -> "action";
            default -> "balance";
        };
    }

    private String normalizeVisualEmphasis(String emphasis, String layout, String templateVariant, int index, int total) {
        if (emphasis == null || emphasis.isBlank()) {
            return defaultVisualEmphasis(layout, index, total);
        }
        String normalized = normalize(emphasis);
        if (normalized.contains("title") || normalized.contains("标题")) {
            return "title";
        }
        if (normalized.contains("data") || normalized.contains("指标") || normalized.contains("数据")) {
            return "data";
        }
        if (normalized.contains("action") || normalized.contains("行动") || normalized.contains("下一步")) {
            return "action";
        }
        return "balance";
    }

    private List<String> variantsForLayout(String layout) {
        return switch (layout) {
            case "cover" -> COVER_VARIANTS;
            case "two-column", "comparison" -> TWO_COLUMN_VARIANTS;
            case "timeline" -> TIMELINE_VARIANTS;
            case "metric-cards", "risk-list" -> METRIC_VARIANTS;
            case "summary" -> SUMMARY_VARIANTS;
            default -> SECTION_VARIANTS;
        };
    }

    String buildSlideXmlTemplate(PresentationSlidePlan slide, int index, int total, PresentationGenerationOptions options) {
        StyleProfile profile = styleProfile(effectiveThemeFamily(options));
        String title = blankToDefault(slide == null ? null : slide.getTitle(), index == 1 ? "汇报 PPT" : "核心内容");
        List<String> points = normalizeKeyPointsForDensity(
                slide == null ? List.of() : slide.getKeyPoints(),
                List.of("明确目标", "梳理进展", "推进下一步"),
                options == null ? "standard" : options.getDensity());
        String layout = normalizeLayout(slide == null ? "" : slide.getLayout(), index, total);
        String templateVariant = normalizeTemplateVariant(
                layout,
                slide == null ? null : slide.getTemplateVariant(),
                index,
                total,
                options);
        String emphasis = normalizeVisualEmphasis(
                slide == null ? null : slide.getVisualEmphasis(),
                layout,
                templateVariant,
                index,
                total);
        String note = options != null && options.isSpeakerNotes() && slide != null && hasText(slide.getSpeakerNotes())
                ? "\n  <note><content textType=\"body\"><p>" + escapeXml(slide.getSpeakerNotes()) + "</p></content></note>"
                : "";
        String pageType = blankToDefault(slide == null ? null : slide.getPageType(), defaultPageType(layout, index, total));
        if ("THANKS".equalsIgnoreCase(pageType) && "summary".equals(layout)) {
            return buildThanksSlide(title, profile, note);
        }
        if ("TOC".equalsIgnoreCase(pageType) && "section".equals(layout)) {
            return buildTocSlide(title, points, profile, note);
        }
        if ("TRANSITION".equalsIgnoreCase(pageType) && "section".equals(layout)) {
            return buildTransitionSlide(title, points, profile, note);
        }
        return switch (layout) {
            case "cover" -> buildCoverSlide(title, points, profile, templateVariant, emphasis, note);
            case "two-column", "comparison" -> buildTwoColumnSlide(title, points, profile, templateVariant, emphasis, index, total, note);
            case "timeline" -> buildTimelineSlide(title, points, profile, templateVariant, emphasis, index, total, note);
            case "metric-cards", "risk-list" -> buildMetricCardsSlide(title, points, profile, templateVariant, emphasis, index, total, note);
            case "summary" -> buildSummarySlide(title, points, profile, templateVariant, emphasis, index, total, note);
            default -> buildSectionSlide(title, points, profile, templateVariant, emphasis, index, total, note);
        };
    }

    private String buildTocSlide(String title, List<String> points, StyleProfile profile, String note) {
        List<String> tocPoints = normalizeTocPoints(points);
        return """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <style><fill><fillColor color="%s"/></fill></style>
                  <data>
                    <shape type="rect" topLeftX="0" topLeftY="0" width="960" height="18"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="64" topLeftY="52" width="820" height="72">%s</shape>
                    <shape type="rect" topLeftX="72" topLeftY="150" width="816" height="278"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                    <shape type="text" topLeftX="108" topLeftY="190" width="744" height="212"><content textType="body" lineSpacing="multiple:1.45"><ol>%s</ol></content></shape>
                  </data>%s
                </slide>
                """.formatted(profile.background(), profile.accent(),
                headlineContent(title, profile.text(), 32),
                profile.cardFill(), profile.cardBorder(),
                orderedList(tocPoints, profile.text(), 20), note).trim();
    }

    private String buildTransitionSlide(String title, List<String> points, StyleProfile profile, String note) {
        return """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <style><fill><fillColor color="%s"/></fill></style>
                  <data>
                    <shape type="rect" topLeftX="0" topLeftY="0" width="960" height="540"><fill><fillColor color="rgba(15,23,42,0.20)"/></fill></shape>
                    <shape type="rect" topLeftX="88" topLeftY="112" width="512" height="244"><fill><fillColor color="rgba(255,255,255,0.93)"/></fill><border color="rgba(255,255,255,0.98)" width="1"/></shape>
                    <shape type="rect" topLeftX="88" topLeftY="112" width="512" height="12"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="132" topLeftY="154" width="404" height="88">%s</shape>
                    <shape type="text" topLeftX="134" topLeftY="262" width="404" height="76"><content textType="body" lineSpacing="multiple:1.25"><p><span color="%s" fontSize="20">%s</span></p></content></shape>
                  </data>%s
                </slide>
                """.formatted(profile.background(), profile.accent(),
                titleContent(title, profile.text(), 38),
                profile.muted(), escapeXml(points.isEmpty() ? "进入下一章节" : points.get(0)), note).trim();
    }

    private String buildThanksSlide(String title, StyleProfile profile, String note) {
        return """
                <slide xmlns="http://www.larkoffice.com/sml/2.0">
                  <style><fill><fillColor color="%s"/></fill></style>
                  <data>
                    <shape type="rect" topLeftX="72" topLeftY="74" width="430" height="392"><fill><fillColor color="rgba(255,255,255,0.93)"/></fill><border color="%s" width="1"/></shape>
                    <shape type="rect" topLeftX="72" topLeftY="74" width="430" height="12"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="126" topLeftY="184" width="300" height="88">%s</shape>
                    <shape type="text" topLeftX="126" topLeftY="298" width="280" height="34">%s</shape>
                  </data>%s
                </slide>
                """.formatted(profile.background(), profile.cardBorder(), profile.accent(),
                titleContent(title, profile.text(), 40),
                plainContent("期待交流指正", profile.muted(), 16, false, "center"),
                note).trim();
    }

    private String buildCoverSlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, String note) {
        String subtitle = coverSubtitle(points);
        String footer = coverFooter(points);
        return switch (templateVariant) {
            case "center-stack" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="132" topLeftY="86" width="696" height="356"><fill><fillColor color="%s"/></fill><border color="%s" width="2"/></shape>
                        <shape type="rect" topLeftX="132" topLeftY="86" width="696" height="14"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="188" topLeftY="146" width="584" height="112">%s</shape>
                        <shape type="text" topLeftX="210" topLeftY="286" width="540" height="58">%s</shape>
                        <shape type="rect" topLeftX="210" topLeftY="372" width="136" height="40"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="346" topLeftY="372" width="178" height="40"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="222" topLeftY="380" width="112" height="22">%s</shape>
                        <shape type="text" topLeftX="360" topLeftY="380" width="150" height="22">%s</shape>
                        <shape type="rect" topLeftX="540" topLeftY="372" width="136" height="40"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="676" topLeftY="372" width="108" height="40"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="552" topLeftY="380" width="112" height="22">%s</shape>
                        <shape type="text" topLeftX="690" topLeftY="380" width="80" height="22">%s</shape>
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.cardFill(), profile.cardBorder(), profile.accent(),
                    titleContent(title, profile.text(), "title".equals(emphasis) ? 40 : 36),
                    plainContent(subtitle, profile.muted(), 22, false, "center"),
                    profile.accent(),
                    plainContent("汇报人", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报人"), profile.text(), 14, false, "center"),
                    profile.accent(),
                    plainContent("汇报时间", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报时间"), profile.text(), 14, false, "center"),
                    note).trim();
            case "asymmetric-title" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="0" topLeftY="0" width="220" height="540"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="48" topLeftY="100" width="90" height="8"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="248" topLeftY="106" width="620" height="150">%s</shape>
                        <shape type="text" topLeftX="250" topLeftY="286" width="560" height="64">%s</shape>
                        <shape type="rect" topLeftX="250" topLeftY="390" width="126" height="38"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="376" topLeftY="390" width="154" height="38"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="260" topLeftY="398" width="104" height="20">%s</shape>
                        <shape type="text" topLeftX="388" topLeftY="398" width="130" height="20">%s</shape>
                        <shape type="rect" topLeftX="548" topLeftY="390" width="126" height="38"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="674" topLeftY="390" width="150" height="38"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="558" topLeftY="398" width="104" height="20">%s</shape>
                        <shape type="text" topLeftX="686" topLeftY="398" width="126" height="20">%s</shape>
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.cardFill(), profile.accent(),
                    titleContent(title, profile.text(), "title".equals(emphasis) ? 44 : 40),
                    plainContent(subtitle, profile.muted(), 22, false, "left"),
                    profile.accent(),
                    plainContent("汇报人", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报人"), profile.text(), 14, false, "center"),
                    profile.accent(),
                    plainContent("汇报时间", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报时间"), profile.text(), 14, false, "center"),
                    note).trim();
            default -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="0" topLeftY="0" width="960" height="540"><fill><fillColor color="rgba(15,23,42,0.28)"/></fill></shape>
                        <shape type="rect" topLeftX="88" topLeftY="82" width="748" height="320"><fill><fillColor color="rgba(255,255,255,0.94)"/></fill><border color="rgba(255,255,255,0.98)" width="1"/></shape>
                        <shape type="rect" topLeftX="88" topLeftY="82" width="748" height="12"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="132" topLeftY="130" width="662" height="122">%s</shape>
                        <shape type="text" topLeftX="136" topLeftY="272" width="620" height="58">%s</shape>
                        <shape type="rect" topLeftX="136" topLeftY="346" width="126" height="38"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="262" topLeftY="346" width="162" height="38"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="146" topLeftY="354" width="104" height="20">%s</shape>
                        <shape type="text" topLeftX="274" topLeftY="354" width="138" height="20">%s</shape>
                        <shape type="rect" topLeftX="446" topLeftY="346" width="126" height="38"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="rect" topLeftX="572" topLeftY="346" width="188" height="38"><fill><fillColor color="rgba(255,255,255,0.96)"/></fill></shape>
                        <shape type="text" topLeftX="456" topLeftY="354" width="104" height="20">%s</shape>
                        <shape type="text" topLeftX="584" topLeftY="354" width="164" height="20">%s</shape>
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.accent(),
                    titleContent(title, "rgb(15,23,42)", "title".equals(emphasis) ? 42 : 40),
                    plainContent(subtitle, "rgb(71,85,105)", 22, false, "left"),
                    profile.accent(),
                    plainContent("汇报人", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报人"), "rgb(15,23,42)", 14, false, "center"),
                    profile.accent(),
                    plainContent("汇报时间", "rgb(255,255,255)", 14, true, "center"),
                    plainContent(coverMetaValue(points, "汇报时间"), "rgb(15,23,42)", 14, false, "center"),
                    note).trim();
        };
    }

    private String buildSectionSlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, int index, int total, String note) {
        return switch (templateVariant) {
            case "rail-notes" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="44" topLeftY="38" width="12" height="442"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="84" topLeftY="44" width="788" height="78">%s</shape>
                        <shape type="text" topLeftX="104" topLeftY="156" width="428" height="252"><content textType="body" lineSpacing="multiple:1.4"><ul>%s</ul></content></shape>
                        <shape type="rect" topLeftX="584" topLeftY="154" width="254" height="220"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="610" topLeftY="188" width="204" height="146">%s</shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.accent(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 34 : 31),
                    bulletList(points, profile.text(), 20),
                    profile.cardFill(), profile.cardBorder(),
                    plainContent("", profile.muted(), 16, false, "left"),
                    pageNumber(index, total, profile), note).trim();
            case "split-band" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="rect" topLeftX="0" topLeftY="74" width="960" height="92"><fill><fillColor color="%s"/></fill></shape>
                        <shape type="text" topLeftX="64" topLeftY="88" width="800" height="62">%s</shape>
                        <shape type="rect" topLeftX="64" topLeftY="206" width="832" height="244"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="104" topLeftY="238" width="742" height="180"><content textType="body" lineSpacing="multiple:1.35"><ul>%s</ul></content></shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), profile.cardFill(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 34 : 30),
                    "data".equals(emphasis) ? profile.background() : profile.cardFill(),
                    profile.cardBorder(),
                    bulletList(points, profile.text(), "data".equals(emphasis) ? 22 : 20),
                    pageNumber(index, total, profile), note).trim();
            default -> """
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
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 34 : 31),
                    profile.cardFill(), profile.cardBorder(),
                    bulletList(points, profile.text(), 20),
                    pageNumber(index, total, profile), note).trim();
        };
    }

    private String buildTwoColumnSlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, int index, int total, String note) {
        return switch (templateVariant) {
            case "offset-columns" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="64" topLeftY="44" width="760" height="78">%s</shape>
                        <shape type="rect" topLeftX="66" topLeftY="144" width="404" height="286"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="rect" topLeftX="500" topLeftY="144" width="350" height="286"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="102" topLeftY="184" width="332" height="214"><content textType="body" lineSpacing="multiple:1.34"><ul>%s</ul></content></shape>
                        <shape type="text" topLeftX="536" topLeftY="392" width="278" height="22">%s</shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    profile.cardFill(), profile.cardBorder(), profile.cardFill(), profile.cardBorder(),
                    bulletList(points, profile.text(), 19),
                    plainContent("", profile.muted(), 12, false, "center"),
                    pageNumber(index, total, profile), note).trim();
            default -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="44" width="820" height="78">%s</shape>
                        <shape type="rect" topLeftX="66" topLeftY="144" width="410" height="286"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="rect" topLeftX="500" topLeftY="144" width="350" height="286"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="102" topLeftY="184" width="338" height="214"><content textType="body" lineSpacing="multiple:1.34"><ul>%s</ul></content></shape>
                        <shape type="text" topLeftX="536" topLeftY="392" width="278" height="22">%s</shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    profile.cardFill(), profile.cardBorder(), profile.cardFill(), profile.cardBorder(),
                    bulletList(points, profile.text(), 19),
                    plainContent("", profile.muted(), 12, false, "center"),
                    pageNumber(index, total, profile), note).trim();
        };
    }

    private String buildTimelineSlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, int index, int total, String note) {
        return switch (templateVariant) {
            case "stacked-steps" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    stackedTimelineItems(points, profile, "action".equals(emphasis)),
                    pageNumber(index, total, profile), note).trim();
            default -> """
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
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31), profile.accent(),
                    timelineItems(points, profile), pageNumber(index, total, profile), note).trim();
        };
    }

    private String buildMetricCardsSlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, int index, int total, String note) {
        return switch (templateVariant) {
            case "compact-grid" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    compactMetricGrid(points, profile, "data".equals(emphasis)),
                    pageNumber(index, total, profile), note).trim();
            case "spotlight-metric" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="64" topLeftY="40" width="820" height="78">%s</shape>
                        <shape type="rect" topLeftX="74" topLeftY="154" width="260" height="234"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="108" topLeftY="204" width="190" height="126">%s</shape>
                        <shape type="rect" topLeftX="376" topLeftY="154" width="492" height="234"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="410" topLeftY="188" width="424" height="170"><content textType="body" lineSpacing="multiple:1.35"><ul>%s</ul></content></shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    profile.cardFill(), profile.cardBorder(),
                    plainContent(firstMetric(points), profile.text(), "data".equals(emphasis) ? 24 : 20, true, "center"),
                    profile.cardFill(), profile.cardBorder(),
                    bulletList(remainingMetrics(points), profile.text(), 18),
                    pageNumber(index, total, profile), note).trim();
            default -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    metricCards(points, profile), pageNumber(index, total, profile), note).trim();
        };
    }

    private String buildSummarySlide(String title, List<String> points, StyleProfile profile, String templateVariant, String emphasis, int index, int total, String note) {
        return switch (templateVariant) {
            case "next-step-board" -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        <shape type="rect" topLeftX="74" topLeftY="148" width="250" height="258"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="rect" topLeftX="354" topLeftY="148" width="250" height="258"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="rect" topLeftX="634" topLeftY="148" width="250" height="258"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        %s
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    profile.cardFill(), profile.cardBorder(),
                    profile.cardFill(), profile.cardBorder(),
                    profile.cardFill(), profile.cardBorder(),
                    summaryBoard(points, profile),
                    pageNumber(index, total, profile), note).trim();
            default -> """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="%s"/></fill></style>
                      <data>
                        %s
                        <shape type="text" topLeftX="64" topLeftY="42" width="820" height="78">%s</shape>
                        <shape type="rect" topLeftX="82" topLeftY="154" width="786" height="248"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                        <shape type="text" topLeftX="116" topLeftY="188" width="712" height="180"><content textType="body" lineSpacing="multiple:1.4"><ul>%s</ul></content></shape>
                        %s
                      </data>%s
                    </slide>
                    """.formatted(profile.background(), accentRail(profile),
                    headlineContent(title, profile.text(), "title".equals(emphasis) ? 33 : 31),
                    profile.cardFill(), profile.cardBorder(),
                    bulletList(points, profile.text(), "action".equals(emphasis) ? 22 : 20),
                    pageNumber(index, total, profile), note).trim();
        };
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

    private String orderedList(List<String> points, String color, int fontSize) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("章节一", "章节二") : points;
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

    private List<String> normalizeTocPoints(List<String> points) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("项目背景", "核心方案", "风险与计划") : points;
        return safePoints.stream()
                .map(this::summarizeAgendaPoint)
                .filter(this::hasText)
                .distinct()
                .limit(5)
                .toList();
    }

    private List<PresentationSlidePlan> repairOutlineStructure(
            List<PresentationSlidePlan> slides,
            PresentationStoryline storyline,
            int targetCount,
            PresentationCoverMeta coverMeta) {
        if (slides == null || slides.isEmpty()) {
            return fallbackOutline(storyline).getSlides();
        }
        List<PresentationSlidePlan> repaired = new ArrayList<>(slides);
        if (repaired.size() >= 2) {
            PresentationSlidePlan last = repaired.get(repaired.size() - 1);
            PresentationSlidePlan beforeLast = repaired.get(repaired.size() - 2);
            if ("TRANSITION".equalsIgnoreCase(blankToDefault(beforeLast.getPageType(), ""))
                    && !"CONTENT".equalsIgnoreCase(blankToDefault(last.getPageType(), ""))
                    && !"TIMELINE".equalsIgnoreCase(blankToDefault(last.getPageType(), ""))
                    && !"COMPARISON".equalsIgnoreCase(blankToDefault(last.getPageType(), ""))
                    && !"CHART".equalsIgnoreCase(blankToDefault(last.getPageType(), ""))) {
                int insertIndex = repaired.size() - 1;
                PresentationSlidePlan transition = beforeLast;
                repaired.add(insertIndex, PresentationSlidePlan.builder()
                        .slideId("slide-" + (insertIndex + 1))
                        .index(insertIndex + 1)
                        .title(blankToDefault(transition.getSectionTitle(), transition.getTitle()))
                        .keyPoints(normalizeKeyPointsForDensity(storyline.getKeyMessages(), List.of(blankToDefault(transition.getSectionTitle(), transition.getTitle())), "standard"))
                        .layout("two-column")
                        .pageType("CONTENT")
                        .pageSubType("CONTENT.HALF_IMAGE_HALF_TEXT")
                        .sectionId(transition.getSectionId())
                        .sectionTitle(blankToDefault(transition.getSectionTitle(), transition.getTitle()))
                        .sectionOrder(transition.getSectionOrder())
                        .templateVariant("dual-cards")
                        .visualEmphasis("balance")
                        .speakerNotes("围绕章节重点展开。")
                        .build());
            }
        }
        if (repaired.size() > targetCount && targetCount > 0) {
            repaired = new ArrayList<>(repaired.subList(0, targetCount));
        }
        if (!repaired.isEmpty()) {
            repaired.get(0).setKeyPoints(List.of(
                    firstNonBlank(repaired.get(0).getKeyPoints() == null || repaired.get(0).getKeyPoints().isEmpty() ? null : repaired.get(0).getKeyPoints().get(0),
                            storyline.getGoal(),
                            "围绕主题进行结构化汇报"),
                    "汇报人：" + coverMeta.presenter(),
                    "汇报时间：" + coverMeta.reportDate()));
        }
        return repaired;
    }

    private List<String> normalizeSlideKeyPoints(
            PresentationSlidePlan slide,
            PresentationStoryline storyline,
            PresentationCoverMeta coverMeta) {
        if (slide == null) {
            return normalizeKeyPoints(List.of(), storyline.getKeyMessages());
        }
        String pageType = blankToDefault(slide.getPageType(), "");
        if ("COVER".equalsIgnoreCase(pageType)) {
            String subtitle = firstNonBlank(
                    slide.getKeyPoints() == null || slide.getKeyPoints().isEmpty() ? null : slide.getKeyPoints().get(0),
                    storyline.getGoal(),
                    storyline.getNarrativeArc(),
                    "围绕主题进行结构化汇报");
            return List.of(
                    compactSlidePoint(subtitle, 30),
                    "汇报人：" + coverMeta.presenter(),
                    "汇报时间：" + coverMeta.reportDate());
        }
        if ("TRANSITION".equalsIgnoreCase(pageType)) {
            List<String> source = slide.getKeyPoints() == null ? List.of() : slide.getKeyPoints();
            String lead = source.stream()
                    .filter(this::hasText)
                    .map(String::trim)
                    .filter(value -> !value.contains("本章节"))
                    .findFirst()
                    .orElse("");
            return hasText(lead) ? List.of(compactSlidePoint(lead, 30)) : List.of();
        }
        if ("THANKS".equalsIgnoreCase(pageType)) {
            return List.of("期待交流指正");
        }
        return normalizeKeyPoints(slide.getKeyPoints(), storyline.getKeyMessages());
    }

    private String normalizeSlideTitle(String title, int index, int total) {
        if (!hasText(title)) {
            return index == total ? "感谢聆听" : "第 " + index + " 页";
        }
        if (index == 2 && "目录".equals(title.trim())) {
            return "目录";
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String summarizeAgendaPoint(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int cut = normalized.length();
        String separators = "，,；;。:：、|/（(";
        for (int i = 0; i < normalized.length(); i++) {
            if (separators.indexOf(normalized.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        String summarized = normalized.substring(0, Math.min(cut, 16)).trim();
        if (!hasText(summarized)) {
            summarized = compactSlidePoint(normalized, 16);
        }
        return trimTrailingPunctuation(summarized);
    }

    private String titleContent(String text, String color, int fontSize) {
        return "<content textType=\"title\"><p><strong><span color=\"%s\" fontSize=\"%d\">%s</span></strong></p></content>"
                .formatted(color, fontSize, escapeXml(text));
    }

    private String headlineContent(String text, String color, int fontSize) {
        return "<content><p><strong><span color=\"%s\" fontSize=\"%d\">%s</span></strong></p></content>"
                .formatted(color, fontSize, escapeXml(text));
    }

    private String coverSubtitle(List<String> points) {
        if (points == null || points.isEmpty()) {
            return "围绕主题进行结构化汇报";
        }
        return compactSlidePoint(firstNonBlank(points.get(0), "围绕主题进行结构化汇报"), 30);
    }

    private PresentationCoverMeta resolveCoverMeta(PresentationStoryline storyline) {
        String merged = String.join("\n", List.of(
                blankToDefault(storyline == null ? null : storyline.getTitle(), ""),
                blankToDefault(storyline == null ? null : storyline.getGoal(), ""),
                blankToDefault(storyline == null ? null : storyline.getNarrativeArc(), ""),
                blankToDefault(storyline == null ? null : storyline.getSourceSummary(), ""),
                storyline == null || storyline.getKeyMessages() == null ? "" : String.join("\n", storyline.getKeyMessages())
        ));
        String presenter = extractByPattern(merged, REPORTER_PATTERN, "张三");
        String reportDate = normalizeReportDate(extractByPattern(merged, REPORT_DATE_PATTERN, DEFAULT_REPORT_DATE));
        return new PresentationCoverMeta(presenter, reportDate);
    }

    private String coverFooter(List<String> points) {
        if (points == null || points.size() < 2) {
            return "汇报人：张三    汇报时间：" + DEFAULT_REPORT_DATE;
        }
        String presenter = firstMatching(points, value -> value.startsWith("汇报人"));
        String reportDate = firstMatching(points, value -> value.startsWith("汇报时间"));
        return firstNonBlank(presenter, "汇报人：张三") + "    " + firstNonBlank(reportDate, "汇报时间：" + DEFAULT_REPORT_DATE);
    }

    private String coverMetaValue(List<String> points, String prefix) {
        if (points == null || !hasText(prefix)) {
            return "";
        }
        String matched = firstMatching(points, value -> value.startsWith(prefix + "：") || value.startsWith(prefix + ":"));
        if (!hasText(matched)) {
            return "";
        }
        return matched.replaceFirst("^" + prefix + "[:：]\\s*", "").trim();
    }

    private String plainContent(String text, String color, int fontSize, boolean bold, String align) {
        String content = "<span color=\"%s\" fontSize=\"%d\">%s</span>".formatted(color, fontSize, escapeXml(text));
        if (bold) {
            content = "<strong>" + content + "</strong>";
        }
        return "<content><p textAlign=\"%s\">%s</p></content>".formatted(align, content);
    }

    private String firstMatching(List<String> values, java.util.function.Predicate<String> predicate) {
        if (values == null || predicate == null) {
            return null;
        }
        return values.stream()
                .filter(this::hasText)
                .map(String::trim)
                .filter(predicate)
                .findFirst()
                .orElse(null);
    }

    private String extractByPattern(String text, Pattern pattern, String fallback) {
        if (!hasText(text) || pattern == null) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        return firstNonBlank(matcher.group(1), fallback);
    }

    private String normalizeReportDate(String raw) {
        if (!hasText(raw)) {
            return DEFAULT_REPORT_DATE;
        }
        Matcher matcher = Pattern.compile("(20\\d{2}|\\d{2})[年\\-/\\.\\s]*(\\d{1,2})[月\\-/\\.\\s]*(\\d{1,2})").matcher(raw);
        if (!matcher.find()) {
            return DEFAULT_REPORT_DATE;
        }
        int year = Integer.parseInt(matcher.group(1));
        if (year < 100) {
            year += 2000;
        }
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        return "%04d年%02d月%02d日".formatted(year, month, day);
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

    private String stackedTimelineItems(List<String> points, StyleProfile profile, boolean emphasizeAction) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("明确目标", "梳理方案", "推进落地") : points;
        int count = Math.min(4, safePoints.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int y = 132 + i * 92;
            builder.append("""
                    <shape type="rect" topLeftX="92" topLeftY="%d" width="48" height="48"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="98" topLeftY="%d" width="36" height="34">%s</shape>
                    <shape type="rect" topLeftX="174" topLeftY="%d" width="666" height="56"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                    <shape type="text" topLeftX="204" topLeftY="%d" width="610" height="28">%s</shape>
                    """.formatted(y, profile.accent(), y + 4,
                    plainContent(String.valueOf(i + 1), profile.background(), 16, true, "center"),
                    y - 4, profile.cardFill(), profile.cardBorder(), y + 10,
                    plainContent(safePoints.get(i), profile.text(), emphasizeAction && i == count - 1 ? 20 : 18, emphasizeAction && i == count - 1, "left")));
        }
        return builder.toString().trim();
    }

    private String compactMetricGrid(List<String> points, StyleProfile profile, boolean emphasizeData) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("明确目标", "关键进展", "下一步") : points;
        int count = Math.min(4, safePoints.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = 82 + col * 394;
            int y = 154 + row * 128;
            builder.append("""
                    <shape type="rect" topLeftX="%d" topLeftY="%d" width="372" height="104"><fill><fillColor color="%s"/></fill><border color="%s" width="1"/></shape>
                    <shape type="rect" topLeftX="%d" topLeftY="%d" width="12" height="104"><fill><fillColor color="%s"/></fill></shape>
                    <shape type="text" topLeftX="%d" topLeftY="%d" width="314" height="58">%s</shape>
                    """.formatted(x, y, profile.cardFill(), profile.cardBorder(),
                    x, y, profile.accent(), x + 32, y + 22,
                    plainContent(safePoints.get(i), profile.text(), emphasizeData ? 20 : 18, emphasizeData, "left")));
        }
        return builder.toString().trim();
    }

    private String firstMetric(List<String> points) {
        if (points == null || points.isEmpty()) {
            return "关键结论";
        }
        return points.get(0);
    }

    private List<String> remainingMetrics(List<String> points) {
        if (points == null || points.size() <= 1) {
            return List.of("补充重点", "明确行动");
        }
        return points.subList(1, points.size());
    }

    private String summaryBoard(List<String> points, StyleProfile profile) {
        List<String> safePoints = points == null || points.isEmpty() ? List.of("对齐结论", "明确负责人", "安排下一步") : points;
        List<String> buckets = new ArrayList<>(List.of("结论", "重点", "行动"));
        while (buckets.size() < Math.min(3, safePoints.size())) {
            buckets.add("补充");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(3, safePoints.size()); i++) {
            int x = 74 + i * 280;
            builder.append("""
                    <shape type="text" topLeftX="%d" topLeftY="182" width="250" height="34">%s</shape>
                    <shape type="text" topLeftX="%d" topLeftY="244" width="216" height="118">%s</shape>
                    """.formatted(x + 20,
                    headlineContent(buckets.get(i), profile.text(), 20),
                    x + 20,
                    plainContent(safePoints.get(i), profile.text(), 18, "行动".equals(buckets.get(i)), "left")));
        }
        return builder.toString().trim();
    }

    private boolean isValidSlideXml(String xml) {
        if (xml == null || xml.isBlank() || !xml.contains("<slide") || !xml.contains("<data")) {
            return false;
        }
        if (xml.length() > 16000 || containsOverlongText(xml)) {
            return false;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            return root != null
                    && "slide".equals(root.getLocalName() == null ? root.getNodeName() : root.getLocalName())
                    && root.getElementsByTagNameNS("*", "data").getLength() > 0
                    && root.getElementsByTagNameNS("*", "content").getLength() > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean containsOverlongText(String xml) {
        Matcher matcher = Pattern.compile("(?s)<p>(.*?)</p>").matcher(xml);
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

    private boolean usesImageSlot(PresentationSlidePlan slide) {
        if (slide == null) {
            return false;
        }
        String layout = blankToDefault(slide.getLayout(), "");
        String pageType = blankToDefault(slide.getPageType(), "");
        String emphasis = blankToDefault(slide.getVisualEmphasis(), "");
        return "cover".equalsIgnoreCase(layout)
                || "TRANSITION".equalsIgnoreCase(pageType)
                || "comparison".equalsIgnoreCase(layout)
                || "two-column".equalsIgnoreCase(layout)
                || "balance".equalsIgnoreCase(emphasis)
                || "action".equalsIgnoreCase(emphasis);
    }

    private List<PresentationAssetResources.AssetResource> buildResolvedResources(
            String slideId,
            List<PresentationAssetPlan.AssetTask> tasks,
            String prefix) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<PresentationAssetResources.AssetResource> resources = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            PresentationAssetPlan.AssetTask task = tasks.get(index);
            resources.add(PresentationAssetResources.AssetResource.builder()
                    .assetId(prefix + "-" + blankToDefault(slideId, "slide") + "-" + (index + 1))
                    .sourceRef(blankToDefault(task.getQuery(), prefix + "-" + (index + 1)))
                    .fileToken("boxcn-" + prefix + "-" + blankToDefault(slideId, "slide") + "-" + (index + 1))
                    .purpose(task.getPurpose())
                    .build());
        }
        return resources;
    }

    private PresentationImagePlan invokeImagePlan(String prompt, String taskId) {
        AssistantMessage response = callAgent(imagePlannerAgent, prompt, taskId + ":presentation:image-plan");
        try {
            return objectMapper.readValue(response.getText(), PresentationImagePlan.class);
        } catch (Exception exception) {
            return PresentationImagePlan.builder().pagePlans(List.of()).build();
        }
    }

    private List<PresentationAssetPlan.AssetTask> safeAssetTasks(List<PresentationAssetPlan.AssetTask> tasks) {
        return tasks == null ? List.of() : tasks.stream().filter(Objects::nonNull).limit(2).toList();
    }

    private List<PresentationAssetPlan.DiagramTask> safeDiagramTasks(List<PresentationAssetPlan.DiagramTask> tasks) {
        return tasks == null ? List.of() : tasks.stream().filter(Objects::nonNull).limit(2).toList();
    }

    private List<PresentationAssetResources.SlideAssetResource> toResolvedSlideResources(
            PresentationAssetPlan assetPlan,
            PresentationImageResources imageResources) {
        Map<String, PresentationImageResources.PageImageResource> resourceBySlide = imageResources.getResources() == null ? Map.of() : imageResources.getResources().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(PresentationImageResources.PageImageResource::getSlideId, value -> value, (left, right) -> left, LinkedHashMap::new));
        List<PresentationAssetResources.SlideAssetResource> results = new ArrayList<>();
        if (assetPlan.getSlides() == null) {
            return results;
        }
        for (PresentationAssetPlan.SlideAssetPlan slide : assetPlan.getSlides()) {
            PresentationImageResources.PageImageResource page = resourceBySlide.get(slide.getSlideId());
            results.add(PresentationAssetResources.SlideAssetResource.builder()
                    .slideId(slide.getSlideId())
                    .images(resolveRealAssets(slide.getSlideId(), page == null ? List.of() : page.getContentImages(), "image"))
                    .illustrations(resolveRealAssets(slide.getSlideId(), page == null ? List.of() : page.getIllustrations(), "illustration"))
                    .diagrams(resolveDiagramAssets(slide.getSlideId(), page == null ? List.of() : page.getDiagrams()))
                    .charts(buildResolvedResources(slide.getSlideId(), slide.getChartTasks(), "chart"))
                    .build());
        }
        return results;
    }

    private List<PresentationAssetResources.AssetResource> resolveRealAssets(
            String slideId,
            List<PresentationImageResources.ResourceItem> items,
            String assetType) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<PresentationAssetResources.AssetResource> resources = new ArrayList<>();
        int ordinal = 0;
        for (PresentationImageResources.ResourceItem item : items) {
            List<String> candidateUrls = sanitizeCandidateUrls(item, assetType);

            if (item == null || candidateUrls.isEmpty()) {
                log.warn("presentation asset skipped: slideId={}, assetType={}, sourceUrl={}, reason=no_safe_candidate_url",
                        slideId, assetType, item == null ? null : item.getSourceUrl());
                continue;
            }
            ordinal++;
            String localTempPath = "";
            String selectedUrl = "";
            String mimeType = blankToDefault(item.getMimeType(), "");
            String downloadStatus = "SKIPPED";
            try {
                DownloadedAsset downloaded = downloadAsset(candidateUrls, slideId + "-" + assetType + "-" + ordinal);
                localTempPath = downloaded.path().toString();
                selectedUrl = downloaded.sourceUrl();
                mimeType = blankToDefault(downloaded.mimeType(), mimeType);
                downloadStatus = hasText(localTempPath) ? "DOWNLOADED" : "DOWNLOAD_FAILED";
            } catch (Exception exception) {
                downloadStatus = "FAILED";
            }
            if (!hasText(localTempPath)) {
                continue;
            }
            resources.add(PresentationAssetResources.AssetResource.builder()
                    .assetId(assetType + "-" + slideId + "-" + ordinal)
                    .sourceRef(item.getSourceUrl())
                    .candidateUrls(candidateUrls)
                    .sourceUrl(hasText(selectedUrl) ? selectedUrl : item.getSourceUrl())
                    .sourceSite(item.getSourceSite())
                    .assetType(assetType)
                    .localTempPath(localTempPath)
                    .downloadStatus(downloadStatus)
                    .fileToken("")
                    .purpose(item.getPurpose())
                    .mimeType(mimeType)
                    .fallbackSource(item.getFallbackSource())
                    .build());
        }
        return resources;
    }

    private PresentationAssetResources uploadResolvedAssets(String presentationId, PresentationAssetResources resources) {
        if (!hasText(presentationId) || resources == null || resources.getSlides() == null) {
            log.warn("presentation asset upload skipped: presentationId={}, hasResources={}",
                    presentationId, resources != null && resources.getSlides() != null);
            return resources;
        }
        List<PresentationAssetResources.SlideAssetResource> uploadedSlides = resources.getSlides().stream()
                .filter(Objects::nonNull)
                .map(slide -> PresentationAssetResources.SlideAssetResource.builder()
                        .slideId(slide.getSlideId())
                        .images(uploadAssetList(presentationId, slide.getImages()))
                        .illustrations(uploadAssetList(presentationId, slide.getIllustrations()))
                        .diagrams(slide.getDiagrams() == null ? List.of() : slide.getDiagrams())
                        .charts(slide.getCharts() == null ? List.of() : slide.getCharts())
                        .build())
                .toList();
        return PresentationAssetResources.builder().slides(uploadedSlides).build();
    }

    private List<PresentationAssetResources.AssetResource> uploadAssetList(
            String presentationId,
            List<PresentationAssetResources.AssetResource> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        List<PresentationAssetResources.AssetResource> uploaded = new ArrayList<>();
        for (PresentationAssetResources.AssetResource asset : assets) {
            if (asset == null) {
                continue;
            }
            String fileToken = blankToDefault(asset.getFileToken(), "");
            String downloadStatus = blankToDefault(asset.getDownloadStatus(), "SKIPPED");
            if (hasText(asset.getLocalTempPath())) {
                try {
                    LarkSlidesMediaUploadResult uploadResult = larkSlidesTool.uploadMedia(presentationId, asset.getLocalTempPath());
                    fileToken = blankToDefault(uploadResult.getFileToken(), "");
                    downloadStatus = hasText(fileToken) ? "UPLOADED" : "UPLOAD_FAILED";
                } catch (Exception exception) {
                    downloadStatus = "UPLOAD_FAILED";
                }
            }
            uploaded.add(PresentationAssetResources.AssetResource.builder()
                    .assetId(asset.getAssetId())
                    .sourceRef(asset.getSourceRef())
                    .candidateUrls(asset.getCandidateUrls())
                    .sourceUrl(asset.getSourceUrl())
                    .sourceSite(asset.getSourceSite())
                    .assetType(asset.getAssetType())
                    .localTempPath(asset.getLocalTempPath())
                    .downloadStatus(downloadStatus)
                    .fileToken(fileToken)
                    .purpose(asset.getPurpose())
                    .mimeType(asset.getMimeType())
                    .fallbackSource(asset.getFallbackSource())
                    .build());
        }
        return uploaded;
    }

    private List<PresentationAssetResources.AssetResource> resolveDiagramAssets(
            String slideId,
            List<PresentationImageResources.ResourceItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<PresentationAssetResources.AssetResource> resources = new ArrayList<>();
        int ordinal = 0;
        for (PresentationImageResources.ResourceItem item : items) {
            if (item == null) {
                continue;
            }
            ordinal++;
            resources.add(PresentationAssetResources.AssetResource.builder()
                    .assetId("diagram-" + slideId + "-" + ordinal)
                    .sourceRef(blankToDefault(item.getWhiteboardDsl(), item.getSourceUrl()))
                    .sourceUrl(item.getSourceUrl())
                    .sourceSite(item.getSourceSite())
                    .assetType("diagram")
                    .downloadStatus("PLANNED")
                    .fileToken("")
                    .purpose(item.getPurpose())
                    .build());
        }
        return resources;
    }

    private boolean isSafeImageUrl(String sourceUrl) {
        if (!hasText(sourceUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(sourceUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = blankToDefault(uri.getHost(), "").toLowerCase();
            String path = blankToDefault(uri.getPath(), "").toLowerCase();
            if (!SAFE_IMAGE_DOMAINS.contains(host)) {
                return false;
            }
            if (path.contains("/api/") || path.contains("/search/")) {
                return false;
            }
            String lowerUrl = sourceUrl.trim().toLowerCase();
            return !lowerUrl.endsWith(".html") && !lowerUrl.contains("search?");
        } catch (Exception exception) {
            return false;
        }
    }

    private DownloadedAsset downloadAsset(List<String> candidateUrls, String fileNamePrefix) throws Exception {
        IllegalStateException lastError = null;
        for (String sourceUrl : candidateUrls) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().length == 0) {
                lastError = new IllegalStateException("Failed to download asset");
                continue;
            }
            if (!isImageContentType(contentType)) {
                lastError = new IllegalStateException("Unsupported content type: " + contentType);
                continue;
            }
            String extension = guessExtension(sourceUrl, contentType);
            Files.createDirectories(assetWorkspaceDirectory);
            Path file = Files.createTempFile(assetWorkspaceDirectory, "ppt-image-" + fileNamePrefix + "-", extension);
            Files.write(file, response.body());
            return new DownloadedAsset(sourceUrl, file, contentType);
        }
        throw lastError == null ? new IllegalStateException("Failed to download asset") : lastError;
    }

    private String guessExtension(String sourceUrl, String contentType) {
        String lowerUrl = blankToDefault(sourceUrl, "").toLowerCase();
        if (lowerUrl.endsWith(".svg") || contentType.contains("svg")) {
            return ".svg";
        }
        if (lowerUrl.endsWith(".png") || contentType.contains("png")) {
            return ".png";
        }
        if (lowerUrl.endsWith(".webp") || contentType.contains("webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private boolean isImageContentType(String contentType) {
        String lower = blankToDefault(contentType, "").toLowerCase();
        return lower.startsWith("image/") || lower.contains("svg+xml");
    }

    private PresentationImageResources resolveImageResources(PresentationAssetPlan assetPlan) {
        if (assetPlan == null || assetPlan.getSlides() == null) {
            return PresentationImageResources.builder().resources(List.of()).build();
        }

        List<PresentationImageResources.PageImageResource> pages = assetPlan.getSlides().stream()
                .filter(Objects::nonNull)
                .map(slide -> PresentationImageResources.PageImageResource.builder()
                        .slideId(slide.getSlideId())
                        .contentImages(resolveTaskResources(slide.getContentImageTasks(), "image"))
                        .illustrations(resolveIllustrationResources(slide.getIllustrationTasks()))
                        .diagrams(resolveDiagramResourceItems(slide.getDiagramTasks()))
                        .build())
                .toList();
        return PresentationImageResources.builder().resources(reuseCoverImageForThanksPage(pages)).build();
    }

    private List<PresentationImageResources.PageImageResource> reuseCoverImageForThanksPage(
            List<PresentationImageResources.PageImageResource> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        PresentationImageResources.PageImageResource cover = pages.stream()
                .filter(Objects::nonNull)
                .filter(page -> "slide-1".equals(page.getSlideId()))
                .findFirst()
                .orElse(null);
        if (cover == null || cover.getContentImages() == null || cover.getContentImages().isEmpty()) {
            return pages;
        }
        return pages.stream()
                .map(page -> {
                    if (page == null || !("slide-" + pages.size()).equals(page.getSlideId())) {
                        return page;
                    }
                    if (page.getContentImages() != null && !page.getContentImages().isEmpty()) {
                        return page;
                    }
                    return PresentationImageResources.PageImageResource.builder()
                            .slideId(page.getSlideId())
                            .contentImages(cover.getContentImages())
                            .illustrations(page.getIllustrations())
                            .diagrams(page.getDiagrams())
                            .build();
                })
                .toList();
    }

    private List<PresentationImageResources.ResourceItem> resolveTaskResources(
            List<PresentationAssetPlan.AssetTask> tasks,
            String assetType) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<PresentationImageResources.ResourceItem> results = new ArrayList<>();
        for (PresentationAssetPlan.AssetTask task : tasks) {
            if (task == null || !hasText(task.getQuery())) {
                continue;
            }
            List<String> candidates = searchPexelsCandidates(task.getQuery());

            results.add(PresentationImageResources.ResourceItem.builder()
                    .candidateUrls(candidates)
                    .selectedUrl(candidates.isEmpty() ? "" : candidates.get(0))
                    .sourceUrl(candidates.isEmpty() ? "" : candidates.get(0))
                    .sourceSite("pexels.com")
                    .assetType(assetType)
                    .purpose(task.getPurpose())
                    .mimeType("")
                    .fallbackSource("")
                    .build());
        }
        return results;
    }

    private List<PresentationImageResources.ResourceItem> resolveIllustrationResources(
            List<PresentationAssetPlan.AssetTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<PresentationImageResources.ResourceItem> results = new ArrayList<>();
        for (PresentationAssetPlan.AssetTask task : tasks) {
            if (task == null || !hasText(task.getQuery())) {
                continue;
            }
            List<String> svgCandidates = resolveDirectSvgCandidates(task);
            if (!svgCandidates.isEmpty()) {

                results.add(PresentationImageResources.ResourceItem.builder()
                        .candidateUrls(svgCandidates)
                        .selectedUrl(svgCandidates.get(0))
                        .sourceUrl(svgCandidates.get(0))
                        .sourceSite(extractSourceSite(svgCandidates.get(0)))
                        .assetType("illustration")
                        .purpose(task.getPurpose())
                        .mimeType("image/svg+xml")
                        .fallbackSource("")
                        .build());
                continue;
            }
            List<String> fallbackCandidates = searchPexelsCandidates(task.getQuery());

            results.add(PresentationImageResources.ResourceItem.builder()
                    .candidateUrls(fallbackCandidates)
                    .selectedUrl(fallbackCandidates.isEmpty() ? "" : fallbackCandidates.get(0))
                    .sourceUrl(fallbackCandidates.isEmpty() ? "" : fallbackCandidates.get(0))
                    .sourceSite("pexels.com")
                    .assetType("illustration")
                    .purpose(task.getPurpose())
                    .mimeType("")
                    .fallbackSource("pexels-image-fallback")
                    .build());
        }
        return results;
    }

    private List<PresentationImageResources.ResourceItem> resolveDiagramResourceItems(
            List<PresentationAssetPlan.DiagramTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .filter(Objects::nonNull)
                .map(task -> PresentationImageResources.ResourceItem.builder()
                        .sourceSite("mermaid")
                        .assetType("diagram")
                        .purpose(task.getPurpose())
                        .whiteboardDsl(blankToDefault(task.getMermaidCode(), ""))
                        .build())
                .toList();
    }

    private List<String> sanitizeCandidateUrls(PresentationImageResources.ResourceItem item, String assetType) {
        if (item == null) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        if (item.getCandidateUrls() != null) {
            item.getCandidateUrls().stream().filter(this::isSafeImageUrl).forEach(candidates::add);
        }
        if (hasText(item.getSelectedUrl()) && isSafeImageUrl(item.getSelectedUrl()) && !candidates.contains(item.getSelectedUrl())) {
            candidates.add(item.getSelectedUrl());
        }
        if (hasText(item.getSourceUrl()) && isSafeImageUrl(item.getSourceUrl()) && !candidates.contains(item.getSourceUrl())) {
            candidates.add(item.getSourceUrl());
        }
        if ("illustration".equalsIgnoreCase(assetType)) {
            return candidates.stream().filter(this::isDirectVisualAssetUrl).toList();
        }
        return candidates;
    }

    private boolean isDirectVisualAssetUrl(String url) {
        String lower = blankToDefault(url, "").toLowerCase();
        return lower.endsWith(".svg")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.contains("images.pexels.com")
                || lower.contains("images.unsplash.com")
                || lower.contains("cdn.pixabay.com");
    }

    private List<String> searchPexelsCandidates(String query) {
        if (!hasText(pexelsApiKey) || !hasText(query)) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PEXELS_SEARCH_API + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Authorization", pexelsApiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !hasText(response.body())) {

                return List.of();
            }
            PexelsSearchResponse payload = objectMapper.readValue(response.body(), PexelsSearchResponse.class);
            if (payload.getPhotos() == null) {

                return List.of();
            }
            List<String> candidates = new ArrayList<>();
            for (PexelsSearchResponse.PexelsPhoto photo : payload.getPhotos()) {
                if (photo == null || photo.getSrc() == null) {
                    continue;
                }
                addIfSafeCandidate(candidates, photo.getSrc().getOriginal());
                addIfSafeCandidate(candidates, photo.getSrc().getLarge2x());
                addIfSafeCandidate(candidates, photo.getSrc().getLarge());
                addIfSafeCandidate(candidates, photo.getSrc().getMedium());
                if (candidates.size() >= 6) {
                    break;
                }
            }

            return candidates;
        } catch (Exception exception) {

            return List.of();
        }
    }

    private void addIfSafeCandidate(List<String> candidates, String url) {
        if (hasText(url) && isSafeImageUrl(url) && isDirectVisualAssetUrl(url) && !candidates.contains(url)) {
            candidates.add(url);
        }
    }

    private List<String> resolveDirectSvgCandidates(PresentationAssetPlan.AssetTask task) {
        if (task == null || task.getPreferredDomains() == null) {
            return List.of();
        }
        return task.getPreferredDomains().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(domain -> domain.equals("svgrepo.com") || domain.equals("storyset.com") || domain.equals("manypixels.co"))
                .map(domain -> "")
                .filter(this::hasText)
                .toList();
    }

    private String extractSourceSite(String sourceUrl) {
        try {
            return blankToDefault(URI.create(sourceUrl).getHost(), "");
        } catch (Exception exception) {
            return "";
        }
    }

    record DownloadedAsset(String sourceUrl, Path path, String mimeType) { }

    private PresentationSlideIR buildSlideIr(
            PresentationSlidePlan slide,
            PresentationGenerationOptions options,
            PresentationAssetResources resources) {
        List<PresentationElementIR> elements = new ArrayList<>();
        elements.add(PresentationElementIR.builder()
                .elementId(slide.getSlideId() + "-title")
                .elementKind(PresentationElementKind.TITLE)
                .targetElementType(PresentationTargetElementType.TITLE)
                .semanticRole("title")
                .textType("title")
                .textContent(slide.getTitle())
                .layoutBox(PresentationLayoutSpec.builder()
                        .topLeftX(64)
                        .topLeftY(48)
                        .width(820)
                        .height(88)
                        .templateVariant(slide.getTemplateVariant())
                        .build())
                .editability(PresentationEditability.NATIVE_EDITABLE)
                .build());
        elements.add(PresentationElementIR.builder()
                .elementId(slide.getSlideId() + "-body")
                .elementKind(PresentationElementKind.BODY)
                .targetElementType(PresentationTargetElementType.BODY)
                .semanticRole("body")
                .textType("body")
                .textContent(slide.getKeyPoints() == null ? "" : String.join("；", slide.getKeyPoints()))
                .layoutBox(PresentationLayoutSpec.builder()
                        .topLeftX(80)
                        .topLeftY(160)
                        .width(760)
                        .height(220)
                        .templateVariant(slide.getTemplateVariant())
                        .build())
                .editability(PresentationEditability.NATIVE_EDITABLE)
                .build());
        resolveSlideImage(slide, resources).ifPresent(image -> elements.add(PresentationElementIR.builder()
                .elementId(slide.getSlideId() + "-image")
                .elementKind(PresentationElementKind.IMAGE)
                .targetElementType(PresentationTargetElementType.IMAGE)
                .semanticRole("hero-image")
                .layoutBox(PresentationLayoutSpec.builder()
                        .topLeftX(560)
                        .topLeftY(90)
                        .width(320)
                        .height(180)
                        .templateVariant(slide.getTemplateVariant())
                        .build())
                .assetRef(PresentationAssetRef.builder()
                        .assetId(image.getAssetId())
                        .fileToken(image.getFileToken())
                        .sourceRef(hasText(image.getLocalTempPath()) ? toSlidesLocalPath(image.getLocalTempPath()) : image.getSourceRef())
                        .sourceType(hasText(image.getLocalTempPath()) ? "local-placeholder" : "resolved")
                        .elementKind(PresentationElementKind.IMAGE)
                        .editability(PresentationEditability.HYBRID_EDITABLE)
                        .altText(image.getPurpose())
                        .caption(image.getPurpose())
                        .build())
                .editability(PresentationEditability.HYBRID_EDITABLE)
                .build()));
        log.info("slide ir built: slideId={}, layout={}, templateVariant={}, imageSelected={}, keyPointCount={}",
                slide.getSlideId(),
                slide.getLayout(),
                slide.getTemplateVariant(),
                elements.stream().anyMatch(element -> element.getElementKind() == PresentationElementKind.IMAGE),
                slide.getKeyPoints() == null ? 0 : slide.getKeyPoints().size());
        resolveSlideImage(slide, resources);
        return PresentationSlideIR.builder()
                .slideId(slide.getSlideId())
                .pageIndex(slide.getIndex())
                .slideRole(blankToDefault(slide.getLayout(), "content"))
                .pageType(slide.getPageType())
                .pageSubType(slide.getPageSubType())
                .sectionId(slide.getSectionId())
                .sectionTitle(slide.getSectionTitle())
                .sectionOrder(slide.getSectionOrder())
                .title(slide.getTitle())
                .message(slide.getSpeakerNotes())
                .visualIntent(slide.getVisualEmphasis())
                .editability(usesImageSlot(slide) ? PresentationEditability.HYBRID_EDITABLE : PresentationEditability.NATIVE_EDITABLE)
                .elements(elements)
                .build();
    }

    private java.util.Optional<PresentationAssetResources.AssetResource> resolveFirstImage(
            String slideId,
            PresentationAssetResources resources) {
        if (resources == null || resources.getSlides() == null) {
            return java.util.Optional.empty();
        }
        return resources.getSlides().stream()
                .filter(item -> Objects.equals(item.getSlideId(), slideId))
                .findFirst()
                .flatMap(item -> {
                    if (item.getImages() != null && !item.getImages().isEmpty()) {
                        return java.util.Optional.of(item.getImages().get(0));
                    }
                    if (item.getIllustrations() != null && !item.getIllustrations().isEmpty()) {
                        return java.util.Optional.of(item.getIllustrations().get(0));
                    }
                    return java.util.Optional.empty();
                });
    }

    private java.util.Optional<PresentationAssetResources.AssetResource> resolveSlideImage(
            PresentationSlidePlan slide,
            PresentationAssetResources resources) {
        if (slide == null) {
            return java.util.Optional.empty();
        }
        java.util.Optional<PresentationAssetResources.AssetResource> direct = resolveFirstImage(slide.getSlideId(), resources);
        if (direct.isPresent()) {
            return direct;
        }
        if ("TRANSITION".equalsIgnoreCase(blankToDefault(slide.getPageType(), ""))) {
            return resolveFirstImage("slide-1", resources);
        }
        if ("THANKS".equalsIgnoreCase(blankToDefault(slide.getPageType(), ""))) {
            return resolveFirstImage("slide-1", resources);
        }
        return java.util.Optional.empty();
    }

    private String compileSlideXml(PresentationSlideIR slideIr, int totalSlides, PresentationGenerationOptions options) {
        if (slideIr == null) {
            return "";
        }
        PresentationSlidePlan plan = PresentationSlidePlan.builder()
                .slideId(slideIr.getSlideId())
                .index(slideIr.getPageIndex() == null ? 1 : slideIr.getPageIndex())
                .title(slideIr.getTitle())
                .layout(slideIr.getSlideRole())
                .pageType(blankToDefault(slideIr.getPageType(), defaultPageType(slideIr.getSlideRole(), slideIr.getPageIndex() == null ? 1 : slideIr.getPageIndex(), totalSlides)))
                .pageSubType(blankToDefault(slideIr.getPageSubType(), defaultPageSubType(blankToDefault(slideIr.getPageType(), defaultPageType(slideIr.getSlideRole(), slideIr.getPageIndex() == null ? 1 : slideIr.getPageIndex(), totalSlides)))))
                .sectionId(slideIr.getSectionId())
                .sectionTitle(slideIr.getSectionTitle())
                .sectionOrder(slideIr.getSectionOrder())
                .templateVariant(resolveTemplateVariant(slideIr))
                .visualEmphasis(blankToDefault(slideIr.getVisualIntent(), "balance"))
                .keyPoints(extractBodyPoints(slideIr))
                .speakerNotes(slideIr.getMessage())
                .build();
        String baseXml = buildSlideXmlTemplate(plan, plan.getIndex(), totalSlides, options);
        if (!containsImage(slideIr)) {
            return baseXml;
        }
        PresentationElementIR image = slideIr.getElements().stream()
                .filter(element -> element.getElementKind() == PresentationElementKind.IMAGE)
                .findFirst()
                .orElse(null);
        if (image == null || image.getAssetRef() == null) {
            log.info("slide xml compile without image: slideId={}, reason=no_asset_ref", slideIr.getSlideId());
            return baseXml;
        }
        String src = hasText(image.getAssetRef().getFileToken())
                ? image.getAssetRef().getFileToken()
                : blankToDefault(image.getAssetRef().getSourceRef(), "");
        if (!hasText(src)) {
            log.info("slide xml compile without image: slideId={}, reason=no_image_src", slideIr.getSlideId());
            return baseXml;
        }
        String imgXml = """
                <img src="%s" topLeftX="%d" topLeftY="%d" width="%d" height="%d" alpha="1" alt="%s">
                  <border color="rgba(0,0,0,0.08)" width="1"/>
                </img>
                """.formatted(
                src,
                valueOrDefault(image.getLayoutBox() == null ? null : image.getLayoutBox().getTopLeftX(), 560),
                valueOrDefault(image.getLayoutBox() == null ? null : image.getLayoutBox().getTopLeftY(), 90),
                valueOrDefault(image.getLayoutBox() == null ? null : image.getLayoutBox().getWidth(), 320),
                valueOrDefault(image.getLayoutBox() == null ? null : image.getLayoutBox().getHeight(), 180),
                escapeXml(blankToDefault(image.getAssetRef().getAltText(), "配图")));
        log.info("slide xml compile with image: slideId={}, imageSrc={}, assetId={}",
                slideIr.getSlideId(),
                src,
                image.getAssetRef().getAssetId());
        String layout = normalizeLayout(plan.getLayout(), plan.getIndex(), totalSlides);
        if ("cover".equals(layout)) {
            return baseXml.replace("<data>",
                    "<data>\n" + """
                    <img src="%s" topLeftX="0" topLeftY="0" width="960" height="540" alpha="1" alt="%s"/>
                    """.formatted(src, escapeXml(blankToDefault(image.getAssetRef().getAltText(), "封面图"))));
        }
        if ("TRANSITION".equalsIgnoreCase(blankToDefault(plan.getPageType(), ""))) {
            return baseXml.replace("<data>",
                    "<data>\n" + """
                    <img src="%s" topLeftX="0" topLeftY="0" width="960" height="540" alpha="1" alt="%s"/>
                    """.formatted(src, escapeXml(blankToDefault(image.getAssetRef().getAltText(), "章节过渡图"))));
        }
        if ("THANKS".equalsIgnoreCase(blankToDefault(plan.getPageType(), ""))) {
            return baseXml.replace("<data>",
                    "<data>\n" + """
                    <img src="%s" topLeftX="522" topLeftY="74" width="366" height="392" alpha="1" alt="%s">
                      <border color="rgba(255,255,255,0.55)" width="1"/>
                    </img>
                    """.formatted(src, escapeXml(blankToDefault(image.getAssetRef().getAltText(), "结束页配图"))));
        }
        if ("two-column".equals(layout) || "comparison".equals(layout)) {
            return baseXml.replace("</data>", """
                    <img src="%s" topLeftX="510" topLeftY="152" width="330" height="270" alpha="1" alt="%s">
                      <border color="rgba(0,0,0,0.08)" width="1"/>
                    </img>
                    </data>
                    """.formatted(src, escapeXml(blankToDefault(image.getAssetRef().getAltText(), "配图"))));
        }
        return baseXml;
    }

    private String toSlidesLocalPath(String localTempPath) {
        if (!hasText(localTempPath)) {
            return "";
        }
        Path localPath = Path.of(localTempPath).toAbsolutePath().normalize();
        Path workingPath = Path.of("").toAbsolutePath().normalize();
        if (!localPath.startsWith(workingPath)) {
            return "";
        }
        return "@." + java.io.File.separator + workingPath.relativize(localPath).toString();
    }


    private boolean containsImage(PresentationSlideIR slideIr) {
        return slideIr.getElements() != null && slideIr.getElements().stream()
                .anyMatch(element -> element.getElementKind() == PresentationElementKind.IMAGE);
    }

    private List<String> extractBodyPoints(PresentationSlideIR slideIr) {
        if (slideIr.getElements() == null) {
            return List.of();
        }
        return slideIr.getElements().stream()
                .filter(element -> element.getElementKind() == PresentationElementKind.BODY)
                .findFirst()
                .map(PresentationElementIR::getTextContent)
                .map(text -> List.of(text.split("[；;\\n]+")))
                .orElse(List.of());
    }

    private String resolveTemplateVariant(PresentationSlideIR slideIr) {
        if (slideIr.getElements() == null || slideIr.getElements().isEmpty()) {
            return "dual-cards";
        }
        PresentationLayoutSpec box = slideIr.getElements().get(0).getLayoutBox();
        return box == null ? "dual-cards" : blankToDefault(box.getTemplateVariant(), "dual-cards");
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
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

    private record PresentationCoverMeta(
            String presenter,
            String reportDate
    ) {
    }
}
