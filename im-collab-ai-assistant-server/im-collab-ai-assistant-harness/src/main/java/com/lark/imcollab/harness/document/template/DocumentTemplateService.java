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
            String mermaidDiagram) {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", outline.getTitle());
        vars.put("background", findSection(sections, "背景", "背景与上下文", "会议背景"));
        vars.put("goal", findSection(sections, "目标", "会议目标", "目标与范围"));
        vars.put("plan", findSection(sections, "方案", "执行方案", "关键结论"));
        vars.put("risks", findSection(sections, "风险", "风险与依赖", "待确认事项"));
        vars.put("owners", findSection(sections, "分工", "责任分工", "行动项"));
        vars.put("timeline", findSection(sections, "时间", "时间计划", "下一步安排"));
        vars.put("sections", joinSections(sections));
        vars.put("reviewSummary", reviewResult != null && reviewResult.getSummary() != null ? reviewResult.getSummary() : "已完成自动审阅。");
        vars.put("userFeedback", userFeedback == null ? "" : userFeedback);
        vars.put("contextDiagram", renderMermaidSection("系统上下文图", mermaidDiagram));
        vars.put("dataFlowDiagram", renderMermaidSection("数据流转图", mermaidDiagram));
        vars.put("sequenceDiagram", renderMermaidSection("关键时序图", mermaidDiagram));
        vars.put("stateDiagram", renderMermaidSection("状态流转图", mermaidDiagram));
        vars.put("diagramNotes", mermaidDiagram == null || mermaidDiagram.isBlank() ? "本次任务未要求附图。" : "图表已按 Mermaid 源码内嵌，可在后续场景复用。");
        return applyTemplate(loadTemplate(templateType), vars);
    }

    private String joinSections(List<DocumentSectionDraft> sections) {
        return sections.stream()
                .map(section -> "## " + section.getHeading() + "\n\n" + section.getBody())
                .collect(Collectors.joining("\n\n"));
    }

    private String findSection(List<DocumentSectionDraft> sections, String... aliases) {
        for (DocumentSectionDraft section : sections) {
            for (String alias : aliases) {
                if (section.getHeading() != null && section.getHeading().contains(alias)) {
                    return section.getBody();
                }
            }
        }
        return "待补充";
    }

    private String applyTemplate(String template, Map<String, String> vars) {
        String rendered = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String renderMermaidSection(String heading, String mermaidDiagram) {
        if (mermaidDiagram == null || mermaidDiagram.isBlank()) {
            return "## " + heading + "\n\n待补充";
        }
        return "## " + heading + "\n\n```mermaid\n" + mermaidDiagram.strip() + "\n```";
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
