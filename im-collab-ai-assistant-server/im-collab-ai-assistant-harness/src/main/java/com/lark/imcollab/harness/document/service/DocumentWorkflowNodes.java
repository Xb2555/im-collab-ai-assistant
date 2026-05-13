package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.common.model.entity.DocumentCompletenessReport;
import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentSectionCompleteness;
import com.lark.imcollab.common.model.entity.DiagramRequirement;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentPlanSection;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.template.DocumentBodyNormalizer;
import com.lark.imcollab.harness.document.template.DocumentTemplateRenderer;
import com.lark.imcollab.harness.document.template.DocumentTemplateStrategyResolver;
import com.lark.imcollab.harness.document.template.DocumentTemplateType;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.skills.lark.doc.LarkDocCreateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DocumentWorkflowNodes {

    private static final String[] CHINESE_SECTION_NUMBERS = {
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
    };
    private static final Set<String> SUPPORTED_MERMAID_HEADERS = Set.of(
            "flowchart",
            "graph",
            "sequencediagram",
            "statediagram",
            "statediagram-v2",
            "erdiagram"
    );
    private static final Pattern BODY_HEADING_PATTERN = Pattern.compile("(?m)^#+\\s*");
    private static final Pattern BODY_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final DocumentExecutionSupport support;
    private final DocumentBodyNormalizer bodyNormalizer;
    private final DocumentTemplateRenderer templateRenderer;
    private final DocumentTemplateStrategyResolver templateStrategyResolver;
    private final ReactAgent documentOutlineAgent;
    private final ReactAgent documentSectionAgent;
    private final ReactAgent documentDiagramAgent;
    private final ReactAgent documentReviewAgent;
    private final LarkDocTool larkDocTool;
    private final ObjectMapper objectMapper;

    public DocumentWorkflowNodes(
            DocumentExecutionSupport support,
            DocumentBodyNormalizer bodyNormalizer,
            DocumentTemplateRenderer templateRenderer,
            DocumentTemplateStrategyResolver templateStrategyResolver,
            @Qualifier("documentOutlineAgent") ReactAgent documentOutlineAgent,
            @Qualifier("documentSectionAgent") ReactAgent documentSectionAgent,
            @Qualifier("documentDiagramAgent") ReactAgent documentDiagramAgent,
            @Qualifier("documentReviewAgent") ReactAgent documentReviewAgent,
            LarkDocTool larkDocTool,
            ObjectMapper objectMapper) {
        this.support = support;
        this.bodyNormalizer = bodyNormalizer;
        this.templateRenderer = templateRenderer;
        this.templateStrategyResolver = templateStrategyResolver;
        this.documentOutlineAgent = documentOutlineAgent;
        this.documentSectionAgent = documentSectionAgent;
        this.documentDiagramAgent = documentDiagramAgent;
        this.documentReviewAgent = documentReviewAgent;
        this.larkDocTool = larkDocTool;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Map<String, Object>> dispatchDocTask(OverAllState state, RunnableConfig config) {
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        var task = support.loadTask(taskId);
        support.publishEvent(taskId, null, TaskEventType.STEP_STARTED);
        String templateStrategy = state.value(DocumentStateKeys.TEMPLATE_STRATEGY, "");
        if ((templateStrategy == null || templateStrategy.isBlank()) && task.getExecutionContract() != null) {
            templateStrategy = task.getExecutionContract().getTemplateStrategy();
        }
        var templateType = templateStrategyResolver.resolve(task.getExecutionContract());
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.TEMPLATE_STRATEGY, templateStrategy == null ? "" : templateStrategy,
                DocumentStateKeys.TEMPLATE_TYPE, templateType.name(),
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false
        ));
    }

    public CompletableFuture<Map<String, Object>> generateOutline(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_OUTLINE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String userFeedback = state.value(DocumentStateKeys.USER_FEEDBACK, "");
        String rawInstruction = state.value(DocumentStateKeys.RAW_INSTRUCTION, "");
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, rawInstruction);
        String prompt = "生成文档大纲。"
                + (clarifiedInstruction.isBlank() ? "任务ID：" + taskId : "任务要求：" + clarifiedInstruction)
                + buildExecutionContext(state)
                + (userFeedback.isBlank() ? "" : "\n用户反馈：" + userFeedback);
        DocumentOutline outline = invokeOutline(prompt, taskId);
        DocumentPlan plan = buildDocumentPlan(taskId, outline, state);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX),
                ArtifactType.DOC_OUTLINE, outline.getTitle(), support.writeJson(outline), null);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.OUTLINE, outline,
                DocumentStateKeys.DOCUMENT_PLAN, plan,
                DocumentStateKeys.DONE_OUTLINE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> generateSections(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_SECTIONS, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, state.value(DocumentStateKeys.RAW_INSTRUCTION, ""));
        DocumentPlan plan = requireValue(state, DocumentStateKeys.DOCUMENT_PLAN, DocumentPlan.class);
        List<DocumentSectionDraft> drafts = new ArrayList<>();
        String summaryOnlyStyle = isSummaryOnlyRequest(state)
                ? "\n输出样式额外要求：这是要直接发到 IM 聊天框的 SUMMARY 纯文本产物。body 必须是自然中文文本，不要使用 Markdown 语法，不要使用 #/##/### 标题、- 或 * 列表符号、**加粗**、代码块、表格。需要分点时用自然句式，例如“一是...；二是...；三是...”。"
                : "";
        for (DocumentPlanSection section : safePlanSections(plan)) {
            String prompt = "任务目标：" + clarifiedInstruction
                    + buildExecutionContext(state)
                    + "\n文档标题：" + plan.getTitle()
                    + "\n全局写作约束：\n" + buildPlanContract(plan)
                    + "\n已完成章节摘要：\n" + summarizeDrafts(drafts)
                    + "\n章节：" + section.getHeading()
                    + "\n章节职责：" + defaultString(section.getPurpose())
                    + "\n要点：" + String.join("；", safeList(section.getMustCover()))
                    + summaryOnlyStyle;
            support.ensureExecutionCanContinue(taskId);
            AssistantMessage response = callAgent(documentSectionAgent, prompt, taskId + ":section:" + section.getSectionId());
            support.ensureExecutionCanContinue(taskId);
            drafts.add(parseSectionDraft(response.getText(), section));
        }
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.SECTION_DRAFTS, drafts,
                DocumentStateKeys.DONE_SECTIONS, true
        ));
    }

    public CompletableFuture<Map<String, Object>> planDiagrams(OverAllState state, RunnableConfig config) {
        DiagramRequirement requirement = readDiagramRequirement(state);
        if (requirement == null || !requirement.isRequired()) {
            return CompletableFuture.completedFuture(Map.of(DocumentStateKeys.DIAGRAM_PLAN, ""));
        }
        String diagramType = requirement.getTypes() == null || requirement.getTypes().isEmpty()
                ? "DATA_FLOW"
                : requirement.getTypes().get(0);
        return CompletableFuture.completedFuture(Map.of(DocumentStateKeys.DIAGRAM_PLAN, diagramType));
    }

    public CompletableFuture<Map<String, Object>> generateMermaid(OverAllState state, RunnableConfig config) {
        String diagramPlan = state.value(DocumentStateKeys.DIAGRAM_PLAN, "");
        if (diagramPlan == null || diagramPlan.isBlank()) {
            return CompletableFuture.completedFuture(Map.of(DocumentStateKeys.MERMAID_DIAGRAM, ""));
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, state.value(DocumentStateKeys.RAW_INSTRUCTION, ""));
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        String prompt = buildDiagramPrompt(diagramPlan, clarifiedInstruction, outline, state);
        AssistantMessage response = callAgent(documentDiagramAgent, prompt, taskId + ":diagram:" + diagramPlan.toLowerCase());
        support.ensureExecutionCanContinue(taskId);
        String mermaid = coerceMermaid(response.getText(), diagramPlan);
        support.saveArtifact(taskId, taskId + ":document:diagram", ArtifactType.DIAGRAM_SOURCE, outline.getTitle() + " 图表", mermaid, null);
        return CompletableFuture.completedFuture(Map.of(DocumentStateKeys.MERMAID_DIAGRAM, mermaid));
    }

    public CompletableFuture<Map<String, Object>> validateMermaid(OverAllState state, RunnableConfig config) {
        String mermaid = state.value(DocumentStateKeys.MERMAID_DIAGRAM, "");
        DiagramRequirement requirement = readDiagramRequirement(state);
        if ((requirement == null || !requirement.isRequired()) && (mermaid == null || mermaid.isBlank())) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (mermaid == null || mermaid.isBlank()) {
            throw new IllegalStateException("Mermaid diagram required but not generated");
        }
        String diagramPlan = state.value(DocumentStateKeys.DIAGRAM_PLAN, "DATA_FLOW");
        if (!isMermaidValid(mermaid, diagramPlan)) {
            throw new IllegalStateException("Generated Mermaid diagram failed validation");
        }
        return CompletableFuture.completedFuture(Map.of(DocumentStateKeys.MERMAID_DIAGRAM, mermaid));
    }

    public CompletableFuture<Map<String, Object>> reviewDoc(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
        String userFeedback = state.value(DocumentStateKeys.USER_FEEDBACK, "");
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, state.value(DocumentStateKeys.RAW_INSTRUCTION, ""));
        String rawInstruction = state.value(DocumentStateKeys.RAW_INSTRUCTION, clarifiedInstruction);
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = readSectionDrafts(state);
        String mermaid = state.value(DocumentStateKeys.MERMAID_DIAGRAM, "");
        DocumentReviewResult reviewResult = evaluateReview(taskId, rawInstruction, clarifiedInstruction, outline, drafts, mermaid, userFeedback, state, "");

        DiagramRequirement requirement = readDiagramRequirement(state);
        if (requirement != null && requirement.isRequired() && (mermaid == null || mermaid.isBlank())) {
            reviewResult.setMissingItems(appendMissingItem(reviewResult.getMissingItems(), "缺少必需的 Mermaid 图"));
        }

        ReviewResolution resolution = resolveReviewWithAutoSupplement(
                taskId,
                rawInstruction,
                clarifiedInstruction,
                outline,
                drafts,
                mermaid,
                userFeedback,
                state,
                requirement,
                reviewResult
        );
        reviewResult = resolution.reviewResult();
        List<DocumentSectionDraft> effectiveDrafts = resolution.effectiveDrafts();

        boolean needsHumanReview = reviewResult.getMissingItems() != null && !reviewResult.getMissingItems().isEmpty();
        if (needsHumanReview && userFeedback.isBlank()) {
            support.publishApprovalRequest(taskId, null, "文档审核发现问题：" + String.join("；", reviewResult.getMissingItems()));
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.REVIEW_RESULT, reviewResult,
                    DocumentStateKeys.SECTION_DRAFTS, effectiveDrafts
            ));
        }
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.REVIEW_RESULT, reviewResult,
                DocumentStateKeys.DONE_REVIEW, true,
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false,
                DocumentStateKeys.SECTION_DRAFTS, effectiveDrafts
        ));
    }

    private ReviewResolution resolveReviewWithAutoSupplement(
            String taskId,
            String rawInstruction,
            String clarifiedInstruction,
            DocumentOutline outline,
            List<DocumentSectionDraft> drafts,
            String mermaid,
            String userFeedback,
            OverAllState state,
            DiagramRequirement requirement,
            DocumentReviewResult initialReview
    ) {
        DocumentReviewResult reviewResult = initialReview;
        List<DocumentSectionDraft> effectiveDrafts = drafts;
        String supplementRetryPrefix = null;
        if (shouldRetryForSupplementalSections(reviewResult, userFeedback)) {
            DocumentReviewResult supplementRetry = evaluateReview(
                    taskId,
                    rawInstruction,
                    clarifiedInstruction,
                    outline,
                    drafts,
                    mermaid,
                    userFeedback,
                    state,
                    "\n补充说明：上一轮审阅只列出了缺项，没有给出可直接合并的补全文字。"
                            + "请优先把能够根据当前任务目标、已拉取材料和现有章节直接补齐的问题写入 supplementalSections，"
                            + "只把确实无法自动补齐的问题保留在 missingItems。"
                            + "\n上一轮缺项：" + String.join("；", safeList(reviewResult == null ? null : reviewResult.getMissingItems()))
            );
            if (requirement != null && requirement.isRequired() && (mermaid == null || mermaid.isBlank())) {
                supplementRetry.setMissingItems(appendMissingItem(supplementRetry.getMissingItems(), "缺少必需的 Mermaid 图"));
            }
            if (hasSupplementalSections(supplementRetry)) {
                reviewResult = supplementRetry;
                supplementRetryPrefix = "系统检测到审阅结果只列出缺项，已触发自动补齐重试。";
            }
        }
        if (hasSupplementalSections(reviewResult)) {
            effectiveDrafts = mergeSupplementalSections(drafts, reviewResult.getSupplementalSections());
            DocumentReviewResult secondPass = evaluateReview(
                    taskId,
                    rawInstruction,
                    clarifiedInstruction,
                    outline,
                    effectiveDrafts,
                    mermaid,
                    userFeedback,
                    state,
                    "\n补充说明：上一轮审阅已自动补齐缺失章节，请基于补齐后的完整文档重新审阅，只保留仍无法自动解决的问题。"
            );
            if (requirement != null && requirement.isRequired() && (mermaid == null || mermaid.isBlank())) {
                secondPass.setMissingItems(appendMissingItem(secondPass.getMissingItems(), "缺少必需的 Mermaid 图"));
            }
            secondPass.setSupplementalSections(List.of());
            secondPass.setSummary(joinSummary(
                    reviewResult.getSummary(),
                    supplementRetryPrefix,
                    "系统已执行自动补充并完成二次复审，最终是否补齐以发布前完整性校验为准。",
                    secondPass.getSummary()
            ));
            reviewResult = secondPass;
        }
        return new ReviewResolution(reviewResult, effectiveDrafts);
    }

    public CompletableFuture<Map<String, Object>> writeDocAndSync(OverAllState state, RunnableConfig config) {
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        try (ExecutionAttemptContext.Scope ignored = bindExecutionAttempt(state, taskId)) {
            return doWriteDocAndSync(state, taskId);
        }
    }

    private CompletableFuture<Map<String, Object>> doWriteDocAndSync(OverAllState state, String taskId) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        support.ensureExecutionCanContinue(taskId);
        DocumentPlan plan = requireValue(state, DocumentStateKeys.DOCUMENT_PLAN, DocumentPlan.class);
        ComposedDocumentDraft composedDraft = requireValue(state, DocumentStateKeys.COMPOSED_DRAFT, ComposedDocumentDraft.class);
        DocumentReviewResult reviewResult = requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class);
        if (isSummaryOnlyRequest(state)) {
            saveSummaryArtifact(taskId, plan, composedDraft, reviewResult, true);
            support.publishEvent(taskId, null, TaskEventType.ARTIFACT_CREATED);
            support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.DOC_MARKDOWN, defaultString(composedDraft.getComposedMarkdown()),
                    DocumentStateKeys.DOC_URL, "",
                    DocumentStateKeys.DOC_ID, "",
                    DocumentStateKeys.DONE_WRITE, true
            ));
        }
        String markdown = templateRenderer.render(
                DocumentTemplateType.valueOf(state.value(DocumentStateKeys.TEMPLATE_TYPE, "REPORT")),
                plan,
                composedDraft,
                reviewResult,
                state.value(DocumentStateKeys.USER_FEEDBACK, "")
        );
        log.info("DOC_WRITE markdown_ready taskId={} title='{}' markdownLength={} containsMermaidFence={} mermaidFenceCount={} preview='{}'",
                taskId,
                plan.getTitle(),
                markdown == null ? 0 : markdown.length(),
                containsMermaidFenceForLog(markdown),
                countMermaidFencesForLog(markdown),
                previewMarkdownForLog(markdown));
        composedDraft.setComposedMarkdown(markdown);
        ensurePublishable(plan, composedDraft);
        support.ensureExecutionCanContinue(taskId);
        LarkDocCreateResult result = larkDocTool.createDoc(plan.getTitle(), markdown);
        support.ensureExecutionCanContinue(taskId);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.WRITE_TASK_SUFFIX),
                ArtifactType.DOC_LINK, plan.getTitle(), null, result.getDocId(), result.getDocUrl());
        support.publishEvent(taskId, null, TaskEventType.ARTIFACT_CREATED);
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.DOC_MARKDOWN, markdown,
                DocumentStateKeys.DOC_URL, result.getDocUrl(),
                DocumentStateKeys.DOC_ID, result.getDocId(),
                DocumentStateKeys.DONE_WRITE, true
        ));
    }

    private ExecutionAttemptContext.Scope bindExecutionAttempt(OverAllState state, String taskId) {
        String attemptId = state.value(DocumentStateKeys.EXECUTION_ATTEMPT_ID, "");
        return ExecutionAttemptContext.open(taskId, attemptId);
    }

    private void saveSummaryArtifact(
            String taskId,
            DocumentPlan plan,
            ComposedDocumentDraft composedDraft,
            DocumentReviewResult reviewResult,
            boolean summaryOnly
    ) {
        String summary = summaryOnly
                ? buildPureSummaryArtifact(plan, composedDraft, reviewResult)
                : buildSummaryArtifact(plan, composedDraft);
        support.ensureExecutionCanContinue(taskId);
        String stepId = support.findSummaryStepId(taskId)
                .orElseGet(() -> support.subtaskId(taskId, "generate_summary"));
        support.saveArtifact(taskId, stepId, ArtifactType.SUMMARY, plan.getTitle() + " - 摘要", summary, null);
        support.markSummaryStepCompleted(taskId, summary);
    }

    public CompletableFuture<String> routeAfterReview(OverAllState state, RunnableConfig config) {
        boolean waiting = Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE));
        return CompletableFuture.completedFuture(waiting ? StateGraph.END : "compose_doc");
    }

    public CompletableFuture<String> routeAfterWrite(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(StateGraph.END);
    }

    public CompletableFuture<Map<String, Object>> composeDoc(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        support.ensureExecutionCanContinue(taskId);
        DocumentPlan plan = requireValue(state, DocumentStateKeys.DOCUMENT_PLAN, DocumentPlan.class);
        DocumentReviewResult reviewResult = requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class);
        List<DocumentSectionDraft> drafts = mergeSupplementalSections(readSectionDrafts(state), reviewResult.getSupplementalSections());
        ComposedDocumentDraft composedDraft = composeDocumentDraft(
                taskId,
                plan,
                drafts,
                state.value(DocumentStateKeys.MERMAID_DIAGRAM, ""),
                state.value(DocumentStateKeys.DIAGRAM_PLAN, ""),
                reviewResult
        );
        DocumentReviewResult finalizedReview = reviseReviewSummary(reviewResult, composedDraft.getCompletenessReport());
        ensurePublishable(plan, composedDraft);
        support.ensureExecutionCanContinue(taskId);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.REVIEW_TASK_SUFFIX),
                ArtifactType.DOC_DRAFT, plan.getTitle(), composedDraft.getComposedMarkdown(), null);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.SECTION_DRAFTS, composedDraft.getOrderedSections(),
                DocumentStateKeys.COMPOSED_DRAFT, composedDraft,
                DocumentStateKeys.REVIEW_RESULT, finalizedReview
        ));
    }

    private DocumentOutline invokeOutline(String prompt, String taskId) {
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        AssistantMessage response = callAgent(documentOutlineAgent, prompt, taskId + ":outline");
        support.ensureExecutionCanContinue(rootTaskId(taskId));
        try {
            return objectMapper.readValue(response.getText(), DocumentOutline.class);
        } catch (Exception e) {
            return DocumentOutline.builder()
                    .title("文档草稿")
                    .templateType("REPORT")
                    .sections(List.of(DocumentOutlineSection.builder()
                            .heading("背景").keyPoints(List.of(response.getText())).build()))
                    .build();
        }
    }

    private DocumentPlan buildDocumentPlan(String taskId, DocumentOutline outline, OverAllState state) {
        List<DocumentPlanSection> orderedSections = new ArrayList<>();
        List<DocumentOutlineSection> outlineSections = outline.getSections() == null ? List.of() : outline.getSections();
        for (int index = 0; index < outlineSections.size(); index++) {
            DocumentOutlineSection section = outlineSections.get(index);
            if (section == null || section.getHeading() == null || section.getHeading().isBlank()) {
                continue;
            }
            orderedSections.add(DocumentPlanSection.builder()
                    .sectionId("section-" + (index + 1))
                    .index(index + 1)
                    .heading(section.getHeading())
                    .purpose(section.getHeading())
                    .mustCover(safeList(section.getKeyPoints()))
                    .acceptanceCriteria(safeList(section.getKeyPoints()))
                    .build());
        }
        return DocumentPlan.builder()
                .planId(taskId + ":plan")
                .taskId(taskId)
                .title(outline.getTitle())
                .templateType(outline.getTemplateType())
                .orderedSections(orderedSections)
                .globalStyleGuide(List.of("章节顺序必须严格遵循冻结计划", "术语保持一致", "正文使用 Markdown 标题层级组织"))
                .terminologyRules(List.of("不要改写任务中的核心模块命名", "不要引入上下文未出现的外部产品设定"))
                .requiredArtifacts(readRequiredArtifacts(state))
                .diagramPlan(state.value(DocumentStateKeys.DIAGRAM_PLAN, ""))
                .version(1L)
                .build();
    }

    private DocumentSectionDraft parseSectionDraft(String text, DocumentPlanSection section) {
        try {
            DocumentSectionDraft draft = objectMapper.readValue(text, DocumentSectionDraft.class);
            draft.setSectionId(section.getSectionId());
            draft.setHeading(section.getHeading());
            draft.setInputSummary(String.join("；", safeList(section.getMustCover())));
            draft.setUpstreamDependencies(safeList(section.getDependsOn()));
            draft.setStatus("DRAFTED");
            draft.setVersion(1L);
            return draft;
        } catch (Exception e) {
            return DocumentSectionDraft.builder()
                    .sectionId(section.getSectionId())
                    .heading(section.getHeading())
                    .body(text)
                    .inputSummary(String.join("；", safeList(section.getMustCover())))
                    .upstreamDependencies(safeList(section.getDependsOn()))
                    .status("DRAFTED")
                    .version(1L)
                    .build();
        }
    }

    private DocumentReviewResult parseReviewResult(String text) {
        try {
            return objectMapper.readValue(text, DocumentReviewResult.class);
        } catch (Exception e) {
            return DocumentReviewResult.builder()
                    .backgroundCovered(true).goalCovered(true).solutionCovered(true)
                    .risksCovered(true).ownershipCovered(true).timelineCovered(true)
                    .missingItems(List.of()).supplementalSections(List.of()).summary(text).build();
        }
    }

    private DiagramRequirement readDiagramRequirement(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.DIAGRAM_REQUIREMENT);
        if (raw == null) {
            return null;
        }
        return objectMapper.convertValue(raw, DiagramRequirement.class);
    }

    private String buildDiagramPrompt(String diagramPlan, String clarifiedInstruction, DocumentOutline outline, OverAllState state) {
        String requiredHeader = switch (diagramPlan) {
            case "SEQUENCE" -> "sequenceDiagram";
            case "STATE" -> "stateDiagram-v2";
            case "DATA_FLOW" -> "sequenceDiagram";
            case "CONTEXT" -> "flowchart TB";
            default -> "flowchart TB";
        };
        String styleReference = switch (diagramPlan) {
            case "DATA_FLOW" -> """
                    参考风格：
                    sequenceDiagram
                        participant U as User (Frontend/IM)
                        participant S as SupervisorAgent
                        participant R as RedisSaver (Checkpoint)
                        participant P as PlanningAgent
                        Note over U: 用户提交意图 + 上下文
                        U->>S: POST /planner/tasks/plan
                        S->>R: 读取/初始化 threadId(taskId) 状态
                    """.strip();
            case "CONTEXT" -> """
                    参考风格：
                    flowchart TB
                        subgraph A[交互入口层]
                            Entry[GUI / IM 双入口<br>接收指令、展示进度]
                        end
                        subgraph B[会话与任务中枢层]
                            Core[会话与任务中枢<br/>沉淀 Task/Step/Artifact/Event]
                        end
                        Entry --> Core
                    """.strip();
            default -> "";
        };
        return """
                请根据以下任务生成一张 Mermaid 图，只返回 Mermaid 源码，不要解释，不要 Markdown 围栏。
                第一行必须严格以 `%s` 开头。
                图类型：%s
                任务目标：%s
                执行上下文：%s
                文档标题：%s
                章节：%s
                参考风格：%s
                额外规则：
                1. 如果图类型是 flowchart，请优先使用 TB 方向。
                2. 如果图类型是 sequenceDiagram，请显式声明 participant，并用箭头表达调用方向。
                3. 节点命名必须贴合当前文档主题，不要输出泛泛的 A/B/C 或 Node1/Node2。
                4. 只允许使用以下 Mermaid 顶层图类型：flowchart、graph、sequenceDiagram、stateDiagram-v2、erDiagram。
                5. 严禁输出 usecaseDiagram、journey、gantt、classDiagram、pie、mindmap、timeline 或其他未列出的图类型。
                6. Mermaid 不支持 usecaseDiagram；如果你想表达角色、系统、用例之间关系，必须改写成 flowchart，禁止输出任何 usecaseDiagram 代码。
                """.formatted(
                requiredHeader,
                diagramPlan,
                clarifiedInstruction,
                inlineExecutionContext(state),
                outline.getTitle(),
                outline.getSections() == null ? "" : outline.getSections().stream().map(DocumentOutlineSection::getHeading).collect(Collectors.joining("、")),
                styleReference
        );
    }

    private String coerceMermaid(String text, String diagramPlan) {
        String sanitized = normalizeSupportedMermaid(extractMermaid(text));
        if (sanitized.isBlank() || !isMermaidValid(sanitized, diagramPlan)) {
            return defaultMermaid(diagramPlan);
        }
        return sanitized;
    }

    private String extractMermaid(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.trim();
        int fencedStart = sanitized.indexOf("```mermaid");
        if (fencedStart >= 0) {
            int contentStart = fencedStart + "```mermaid".length();
            int fencedEnd = sanitized.indexOf("```", contentStart);
            if (fencedEnd > contentStart) {
                return sanitized.substring(contentStart, fencedEnd).trim();
            }
        }
        sanitized = sanitized.replace("```mermaid", "").replace("```", "").trim();
        if (looksLikeMermaid(sanitized)) {
            return sanitized;
        }
        try {
            DocumentSectionDraft draft = objectMapper.readValue(sanitized, DocumentSectionDraft.class);
            if (draft.getBody() != null && !draft.getBody().isBlank()) {
                String fromBody = extractMermaid(draft.getBody());
                if (!fromBody.isBlank()) {
                    return fromBody;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Map<String, Object> generic = objectMapper.readValue(sanitized, new TypeReference<Map<String, Object>>() { });
            Object body = generic.get("body");
            if (body instanceof String bodyText) {
                String fromBody = extractMermaid(bodyText);
                if (!fromBody.isBlank()) {
                    return fromBody;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean looksLikeMermaid(String text) {
        String lower = defaultString(text).trim().toLowerCase();
        return SUPPORTED_MERMAID_HEADERS.stream().anyMatch(lower::startsWith);
    }

    private boolean isMermaidValid(String mermaid, String diagramPlan) {
        String lower = normalizeSupportedMermaid(mermaid).toLowerCase();
        if (lower.isBlank()) {
            return false;
        }
        return switch (diagramPlan) {
            case "SEQUENCE" -> lower.startsWith("sequencediagram");
            case "STATE" -> lower.startsWith("statediagram");
            case "DATA_FLOW" -> lower.startsWith("sequencediagram");
            case "CONTEXT" -> lower.startsWith("flowchart") || lower.startsWith("graph");
            default -> lower.startsWith("flowchart") || lower.startsWith("graph") || lower.startsWith("sequencediagram");
        };
    }

    private String normalizeSupportedMermaid(String mermaid) {
        String normalized = defaultString(mermaid).trim();
        if (normalized.isBlank()) {
            return "";
        }
        String firstLine = normalized.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("")
                .toLowerCase();
        return SUPPORTED_MERMAID_HEADERS.stream().anyMatch(firstLine::startsWith) ? normalized : "";
    }

    private String defaultMermaid(String diagramPlan) {
        return switch (diagramPlan) {
            case "SEQUENCE" -> """
                    sequenceDiagram
                        participant User
                        participant Planner
                        participant Harness
                        User->>Planner: Submit request
                        Planner->>Harness: Freeze execution contract
                        Harness-->>User: Deliver document
                    """.strip();
            case "STATE" -> """
                    stateDiagram-v2
                        [*] --> PLANNING
                        PLANNING --> PLAN_READY
                        PLAN_READY --> EXECUTING
                        EXECUTING --> COMPLETED
                    """.strip();
            case "DATA_FLOW" -> """
                    sequenceDiagram
                        participant U as User (Frontend/IM)
                        participant P as Planner
                        participant H as Harness
                        participant D as Doc Tool
                        U->>P: 提交文档生成需求
                        P->>H: 下发冻结后的执行契约
                        H->>D: 写入正式文档与图表
                        D-->>H: 返回文档链接
                        H-->>U: 返回最终产物
                    """.strip();
            default -> """
                    flowchart TB
                        subgraph A[交互入口层]
                            Entry[GUI / IM 双入口<br>接收指令、展示进度]
                        end
                        subgraph B[会话与任务中枢层]
                            Core[会话与任务中枢<br/>沉淀 Task/Step/Artifact/Event]
                        end
                        subgraph C[Planner / Orchestrator 层]
                            Planner[规划层<br/>识别意图、拆解任务、必要时重规划]
                            Harness[执行编排层<br/>按状态和条件调度执行]
                        end
                        Entry --> Core
                        Core --> Planner
                        Planner --> Harness
                    """.strip();
        };
    }

    private List<DocumentSectionDraft> mergeSupplementalSections(
            List<DocumentSectionDraft> drafts,
            List<DocumentSectionDraft> supplementals
    ) {
        List<DocumentSectionDraft> merged = new ArrayList<>(drafts == null ? List.of() : drafts);
        if (supplementals == null || supplementals.isEmpty()) {
            return merged;
        }
        for (DocumentSectionDraft supplemental : supplementals) {
            if (supplemental == null || supplemental.getHeading() == null || supplemental.getHeading().isBlank()) {
                continue;
            }
            int matchedIndex = -1;
            for (int index = 0; index < merged.size(); index++) {
                DocumentSectionDraft existing = merged.get(index);
                if (existing != null && existing.getHeading() != null
                        && existing.getHeading().contains(supplemental.getHeading())) {
                    matchedIndex = index;
                    break;
                }
            }
            if (matchedIndex >= 0) {
                DocumentSectionDraft existing = merged.get(matchedIndex);
                String mergedBody = mergeBodies(existing == null ? "" : existing.getBody(), supplemental.getBody());
                merged.set(matchedIndex, DocumentSectionDraft.builder()
                        .sectionId(existing.getSectionId())
                        .heading(existing.getHeading())
                        .body(mergedBody)
                        .inputSummary(existing.getInputSummary())
                        .upstreamDependencies(existing.getUpstreamDependencies())
                        .status("SUPPLEMENTED")
                        .qualityFlags(existing.getQualityFlags())
                        .version(Math.max(1L, existing.getVersion()))
                        .build());
            } else {
                merged.add(supplemental);
            }
        }
        return merged;
    }

    private String mergeBodies(String original, String supplemental) {
        String left = original == null ? "" : original.strip();
        String right = supplemental == null ? "" : supplemental.strip();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        String normalizedLeft = normalizeBodyForMerge(left);
        String normalizedRight = normalizeBodyForMerge(right);
        if (normalizedLeft.contains(normalizedRight)) {
            return left;
        }
        if (normalizedRight.contains(normalizedLeft)) {
            return right;
        }
        return left + "\n\n" + right;
    }

    private String normalizeBodyForMerge(String body) {
        String normalized = BODY_HEADING_PATTERN.matcher(body == null ? "" : body).replaceAll("");
        return BODY_WHITESPACE_PATTERN.matcher(normalized).replaceAll("").toLowerCase();
    }

    ComposedDocumentDraft composeDocumentDraft(
            String taskId,
            DocumentPlan plan,
            List<DocumentSectionDraft> drafts,
            String mermaid,
            String diagramPlan,
            DocumentReviewResult reviewResult
    ) {
        Map<String, DocumentSectionDraft> draftBySectionId = new LinkedHashMap<>();
        Map<String, DocumentSectionDraft> draftByNormalizedHeading = new LinkedHashMap<>();
        for (DocumentSectionDraft draft : drafts == null ? List.<DocumentSectionDraft>of() : drafts) {
            if (draft == null) {
                continue;
            }
            if ((draft.getSectionId() == null || draft.getSectionId().isBlank()) && draft.getHeading() != null) {
                String normalizedHeading = normalizeHeadingForMatch(draft.getHeading());
                DocumentPlanSection matchedSection = safePlanSections(plan).stream()
                        .filter(section -> normalizedHeading.equals(normalizeHeadingForMatch(section.getHeading())))
                        .findFirst()
                        .orElse(null);
                if (matchedSection != null) {
                    draft.setSectionId(matchedSection.getSectionId());
                    draft.setHeading(matchedSection.getHeading());
                }
            }
            if (draft == null || draft.getSectionId() == null || draft.getSectionId().isBlank()) {
                if (draft != null && draft.getHeading() != null && !draft.getHeading().isBlank()) {
                    draftByNormalizedHeading.put(normalizeHeadingForMatch(draft.getHeading()), draft);
                }
                continue;
            }
            draftBySectionId.put(draft.getSectionId(), draft);
            if (draft.getHeading() != null && !draft.getHeading().isBlank()) {
                draftByNormalizedHeading.put(normalizeHeadingForMatch(draft.getHeading()), draft);
            }
        }
        String diagramSectionId = resolveDiagramSectionId(plan, diagramPlan);
        List<DocumentSectionDraft> orderedDrafts = new ArrayList<>();
        List<String> renderedSections = new ArrayList<>();
        List<DocumentSectionCompleteness> completenessSections = new ArrayList<>();
        Set<String> consumedSupplementals = new HashSet<>();
        for (DocumentPlanSection section : safePlanSections(plan)) {
            DocumentSectionDraft draft = draftBySectionId.get(section.getSectionId());
            if (draft == null) {
                draft = draftByNormalizedHeading.get(normalizeHeadingForMatch(section.getHeading()));
            }
            String body = draft == null ? "" : defaultString(draft.getBody()).strip();
            boolean reviewSupplemented = draft != null && "SUPPLEMENTED".equalsIgnoreCase(defaultString(draft.getStatus()));
            if (section.getSectionId().equals(diagramSectionId) && mermaid != null && !mermaid.isBlank()) {
                body = appendMermaid(body, mermaid);
            }
            DocumentSectionDraft normalizedDraft = DocumentSectionDraft.builder()
                    .sectionId(section.getSectionId())
                    .heading(section.getHeading())
                    .body(body)
                    .inputSummary(draft == null ? String.join("；", safeList(section.getMustCover())) : draft.getInputSummary())
                    .upstreamDependencies(draft == null ? safeList(section.getDependsOn()) : draft.getUpstreamDependencies())
                    .status(reviewSupplemented ? "COMPOSED_SUPPLEMENTED" : "COMPOSED")
                    .qualityFlags(draft == null ? List.of("EMPTY_DRAFT") : safeList(draft.getQualityFlags()))
                    .version(draft == null ? 1L : Math.max(1L, draft.getVersion()))
                    .build();
            orderedDrafts.add(normalizedDraft);
            if (draft != null && draft.getHeading() != null) {
                consumedSupplementals.add(normalizeHeadingForMatch(draft.getHeading()));
            }
            String renderedSection = renderComposedSection(section, normalizedDraft);
            renderedSections.add(renderedSection);
            completenessSections.add(buildSectionCompleteness(section, normalizedDraft, renderedSection, reviewSupplemented));
        }
        DocumentCompletenessReport completenessReport = buildCompletenessReport(
                completenessSections,
                safeList(reviewResult == null ? null : reviewResult.getSupplementalSections()),
                consumedSupplementals
        );
        ComposedDocumentDraft composedDraft = ComposedDocumentDraft.builder()
                .taskId(taskId)
                .planId(plan.getPlanId())
                .orderedSections(orderedDrafts)
                .composedMarkdown(String.join("\n\n", renderedSections))
                .consistencyReport("ordered_sections=" + orderedDrafts.size() + ", incomplete=" + completenessReport.getIncompleteSectionHeadings().size())
                .completenessReport(completenessReport)
                .version(1L)
                .build();
        log.info("DOC_COMPOSE composed taskId={} sections={} markdownLength={} containsMermaidFence={} mermaidFenceCount={} preview='{}'",
                taskId,
                orderedDrafts.size(),
                composedDraft.getComposedMarkdown() == null ? 0 : composedDraft.getComposedMarkdown().length(),
                containsMermaidFenceForLog(composedDraft.getComposedMarkdown()),
                countMermaidFencesForLog(composedDraft.getComposedMarkdown()),
                previewMarkdownForLog(composedDraft.getComposedMarkdown()));
        return composedDraft;
    }

    private String renderComposedSection(DocumentPlanSection section, DocumentSectionDraft draft) {
        String normalizedBody = defaultString(draft.getBody()).isBlank()
                ? ""
                : supportMarkdownBody(defaultString(draft.getBody()), Integer.toString(section.getIndex()));
        return "## " + sectionHeading(section) + (normalizedBody.isBlank() ? "" : "\n\n" + normalizedBody);
    }

    private DocumentSectionCompleteness buildSectionCompleteness(
            DocumentPlanSection section,
            DocumentSectionDraft draft,
            String renderedSection,
            boolean reviewSupplemented
    ) {
        String body = defaultString(draft.getBody());
        boolean hasBody = !body.isBlank();
        boolean diagramSection = isDiagramSection(section);
        boolean diagramPresent = containsMermaid(body);
        boolean meaningfulContent = hasMeaningfulContent(body, section.getHeading(), diagramSection, diagramPresent);
        List<String> issues = new ArrayList<>();
        if (!hasBody) {
            issues.add("missing_body");
        }
        if (!meaningfulContent) {
            issues.add("body_not_meaningful");
        }
        if (diagramSection && !diagramPresent) {
            issues.add("diagram_missing");
        }
        if (renderedSection == null || renderedSection.isBlank()) {
            issues.add("render_missing");
        }
        return DocumentSectionCompleteness.builder()
                .sectionId(section.getSectionId())
                .heading(sectionHeading(section))
                .rendered(renderedSection != null && !renderedSection.isBlank())
                .hasBody(hasBody)
                .meaningfulContent(meaningfulContent)
                .diagramSection(diagramSection)
                .diagramPresent(diagramPresent)
                .reviewSupplemented(reviewSupplemented)
                .issues(issues)
                .build();
    }

    private DocumentCompletenessReport buildCompletenessReport(
            List<DocumentSectionCompleteness> sections,
            List<DocumentSectionDraft> supplementalSections,
            Set<String> consumedSupplementals
    ) {
        List<String> incompleteSectionHeadings = sections.stream()
                .filter(section -> section.getIssues() != null && !section.getIssues().isEmpty())
                .map(DocumentSectionCompleteness::getHeading)
                .toList();
        List<String> unmatchedSupplementalHeadings = safeList(supplementalSections).stream()
                .filter(section -> section != null && section.getHeading() != null && !section.getHeading().isBlank())
                .map(DocumentSectionDraft::getHeading)
                .filter(heading -> !consumedSupplementals.contains(normalizeHeadingForMatch(heading)))
                .distinct()
                .toList();
        List<String> issues = new ArrayList<>();
        if (!incompleteSectionHeadings.isEmpty()) {
            issues.add("incomplete_sections=" + String.join("、", incompleteSectionHeadings));
        }
        if (!unmatchedSupplementalHeadings.isEmpty()) {
            issues.add("unmatched_supplementals=" + String.join("、", unmatchedSupplementalHeadings));
        }
        return DocumentCompletenessReport.builder()
                .sections(sections)
                .incompleteSectionHeadings(incompleteSectionHeadings)
                .unmatchedSupplementalHeadings(unmatchedSupplementalHeadings)
                .issues(issues)
                .build();
    }

    private String supportMarkdownBody(String body, String sectionPrefix) {
        return bodyNormalizer.normalizeBodyStructure(body, sectionPrefix);
    }

    private String sectionHeading(DocumentPlanSection section) {
        return toChineseSectionNumber(section.getIndex()) + "、" + bodyNormalizer.displayHeading(section.getHeading());
    }

    private String appendMermaid(String body, String mermaid) {
        String mermaidBlock = "```mermaid\n" + mermaid.strip() + "\n```";
        if (body == null || body.isBlank()) {
            return mermaidBlock;
        }
        if (body.contains(mermaidBlock)) {
            return body;
        }
        return body.strip() + "\n\n" + mermaidBlock;
    }

    private boolean isDiagramSection(DocumentPlanSection section) {
        String heading = defaultString(section.getHeading());
        return heading.contains("图") || heading.toLowerCase().contains("mermaid");
    }

    private boolean containsMermaid(String body) {
        return defaultString(body).contains("```mermaid");
    }

    private boolean hasMeaningfulContent(String body, String heading, boolean diagramSection, boolean diagramPresent) {
        String trimmed = defaultString(body).trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if (diagramSection && diagramPresent) {
            return true;
        }
        for (String line : trimmed.split("\\R")) {
            String normalizedLine = line.trim();
            if (normalizedLine.isBlank()) {
                continue;
            }
            if (normalizedLine.startsWith("#")) {
                continue;
            }
            if (normalizedLine.startsWith("```")) {
                continue;
            }
            if (normalizeHeadingForMatch(normalizedLine).equals(normalizeHeadingForMatch(heading))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String resolveDiagramSectionId(DocumentPlan plan, String diagramPlan) {
        if (diagramPlan == null || diagramPlan.isBlank()) {
            return "";
        }
        String keyword = switch (diagramPlan) {
            case "DATA_FLOW" -> "数据流";
            case "SEQUENCE" -> "时序";
            case "STATE" -> "状态";
            case "CONTEXT" -> "架构";
            default -> "";
        };
        return safePlanSections(plan).stream()
                .filter(section -> section.getHeading() != null)
                .sorted(Comparator.comparingInt(DocumentPlanSection::getIndex))
                .filter(section -> keyword.isBlank() || section.getHeading().contains(keyword))
                .map(DocumentPlanSection::getSectionId)
                .findFirst()
                .orElseGet(() -> safePlanSections(plan).isEmpty() ? "" : safePlanSections(plan).get(0).getSectionId());
    }

    void ensurePublishable(DocumentPlan plan, ComposedDocumentDraft composedDraft) {
        List<DocumentPlanSection> sections = safePlanSections(plan);
        if (sections.isEmpty()) {
            throw new IllegalStateException("Document plan has no ordered sections");
        }
        List<DocumentSectionDraft> drafts = composedDraft == null ? List.of() : safeList(composedDraft.getOrderedSections());
        if (drafts == null || drafts.size() != sections.size()) {
            throw new IllegalStateException("Draft completeness gate failed");
        }
        for (int index = 0; index < sections.size(); index++) {
            DocumentPlanSection section = sections.get(index);
            DocumentSectionDraft draft = drafts.get(index);
            if (draft == null || !Objects.equals(section.getSectionId(), draft.getSectionId())) {
                throw new IllegalStateException("Ordering gate failed at section index " + (index + 1));
            }
        }
        DocumentCompletenessReport completenessReport = composedDraft == null ? null : composedDraft.getCompletenessReport();
        if (completenessReport == null) {
            throw new IllegalStateException("Missing completeness report");
        }
        if (completenessReport.getIncompleteSectionHeadings() != null && !completenessReport.getIncompleteSectionHeadings().isEmpty()) {
            throw new IllegalStateException("Publish gate failed due to incomplete sections: "
                    + String.join("、", completenessReport.getIncompleteSectionHeadings()));
        }
        if (completenessReport.getUnmatchedSupplementalHeadings() != null && !completenessReport.getUnmatchedSupplementalHeadings().isEmpty()) {
            throw new IllegalStateException("Publish gate failed due to unmatched supplemental sections: "
                    + String.join("、", completenessReport.getUnmatchedSupplementalHeadings()));
        }
        String markdown = composedDraft.getComposedMarkdown();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("Rendered markdown is empty");
        }
        int lastOffset = -1;
        for (DocumentPlanSection section : sections) {
            String heading = "## " + sectionHeading(section);
            int currentOffset = markdown.indexOf(heading);
            if (currentOffset < 0) {
                throw new IllegalStateException("Heading consistency gate failed for " + section.getHeading());
            }
            if (currentOffset <= lastOffset) {
                throw new IllegalStateException("Structure gate failed: heading order drifted");
            }
            lastOffset = currentOffset;
        }
    }

    private DocumentReviewResult reviseReviewSummary(DocumentReviewResult reviewResult, DocumentCompletenessReport report) {
        if (reviewResult == null || report == null) {
            return reviewResult;
        }
        if ((report.getIncompleteSectionHeadings() == null || report.getIncompleteSectionHeadings().isEmpty())
                && (report.getUnmatchedSupplementalHeadings() == null || report.getUnmatchedSupplementalHeadings().isEmpty())) {
            return reviewResult;
        }
        List<String> messages = new ArrayList<>();
        messages.add(defaultString(reviewResult.getSummary()));
        if (report.getIncompleteSectionHeadings() != null && !report.getIncompleteSectionHeadings().isEmpty()) {
            messages.add("发布前完整性校验未通过，以下章节仍未有效落盘：" + String.join("、", report.getIncompleteSectionHeadings()));
        }
        if (report.getUnmatchedSupplementalHeadings() != null && !report.getUnmatchedSupplementalHeadings().isEmpty()) {
            messages.add("review 声称补齐但未成功映射的章节：" + String.join("、", report.getUnmatchedSupplementalHeadings()));
        }
        reviewResult.setSummary(messages.stream().filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(" ")));
        return reviewResult;
    }

    private List<DocumentPlanSection> safePlanSections(DocumentPlan plan) {
        return plan == null || plan.getOrderedSections() == null ? List.of() : plan.getOrderedSections();
    }

    private List<String> readRequiredArtifacts(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.ALLOWED_ARTIFACTS);
        if (raw == null) {
            return List.of("DOC");
        }
        List<String> values = objectMapper.convertValue(raw, new TypeReference<List<String>>() { });
        return values == null || values.isEmpty() ? List.of("DOC") : values;
    }

    private boolean isSummaryOnlyRequest(OverAllState state) {
        List<String> requiredArtifacts = readRequiredArtifacts(state);
        return !requiredArtifacts.isEmpty()
                && requiredArtifacts.stream().allMatch(value -> "SUMMARY".equalsIgnoreCase(value));
    }

    private String buildPlanContract(DocumentPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("章节顺序：");
        builder.append(safePlanSections(plan).stream()
                .map(section -> section.getIndex() + "." + sectionHeading(section))
                .collect(Collectors.joining(" | ")));
        if (plan.getGlobalStyleGuide() != null && !plan.getGlobalStyleGuide().isEmpty()) {
            builder.append("\n风格约束：").append(String.join("；", plan.getGlobalStyleGuide()));
        }
        if (plan.getTerminologyRules() != null && !plan.getTerminologyRules().isEmpty()) {
            builder.append("\n术语约束：").append(String.join("；", plan.getTerminologyRules()));
        }
        return builder.toString();
    }

    private String summarizeDrafts(List<DocumentSectionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return "暂无已完成章节。";
        }
        return drafts.stream()
                .filter(Objects::nonNull)
                .map(draft -> draft.getHeading() + "：" + excerpt(defaultString(draft.getBody())))
                .collect(Collectors.joining("\n"));
    }

    private String excerpt(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    private String buildPureSummaryArtifact(DocumentPlan plan, ComposedDocumentDraft composedDraft, DocumentReviewResult reviewResult) {
        List<DocumentSectionDraft> sections = composedDraft == null || composedDraft.getOrderedSections() == null
                ? List.of()
                : composedDraft.getOrderedSections();
        if (!sections.isEmpty()) {
            String body = sections.stream()
                    .filter(Objects::nonNull)
                    .map(section -> {
                        String heading = stripMarkdownSyntax(defaultString(section.getHeading()));
                        String sectionBody = stripMarkdownForIm(defaultString(section.getBody()));
                        if (heading.isBlank()) {
                            return sectionBody;
                        }
                        return heading + "\n" + sectionBody;
                    })
                    .filter(this::hasText)
                    .collect(Collectors.joining("\n\n"));
            if (hasText(body)) {
                return stripMarkdownSyntax(plan.getTitle()) + "\n\n" + body;
            }
        }
        String markdown = composedDraft == null ? null : composedDraft.getComposedMarkdown();
        if (markdown != null && !markdown.isBlank()) {
            return stripMarkdownSyntax(plan.getTitle()) + "\n\n" + stripMarkdownForIm(stripTopLevelHeading(markdown));
        }
        return buildSummaryArtifact(plan, composedDraft);
    }

    private String buildSummaryArtifact(DocumentPlan plan, ComposedDocumentDraft composedDraft) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(plan.getTitle()).append(" - 摘要\n\n");
        builder.append("## 关键内容\n");
        List<DocumentSectionDraft> sections = composedDraft == null || composedDraft.getOrderedSections() == null
                ? List.of()
                : composedDraft.getOrderedSections();
        for (DocumentSectionDraft section : sections) {
            if (section == null || section.getHeading() == null || section.getHeading().isBlank()) {
                continue;
            }
            builder.append("- ")
                    .append(section.getHeading())
                    .append("：")
                    .append(excerpt(stripMarkdownSyntax(defaultString(section.getBody()))))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String stripTopLevelHeading(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return markdown.lines()
                .filter(line -> !line.trim().startsWith("# "))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private String stripMarkdownSyntax(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return markdown
                .replaceAll("(?m)^#+\\s*", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripMarkdownForIm(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return markdown
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .lines()
                .map(line -> line
                        .replaceFirst("^\\s*#+\\s*", "")
                        .replaceFirst("^\\s*[-*+]\\s+", "")
                        .replaceFirst("^\\s*\\d+[.)、]\\s+", "")
                        .trim())
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String normalizeHeadingForMatch(String heading) {
        return bodyNormalizer.stripLeadingOrdinal(bodyNormalizer.normalizeHeading(defaultString(heading)))
                .replaceAll("[\\s:：,.，。;；、/\\\\()（）\\-]+", "")
                .toLowerCase();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toChineseSectionNumber(int index) {
        if (index >= 0 && index < CHINESE_SECTION_NUMBERS.length) {
            return CHINESE_SECTION_NUMBERS[index];
        }
        return Integer.toString(index);
    }

    private DocumentReviewResult evaluateReview(
            String taskId,
            String rawInstruction,
            String clarifiedInstruction,
            DocumentOutline outline,
            List<DocumentSectionDraft> drafts,
            String mermaid,
            String userFeedback,
            OverAllState state,
            String extraInstruction
    ) {
        StringBuilder prompt = new StringBuilder("原始需求：").append(rawInstruction)
                .append("\n任务目标：").append(clarifiedInstruction)
                .append(buildExecutionContext(state))
                .append("\n允许交付物：").append(formatAllowedArtifacts(state))
                .append("\n请重点检查：goal_alignment、artifact_scope_alignment、diagram_alignment。")
                .append(extraInstruction)
                .append("\n文档标题：").append(outline.getTitle()).append("\n");
        if (!userFeedback.isBlank()) {
            prompt.append("人工反馈：").append(userFeedback).append("\n");
        }
        if (mermaid != null && !mermaid.isBlank()) {
            prompt.append("Mermaid 图：\n```mermaid\n").append(mermaid).append("\n```\n");
        }
        drafts.forEach(s -> prompt.append("## ").append(s.getHeading()).append("\n").append(s.getBody()).append("\n"));
        support.ensureExecutionCanContinue(taskId);
        AssistantMessage response = callAgent(documentReviewAgent, prompt.toString(), taskId + ":review");
        support.ensureExecutionCanContinue(taskId);
        return parseReviewResult(response.getText());
    }

    private String joinSummary(String... parts) {
        List<String> filtered = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                filtered.add(part.trim());
            }
        }
        return String.join(" ", filtered);
    }

    private boolean shouldRetryForSupplementalSections(DocumentReviewResult reviewResult, String userFeedback) {
        return !hasText(userFeedback)
                && reviewResult != null
                && reviewResult.getMissingItems() != null
                && !reviewResult.getMissingItems().isEmpty()
                && !hasSupplementalSections(reviewResult);
    }

    private boolean hasSupplementalSections(DocumentReviewResult reviewResult) {
        return reviewResult != null
                && reviewResult.getSupplementalSections() != null
                && !reviewResult.getSupplementalSections().isEmpty();
    }

    private List<String> appendMissingItem(List<String> items, String value) {
        List<String> result = new ArrayList<>(items == null ? List.of() : items);
        if (!result.contains(value)) {
            result.add(value);
        }
        return result;
    }

    private String buildExecutionContext(OverAllState state) {
        String inline = inlineExecutionContext(state);
        return inline.isBlank() ? "" : "\n执行上下文：" + inline;
    }

    private String inlineExecutionContext(OverAllState state) {
        List<String> parts = new ArrayList<>();
        List<String> constraints = readConstraints(state);
        if (!constraints.isEmpty()) {
            parts.add("约束=" + String.join("；", constraints));
        }
        WorkspaceContext sourceScope = readSourceScope(state);
        if (sourceScope != null) {
            List<String> refs = new ArrayList<>();
            if (sourceScope.getTimeRange() != null && !sourceScope.getTimeRange().isBlank()) {
                refs.add("timeRange=" + sourceScope.getTimeRange());
            }
            if (sourceScope.getSelectedMessages() != null && !sourceScope.getSelectedMessages().isEmpty()) {
                refs.add("selectedMessages=" + sourceScope.getSelectedMessages().size());
            }
            if (sourceScope.getDocRefs() != null && !sourceScope.getDocRefs().isEmpty()) {
                refs.add("docRefs=" + sourceScope.getDocRefs().size());
            }
            if (sourceScope.getAttachmentRefs() != null && !sourceScope.getAttachmentRefs().isEmpty()) {
                refs.add("attachments=" + sourceScope.getAttachmentRefs().size());
            }
            if (!refs.isEmpty()) {
                parts.add("sourceScope=" + String.join(", ", refs));
            }
        }
        return String.join(" | ", parts);
    }

    private List<String> readConstraints(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.EXECUTION_CONSTRAINTS);
        if (raw == null) {
            return List.of();
        }
        return objectMapper.convertValue(raw, new TypeReference<List<String>>() { });
    }

    private WorkspaceContext readSourceScope(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.SOURCE_SCOPE);
        if (raw == null) {
            return null;
        }
        return objectMapper.convertValue(raw, WorkspaceContext.class);
    }

    private String formatAllowedArtifacts(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.ALLOWED_ARTIFACTS);
        if (raw == null) {
            return "DOC";
        }
        List<String> values = objectMapper.convertValue(raw, new TypeReference<List<String>>() { });
        return values == null || values.isEmpty() ? "DOC" : String.join(", ", values);
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

    private List<DocumentSectionDraft> readSectionDrafts(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.SECTION_DRAFTS);
        if (raw == null) return List.of();
        return objectMapper.convertValue(raw, new TypeReference<>() {});
    }

    private <T> T requireValue(OverAllState state, String key, Class<T> type) {
        Object raw = state.data().get(key);
        if (raw == null) throw new IllegalStateException("Missing state value: " + key);
        return objectMapper.convertValue(raw, type);
    }

    private boolean containsMermaidFenceForLog(String markdown) {
        return markdown != null && markdown.contains("```mermaid");
    }

    private int countMermaidFencesForLog(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return 0;
        }
        int count = 0;
        int offset = 0;
        String needle = "```mermaid";
        while (true) {
            offset = markdown.indexOf(needle, offset);
            if (offset < 0) {
                return count;
            }
            count++;
            offset += needle.length();
        }
    }

    private String previewMarkdownForLog(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String normalized = markdown.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    private record ReviewResolution(
            DocumentReviewResult reviewResult,
            List<DocumentSectionDraft> effectiveDrafts
    ) {
    }
}
