package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocumentTemplateService {

    private static final Pattern LEADING_HEADING_MARKS = Pattern.compile("^#+\\s*");
    private static final Pattern LEADING_SECTION_ORDINAL = Pattern.compile(
            "^(?:第[一二三四五六七八九十百千万0-9]+[章节部分篇]\\s*|\\(?[一二三四五六七八九十百千万0-9]+\\)?[、.．]\\s*|[（(][一二三四五六七八九十百千万0-9]+[）)]\\s*|[0-9]+(?:\\.[0-9]+)*\\s+)"
    );
    private static final Pattern MATCH_PUNCTUATION = Pattern.compile("[\\s:：,.，。;；、/\\\\()（）\\-]+");

    public DocumentTemplateType selectTemplate(UserPlanCard card) {
        String text = (card.getTitle() + " " + card.getDescription()).toLowerCase(Locale.ROOT);
        if (text.contains("会议") || text.contains("纪要")) {
            return DocumentTemplateType.MEETING_SUMMARY;
        }
        if (text.contains("需求") || text.contains("prd")) {
            return DocumentTemplateType.REQUIREMENTS;
        }
        if (text.contains("技术") || text.contains("架构") || text.contains("方案")) {
            return DocumentTemplateType.TECHNICAL_PLAN;
        }
        return DocumentTemplateType.REPORT;
    }

    public DocumentTemplateType resolveTemplate(ExecutionContract contract) {
        if (contract == null || contract.getTemplateStrategy() == null || contract.getTemplateStrategy().isBlank()) {
            return DocumentTemplateType.REPORT;
        }
        try {
            return DocumentTemplateType.valueOf(contract.getTemplateStrategy());
        } catch (IllegalArgumentException ignored) {
            return DocumentTemplateType.REPORT;
        }
    }

    public String render(
            DocumentTemplateType templateType,
            DocumentOutline outline,
            List<DocumentSectionDraft> sections,
            DocumentReviewResult reviewResult,
            String userFeedback,
            String mermaidDiagram,
            String diagramPlan) {
        TemplateSections templateSections = resolveTemplateSections(outline, sections);
        Map<String, String> vars = new HashMap<>();
        vars.put("title", outline.getTitle());
        vars.put("background", templateSections.background());
        vars.put("goal", templateSections.goal());
        vars.put("principles", templateSections.principles());
        vars.put("plan", templateSections.plan());
        vars.put("risks", templateSections.risks());
        vars.put("owners", templateSections.owners());
        vars.put("timeline", templateSections.timeline());
        vars.put("sections", joinSections(templateSections.remainingSections()));
        vars.put("reviewSummary", reviewResult != null && reviewResult.getSummary() != null ? reviewResult.getSummary() : "已完成自动审阅。");
        vars.put("userFeedback", userFeedback == null ? "" : userFeedback);
        vars.put("contextDiagram", renderMermaidSection("### 4.1 全局架构流程图", mermaidDiagram, diagramPlan, "CONTEXT"));
        vars.put("dataFlowDiagram", renderMermaidSection("### 4.2 数据流转图", mermaidDiagram, diagramPlan, "DATA_FLOW"));
        vars.put("sequenceDiagram", renderMermaidSection("### 4.3 关键时序图", mermaidDiagram, diagramPlan, "SEQUENCE"));
        vars.put("stateDiagram", renderMermaidSection("### 4.4 状态流转图", mermaidDiagram, diagramPlan, "STATE"));
        vars.put("diagramNotes", mermaidDiagram == null || mermaidDiagram.isBlank() ? "" : "图表已按 Mermaid 源码内嵌，可在后续场景复用。");
        return applyTemplate(loadTemplate(templateType), vars);
    }

    private String joinSections(List<DocumentSectionDraft> sections) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        return sections.stream()
                .filter(section -> section != null
                        && section.getHeading() != null
                        && !section.getHeading().isBlank()
                        && section.getBody() != null
                        && !section.getBody().isBlank())
                .map(section -> {
                    String renderedHeading = displayHeading(section.getHeading());
                    return "## " + renderedHeading + "\n\n" + trimDuplicatedHeading(section.getBody(), section.getHeading());
                })
                .collect(Collectors.joining("\n\n"));
    }

    private TemplateSections resolveTemplateSections(DocumentOutline outline, List<DocumentSectionDraft> sections) {
        List<DocumentSectionDraft> safeSections = sections == null ? List.of() : sections;
        Set<String> consumedHeadings = new HashSet<>();
        String background = findSection(outline, safeSections, consumedHeadings, "项目背景", "背景与问题", "背景与上下文", "会议背景", "背景");
        String goal = findSection(outline, safeSections, consumedHeadings, "设计目标与非目标", "目标与范围", "会议目标", "目标");
        String principles = findSection(outline, safeSections, consumedHeadings, "设计原则", "架构原则", "目标与设计原则", "原则");
        String plan = findSection(outline, safeSections, consumedHeadings, "模块分层与职责", "模块分层", "总体架构", "执行方案", "方案", "关键结论");
        String risks = findSection(outline, safeSections, consumedHeadings, "风险与边界", "风险与依赖", "待确认事项", "风险");
        String owners = findSection(outline, safeSections, consumedHeadings, "演进建议", "责任分工", "分工", "行动项");
        String timeline = findSection(outline, safeSections, consumedHeadings, "实施节奏与时间计划", "时间计划", "下一步安排", "时间");
        List<DocumentSectionDraft> remainingSections = safeSections.stream()
                .filter(section -> section != null)
                .filter(section -> !consumedHeadings.contains(normalizeHeadingForMatch(section.getHeading())))
                .toList();
        return new TemplateSections(background, goal, principles, plan, risks, owners, timeline, remainingSections);
    }

    private String findSection(
            DocumentOutline outline,
            List<DocumentSectionDraft> sections,
            Set<String> consumedHeadings,
            String... aliases
    ) {
        DocumentSectionDraft matchedDraft = findBestDraftMatch(sections, aliases);
        if (matchedDraft != null) {
            consumedHeadings.add(normalizeHeadingForMatch(matchedDraft.getHeading()));
            return matchedDraft.getBody();
        }
        if (outline != null && outline.getSections() != null) {
            var matchedOutlineSection = findBestOutlineMatch(outline.getSections(), aliases);
            if (matchedOutlineSection != null
                    && matchedOutlineSection.getKeyPoints() != null
                    && !matchedOutlineSection.getKeyPoints().isEmpty()) {
                return synthesizeBodyFromKeyPoints(matchedOutlineSection.getHeading(), matchedOutlineSection.getKeyPoints());
            }
        }
        return defaultSectionContent(aliases);
    }

    private String applyTemplate(String template, Map<String, String> vars) {
        String rendered = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String renderMermaidSection(String heading, String mermaidDiagram, String diagramPlan, String expectedPlan) {
        if (mermaidDiagram == null || mermaidDiagram.isBlank()) {
            return "";
        }
        if (diagramPlan == null || diagramPlan.isBlank()) {
            return "";
        }
        if (!expectedPlan.equalsIgnoreCase(diagramPlan)) {
            return "";
        }
        return heading + "\n\n```mermaid\n" + mermaidDiagram.strip() + "\n```";
    }

    private String normalizeHeading(String heading) {
        if (heading == null) {
            return "";
        }
        return LEADING_HEADING_MARKS.matcher(heading.strip()).replaceFirst("").strip();
    }

    private String displayHeading(String heading) {
        String normalized = normalizeHeading(heading);
        String stripped = stripLeadingOrdinal(normalized);
        return stripped.isBlank() ? normalized : stripped;
    }

    private String normalizeHeadingForMatch(String heading) {
        String normalized = stripLeadingOrdinal(normalizeHeading(heading)).toLowerCase(Locale.ROOT);
        return MATCH_PUNCTUATION.matcher(normalized).replaceAll("");
    }

    private String stripLeadingOrdinal(String heading) {
        String stripped = normalizeHeading(heading);
        String previous;
        do {
            previous = stripped;
            stripped = LEADING_SECTION_ORDINAL.matcher(stripped).replaceFirst("").stripLeading();
        } while (!stripped.equals(previous));
        return stripped;
    }

    private String trimDuplicatedHeading(String body, String heading) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalizedHeading = normalizeHeading(heading);
        String displayHeading = displayHeading(heading);
        String normalizedBody = body.strip();
        if (normalizedHeading.isBlank()) {
            return normalizedBody;
        }
        if (normalizedBody.startsWith("## " + normalizedHeading)) {
            return normalizedBody.substring(("## " + normalizedHeading).length()).stripLeading();
        }
        if (!displayHeading.isBlank() && normalizedBody.startsWith("## " + displayHeading)) {
            return normalizedBody.substring(("## " + displayHeading).length()).stripLeading();
        }
        return normalizedBody;
    }

    private DocumentSectionDraft findBestDraftMatch(List<DocumentSectionDraft> sections, String... aliases) {
        DocumentSectionDraft bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        for (DocumentSectionDraft section : sections) {
            if (section == null || section.getHeading() == null || section.getHeading().isBlank()) {
                continue;
            }
            int score = scoreHeadingMatch(section.getHeading(), aliases);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = section;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    private com.lark.imcollab.common.model.entity.DocumentOutlineSection findBestOutlineMatch(
            List<com.lark.imcollab.common.model.entity.DocumentOutlineSection> sections,
            String... aliases
    ) {
        com.lark.imcollab.common.model.entity.DocumentOutlineSection bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        for (com.lark.imcollab.common.model.entity.DocumentOutlineSection section : sections) {
            if (section == null || section.getHeading() == null || section.getHeading().isBlank()) {
                continue;
            }
            int score = scoreHeadingMatch(section.getHeading(), aliases);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = section;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    private int scoreHeadingMatch(String heading, String... aliases) {
        String normalizedHeading = normalizeHeadingForMatch(heading);
        int bestScore = Integer.MIN_VALUE;
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String normalizedAlias = normalizeHeadingForMatch(alias);
            if (normalizedHeading.equals(normalizedAlias)) {
                bestScore = Math.max(bestScore, 300 + normalizedAlias.length());
                continue;
            }
            if (normalizedHeading.startsWith(normalizedAlias)) {
                bestScore = Math.max(bestScore, 200 + normalizedAlias.length());
                continue;
            }
            if (normalizedHeading.contains(normalizedAlias)) {
                bestScore = Math.max(bestScore, 100 + normalizedAlias.length());
            }
        }
        return bestScore;
    }

    private String synthesizeBodyFromKeyPoints(String heading, List<String> keyPoints) {
        if (keyPoints == null || keyPoints.isEmpty()) {
            return defaultSectionContent(heading);
        }
        String lead = "本节围绕“" + normalizeHeading(heading) + "”展开，结合当前任务目标对关键内容进行归纳说明。";
        String details = keyPoints.stream()
                .filter(point -> point != null && !point.isBlank())
                .map(String::trim)
                .map(point -> "- " + point)
                .collect(Collectors.joining("\n"));
        String closing = "以上要点可作为当前章节的执行依据与汇报口径，在后续评审或扩展实现中继续细化。";
        if (details.isBlank()) {
            return lead + "\n\n" + closing;
        }
        return lead + "\n\n" + details + "\n\n" + closing;
    }

    private String defaultSectionContent(String... aliases) {
        String topic = "当前主题";
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    topic = alias.trim();
                    break;
                }
            }
        }
        return "本节用于说明“" + topic + "”在当前任务中的作用、范围与交付预期。系统在生成正式文档时，应优先围绕用户原始需求、补充约束和已有上下文给出完整说明。\n\n"
                + "从汇报视角看，需要明确该主题对应的背景动因、关键判断、执行方式以及后续落地影响，确保不同目标读者都能快速理解本节结论。";
    }

    private String loadTemplate(DocumentTemplateType templateType) {
        ClassPathResource resource = new ClassPathResource("templates/doc/" + templateType.getResourceName());
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load template: " + templateType.getResourceName(), exception);
        }
    }

    private record TemplateSections(
            String background,
            String goal,
            String principles,
            String plan,
            String risks,
            String owners,
            String timeline,
            List<DocumentSectionDraft> remainingSections
    ) {
    }
}
