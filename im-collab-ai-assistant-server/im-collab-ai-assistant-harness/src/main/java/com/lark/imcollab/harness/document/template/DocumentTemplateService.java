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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentTemplateService {

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
        Map<String, String> vars = new HashMap<>();
        vars.put("title", outline.getTitle());
        vars.put("background", findSection(outline, sections, "背景", "背景与上下文", "会议背景", "项目背景", "背景与问题"));
        vars.put("goal", findSection(outline, sections, "目标", "会议目标", "目标与范围", "设计目标与非目标"));
        vars.put("plan", findSection(outline, sections, "方案", "执行方案", "关键结论", "模块分层", "架构原则"));
        vars.put("risks", findSection(outline, sections, "风险", "风险与依赖", "待确认事项", "风险与边界"));
        vars.put("owners", findSection(outline, sections, "分工", "责任分工", "行动项", "演进建议"));
        vars.put("timeline", findSection(outline, sections, "时间", "时间计划", "下一步安排"));
        vars.put("sections", joinSections(sections));
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
        return sections.stream()
                .map(section -> "## " + normalizeHeading(section.getHeading()) + "\n\n" + trimDuplicatedHeading(section.getBody(), section.getHeading()))
                .collect(Collectors.joining("\n\n"));
    }

    private String findSection(DocumentOutline outline, List<DocumentSectionDraft> sections, String... aliases) {
        for (DocumentSectionDraft section : sections) {
            for (String alias : aliases) {
                if (section.getHeading() != null && section.getHeading().contains(alias)) {
                    return section.getBody();
                }
            }
        }
        if (outline != null && outline.getSections() != null) {
            for (var section : outline.getSections()) {
                for (String alias : aliases) {
                    if (section.getHeading() != null && section.getHeading().contains(alias)
                            && section.getKeyPoints() != null && !section.getKeyPoints().isEmpty()) {
                        return synthesizeBodyFromKeyPoints(section.getHeading(), section.getKeyPoints());
                    }
                }
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
        String normalized = heading.strip();
        while (normalized.startsWith("#")) {
            normalized = normalized.substring(1).stripLeading();
        }
        return normalized;
    }

    private String trimDuplicatedHeading(String body, String heading) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalizedHeading = normalizeHeading(heading);
        String normalizedBody = body.strip();
        if (normalizedHeading.isBlank()) {
            return normalizedBody;
        }
        if (normalizedBody.startsWith("## " + normalizedHeading)) {
            return normalizedBody.substring(("## " + normalizedHeading).length()).stripLeading();
        }
        return normalizedBody;
    }

    private String synthesizeBodyFromKeyPoints(String heading, List<String> keyPoints) {
        if (keyPoints == null || keyPoints.isEmpty()) {
            return defaultSectionContent(heading);
        }
        String lead = "### 1.1 核心说明\n\n"
                + "本节围绕“" + normalizeHeading(heading) + "”展开，结合当前任务目标对关键内容进行归纳说明。";
        String details = keyPoints.stream()
                .filter(point -> point != null && !point.isBlank())
                .map(String::trim)
                .map(point -> "- " + point)
                .collect(Collectors.joining("\n"));
        String closing = "#### 1.1.1 落地要点\n\n"
                + "以上要点可作为当前章节的执行依据与汇报口径，在后续评审或扩展实现中继续细化。";
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
        return "### 1.1 核心说明\n\n"
                + "本节用于说明“" + topic + "”在当前任务中的作用、范围与交付预期。系统在生成正式文档时，应优先围绕用户原始需求、补充约束和已有上下文给出完整说明。\n\n"
                + "#### 1.1.1 汇报口径\n\n"
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
}
