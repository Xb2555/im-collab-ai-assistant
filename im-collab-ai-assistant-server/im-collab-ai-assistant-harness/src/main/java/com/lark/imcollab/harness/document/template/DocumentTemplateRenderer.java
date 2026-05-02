package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentPlanSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentTemplateRenderer {

    private static final String[] CHINESE_SECTION_NUMBERS = {
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
    };

    private final DocumentBodyNormalizer bodyNormalizer;

    public DocumentTemplateRenderer(DocumentBodyNormalizer bodyNormalizer) {
        this.bodyNormalizer = bodyNormalizer;
    }

    public String render(
            DocumentTemplateType templateType,
            DocumentPlan plan,
            ComposedDocumentDraft composedDraft,
            DocumentReviewResult reviewResult,
            String userFeedback) {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", plan == null ? "" : defaultString(plan.getTitle()));
        vars.put("reviewSummary", reviewResult != null && reviewResult.getSummary() != null
                ? reviewResult.getSummary()
                : "已完成自动审阅。");
        vars.put("userFeedback", defaultString(userFeedback));
        vars.put("sections", resolveSectionsMarkdown(plan, composedDraft));
        return applyTemplate(loadTemplate(templateType), vars);
    }

    private String resolveSectionsMarkdown(DocumentPlan plan, ComposedDocumentDraft composedDraft) {
        if (composedDraft != null && composedDraft.getComposedMarkdown() != null && !composedDraft.getComposedMarkdown().isBlank()) {
            return composedDraft.getComposedMarkdown().strip();
        }
        List<DocumentPlanSection> sections = plan == null ? List.of() : safeList(plan.getOrderedSections());
        return sections.stream()
                .map(section -> renderSection(section, findDraft(composedDraft, section.getSectionId())))
                .filter(rendered -> rendered != null && !rendered.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private String renderSection(DocumentPlanSection section, DocumentSectionDraft draft) {
        if (section == null || section.getHeading() == null || section.getHeading().isBlank()) {
            return "";
        }
        String body = draft == null ? "" : bodyNormalizer.normalizeBodyStructure(
                bodyNormalizer.trimDuplicatedHeading(draft.getBody(), section.getHeading()),
                Integer.toString(section.getIndex())
        );
        String heading = formatTopLevelHeading(section);
        return "## " + heading + (body.isBlank() ? "" : "\n\n" + body);
    }

    private String formatTopLevelHeading(DocumentPlanSection section) {
        String displayHeading = bodyNormalizer.displayHeading(section.getHeading());
        return toChineseSectionNumber(section.getIndex()) + "、" + displayHeading;
    }

    private DocumentSectionDraft findDraft(ComposedDocumentDraft composedDraft, String sectionId) {
        if (composedDraft == null || composedDraft.getOrderedSections() == null || sectionId == null || sectionId.isBlank()) {
            return null;
        }
        return composedDraft.getOrderedSections().stream()
                .filter(section -> section != null && sectionId.equals(section.getSectionId()))
                .findFirst()
                .orElse(null);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
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
        return rendered.replaceAll("\\{[a-zA-Z0-9]+}", "").strip() + "\n";
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
