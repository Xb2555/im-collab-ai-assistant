package com.lark.imcollab.harness.document.template;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentTemplateRenderer {

    private static final String[] CHINESE_SECTION_NUMBERS = {
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
    };

    private final TemplateSectionResolver sectionResolver;
    private final DocumentBodyNormalizer bodyNormalizer;

    public DocumentTemplateRenderer(
            TemplateSectionResolver sectionResolver,
            DocumentBodyNormalizer bodyNormalizer) {
        this.sectionResolver = sectionResolver;
        this.bodyNormalizer = bodyNormalizer;
    }

    public String render(
            DocumentTemplateType templateType,
            DocumentOutline outline,
            List<DocumentSectionDraft> sections,
            DocumentReviewResult reviewResult,
            String userFeedback,
            String mermaidDiagram,
            String diagramPlan) {
        ResolvedTemplateSections resolvedSections = sectionResolver.resolve(outline, sections);
        Map<String, String> vars = new HashMap<>();
        vars.put("title", outline.getTitle());
        vars.put("background", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.BACKGROUND), "1"));
        vars.put("goal", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.GOAL), "2"));
        vars.put("principles", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.PRINCIPLES), "3"));
        vars.put("plan", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.PLAN), "5"));
        vars.put("risks", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.RISKS), "6"));
        vars.put("owners", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.OWNERS), "7"));
        vars.put("timeline", bodyNormalizer.normalizeBodyStructure(resolvedSections.contentOf(DocumentSemanticSection.TIMELINE), "8"));
        vars.put("architectureViewBlock", buildArchitectureViewBlock(mermaidDiagram, diagramPlan));
        vars.put("dataFlowDiagram", buildDataFlowDiagramBlock(mermaidDiagram, diagramPlan));
        vars.put("additionalSectionsBlock", buildAdditionalSectionsBlock(resolvedSections.remainingSections(), 9));
        vars.put("sections", joinSections(resolvedSections.remainingSections()));
        vars.put("reviewSummary", reviewResult != null && reviewResult.getSummary() != null ? reviewResult.getSummary() : "已完成自动审阅。");
        vars.put("userFeedback", userFeedback == null ? "" : userFeedback);
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
                    String renderedHeading = bodyNormalizer.displayHeading(section.getHeading());
                    return "## " + renderedHeading + "\n\n"
                            + bodyNormalizer.normalizeBodyStructure(
                            bodyNormalizer.trimDuplicatedHeading(section.getBody(), section.getHeading()),
                            null
                    );
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String buildArchitectureViewBlock(String mermaidDiagram, String diagramPlan) {
        List<String> blocks = new ArrayList<>();
        addDiagramBlock(blocks, "全局架构流程图", mermaidDiagram, diagramPlan, "CONTEXT");
        addDiagramBlock(blocks, "数据流转图", mermaidDiagram, diagramPlan, "DATA_FLOW");
        addDiagramBlock(blocks, "关键时序图", mermaidDiagram, diagramPlan, "SEQUENCE");
        addDiagramBlock(blocks, "状态流转图", mermaidDiagram, diagramPlan, "STATE");
        if (blocks.isEmpty()) {
            return "";
        }
        for (int index = 0; index < blocks.size(); index++) {
            blocks.set(index, "### 4." + (index + 1) + " " + blocks.get(index));
        }
        return String.join("\n\n", blocks);
    }

    private String buildDataFlowDiagramBlock(String mermaidDiagram, String diagramPlan) {
        String block = renderMermaidSection("## 四、数据流转图", mermaidDiagram, diagramPlan, "DATA_FLOW");
        return block.isBlank() ? "" : block;
    }

    private void addDiagramBlock(List<String> blocks, String title, String mermaidDiagram, String diagramPlan, String expectedPlan) {
        String content = renderMermaidSection(title, mermaidDiagram, diagramPlan, expectedPlan);
        if (!content.isBlank()) {
            blocks.add(content);
        }
    }

    private String buildAdditionalSectionsBlock(List<DocumentSectionDraft> remainingSections, int sectionNumber) {
        String detailedSections = renderDetailedSections(remainingSections, sectionNumber);
        if (detailedSections.isBlank()) {
            return "";
        }
        return renderTopLevelSection(sectionNumber, "详细设计展开", detailedSections);
    }

    private String renderDetailedSections(List<DocumentSectionDraft> sections, int topLevelSectionNumber) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        int subsectionIndex = 1;
        List<String> renderedSections = new ArrayList<>();
        for (DocumentSectionDraft section : sections) {
            if (section == null
                    || section.getHeading() == null
                    || section.getHeading().isBlank()
                    || section.getBody() == null
                    || section.getBody().isBlank()) {
                continue;
            }
            String subsectionPrefix = topLevelSectionNumber + "." + subsectionIndex++;
            String renderedHeading = bodyNormalizer.displayHeading(section.getHeading());
            String normalizedBody = bodyNormalizer.normalizeBodyStructure(
                    bodyNormalizer.trimDuplicatedHeading(section.getBody(), section.getHeading()),
                    subsectionPrefix
            );
            renderedSections.add("### " + subsectionPrefix + " " + renderedHeading + "\n\n" + normalizedBody);
        }
        return String.join("\n\n", renderedSections);
    }

    private String renderTopLevelSection(int index, String title, String body) {
        return "## " + toChineseSectionNumber(index) + "、" + title + "\n\n" + body;
    }

    private String renderMermaidSection(String title, String mermaidDiagram, String diagramPlan, String expectedPlan) {
        if (mermaidDiagram == null || mermaidDiagram.isBlank()) {
            return "";
        }
        if (diagramPlan == null || diagramPlan.isBlank() || !expectedPlan.equalsIgnoreCase(diagramPlan)) {
            return "";
        }
        return title + "\n\n```mermaid\n" + mermaidDiagram.strip() + "\n```";
    }

    private String toChineseSectionNumber(int index) {
        if (index >= 0 && index < CHINESE_SECTION_NUMBERS.length) {
            return CHINESE_SECTION_NUMBERS[index];
        }
        return Integer.toString(index);
    }

    private String applyTemplate(String template, Map<String, String> vars) {
        String rendered = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered.replaceAll("\\{[a-zA-Z0-9]+}", "");
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
