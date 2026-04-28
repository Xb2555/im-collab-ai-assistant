package com.lark.imcollab.harness.scene.c.template;

import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.harness.scene.c.model.SceneCDocOutline;
import com.lark.imcollab.harness.scene.c.model.SceneCDocReviewResult;
import com.lark.imcollab.harness.scene.c.model.SceneCDocSectionDraft;
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
public class DocTemplateService {

    public DocTemplateType selectTemplate(UserPlanCard card) {
        String text = (card.getTitle() + " " + card.getDescription()).toLowerCase(Locale.ROOT);
        if (text.contains("会议") || text.contains("纪要")) {
            return DocTemplateType.MEETING_SUMMARY;
        }
        if (text.contains("需求") || text.contains("prd")) {
            return DocTemplateType.REQUIREMENTS;
        }
        if (text.contains("技术") || text.contains("架构") || text.contains("方案")) {
            return DocTemplateType.TECHNICAL_PLAN;
        }
        return DocTemplateType.REPORT;
    }

    public String render(
            DocTemplateType templateType,
            SceneCDocOutline outline,
            List<SceneCDocSectionDraft> sections,
            SceneCDocReviewResult reviewResult,
            String userFeedback) {
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
        return applyTemplate(loadTemplate(templateType), vars);
    }

    private String joinSections(List<SceneCDocSectionDraft> sections) {
        return sections.stream()
                .map(section -> "## " + section.getHeading() + "\n\n" + section.getBody())
                .collect(Collectors.joining("\n\n"));
    }

    private String findSection(List<SceneCDocSectionDraft> sections, String... aliases) {
        for (SceneCDocSectionDraft section : sections) {
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

    private String loadTemplate(DocTemplateType templateType) {
        ClassPathResource resource = new ClassPathResource("templates/doc/" + templateType.getResourceName());
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load template: " + templateType.getResourceName(), exception);
        }
    }
}
