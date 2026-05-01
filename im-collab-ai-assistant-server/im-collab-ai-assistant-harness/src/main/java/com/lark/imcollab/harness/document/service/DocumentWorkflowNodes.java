package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.model.entity.DiagramRequirement;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.template.DocumentTemplateRenderer;
import com.lark.imcollab.harness.document.template.DocumentTemplateStrategyResolver;
import com.lark.imcollab.harness.document.template.DocumentTemplateType;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.skills.lark.doc.LarkDocCreateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocumentWorkflowNodes {

    private static final Pattern BODY_HEADING_PATTERN = Pattern.compile("(?m)^#+\\s*");
    private static final Pattern BODY_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final DocumentExecutionSupport support;
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
            DocumentTemplateRenderer templateRenderer,
            DocumentTemplateStrategyResolver templateStrategyResolver,
            @Qualifier("documentOutlineAgent") ReactAgent documentOutlineAgent,
            @Qualifier("documentSectionAgent") ReactAgent documentSectionAgent,
            @Qualifier("documentDiagramAgent") ReactAgent documentDiagramAgent,
            @Qualifier("documentReviewAgent") ReactAgent documentReviewAgent,
            LarkDocTool larkDocTool,
            ObjectMapper objectMapper) {
        this.support = support;
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
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX),
                ArtifactType.DOC_OUTLINE, outline.getTitle(), support.writeJson(outline), null);
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.OUTLINE, outline,
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
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, state.value(DocumentStateKeys.RAW_INSTRUCTION, ""));
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = new ArrayList<>();
        for (DocumentOutlineSection section : outline.getSections()) {
            String prompt = "任务目标：" + clarifiedInstruction
                    + buildExecutionContext(state)
                    + "\n文档标题：" + outline.getTitle()
                    + "\n章节：" + section.getHeading()
                    + "\n要点：" + String.join("；", section.getKeyPoints());
            AssistantMessage response = callAgent(documentSectionAgent, prompt, taskId + ":section:" + section.getHeading());
            drafts.add(parseSectionDraft(response.getText(), section));
        }
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
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
        String clarifiedInstruction = state.value(DocumentStateKeys.CLARIFIED_INSTRUCTION, state.value(DocumentStateKeys.RAW_INSTRUCTION, ""));
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        String prompt = buildDiagramPrompt(diagramPlan, clarifiedInstruction, outline, state);
        AssistantMessage response = callAgent(documentDiagramAgent, prompt, taskId + ":diagram:" + diagramPlan.toLowerCase());
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

        List<DocumentSectionDraft> effectiveDrafts = drafts;
        boolean autoSupplemented = reviewResult.getSupplementalSections() != null && !reviewResult.getSupplementalSections().isEmpty();
        if (autoSupplemented) {
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
                    "系统已自动合并补充章节并完成二次复审。",
                    secondPass.getSummary()
            ));
            reviewResult = secondPass;
        }

        boolean needsHumanReview = reviewResult.getMissingItems() != null && !reviewResult.getMissingItems().isEmpty();
        if (needsHumanReview && userFeedback.isBlank()) {
            support.publishApprovalRequest(taskId, null, "文档审核发现问题：" + String.join("；", reviewResult.getMissingItems()));
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.REVIEW_RESULT, reviewResult,
                    DocumentStateKeys.SECTION_DRAFTS, effectiveDrafts
            ));
        }
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.REVIEW_RESULT, reviewResult,
                DocumentStateKeys.DONE_REVIEW, true,
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false,
                DocumentStateKeys.SECTION_DRAFTS, effectiveDrafts
        ));
    }

    public CompletableFuture<Map<String, Object>> writeDocAndSync(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = mergeSupplementalSections(readSectionDrafts(state),
                requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class).getSupplementalSections());
        DocumentReviewResult reviewResult = requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class);
        String markdown = templateRenderer.render(
                DocumentTemplateType.valueOf(state.value(DocumentStateKeys.TEMPLATE_TYPE, "REPORT")),
                outline, drafts, reviewResult,
                state.value(DocumentStateKeys.USER_FEEDBACK, ""),
                state.value(DocumentStateKeys.MERMAID_DIAGRAM, ""),
                state.value(DocumentStateKeys.DIAGRAM_PLAN, "")
        );
        LarkDocCreateResult result = larkDocTool.createDoc(outline.getTitle(), markdown);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.WRITE_TASK_SUFFIX),
                ArtifactType.DOC_LINK, outline.getTitle(), null, result.getDocId(), result.getDocUrl());
        support.publishEvent(taskId, null, TaskEventType.ARTIFACT_CREATED);
        support.publishEvent(taskId, null, TaskEventType.TASK_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.DOC_URL, result.getDocUrl(),
                DocumentStateKeys.DOC_ID, result.getDocId(),
                DocumentStateKeys.DONE_WRITE, true
        ));
    }

    public CompletableFuture<String> routeAfterReview(OverAllState state, RunnableConfig config) {
        boolean waiting = Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE));
        return CompletableFuture.completedFuture(waiting ? StateGraph.END : "write_doc_and_sync");
    }

    public CompletableFuture<String> routeAfterWrite(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(StateGraph.END);
    }

    private DocumentOutline invokeOutline(String prompt, String taskId) {
        AssistantMessage response = callAgent(documentOutlineAgent, prompt, taskId + ":outline");
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

    private DocumentSectionDraft parseSectionDraft(String text, DocumentOutlineSection section) {
        try {
            return objectMapper.readValue(text, DocumentSectionDraft.class);
        } catch (Exception e) {
            return DocumentSectionDraft.builder().heading(section.getHeading()).body(text).build();
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
        String sanitized = extractMermaid(text);
        if (sanitized.isBlank()) {
            sanitized = defaultMermaid(diagramPlan);
        }
        if (!isMermaidValid(sanitized, diagramPlan)) {
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
        String lower = text.toLowerCase();
        return lower.startsWith("flowchart")
                || lower.startsWith("graph")
                || lower.startsWith("sequencediagram")
                || lower.startsWith("statediagram")
                || lower.startsWith("statediagram-v2")
                || lower.startsWith("erdiagram");
    }

    private boolean isMermaidValid(String mermaid, String diagramPlan) {
        String lower = mermaid.toLowerCase();
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
                        .heading(existing.getHeading())
                        .body(mergedBody)
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
        AssistantMessage response = callAgent(documentReviewAgent, prompt.toString(), taskId + ":review");
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
        try {
            return agent.call(prompt, RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            throw new IllegalStateException("Agent call failed for thread: " + threadId, e);
        }
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
}
