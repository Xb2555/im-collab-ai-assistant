package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class TemplateSectionResolver {

    private static final Pattern MATCH_PUNCTUATION = Pattern.compile("[\\s:：,.，。;；、/\\\\()（）\\-]+");

    private final DocumentBodyNormalizer bodyNormalizer;

    public TemplateSectionResolver(DocumentBodyNormalizer bodyNormalizer) {
        this.bodyNormalizer = bodyNormalizer;
    }

    public ResolvedTemplateSections resolve(DocumentOutline outline, List<DocumentSectionDraft> sections) {
        List<DocumentSectionDraft> safeSections = sections == null ? List.of() : sections;
        Set<String> consumedHeadings = new HashSet<>();
        EnumMap<DocumentSemanticSection, String> slotContent = new EnumMap<>(DocumentSemanticSection.class);
        for (DocumentSemanticSection semanticSection : DocumentSemanticSection.values()) {
            slotContent.put(semanticSection, resolveSlot(outline, safeSections, consumedHeadings, semanticSection));
        }
        List<DocumentSectionDraft> remainingSections = safeSections.stream()
                .filter(section -> section != null)
                .filter(section -> !consumedHeadings.contains(normalizeHeadingForMatch(section.getHeading())))
                .toList();
        return new ResolvedTemplateSections(slotContent, remainingSections);
    }

    private String resolveSlot(
            DocumentOutline outline,
            List<DocumentSectionDraft> sections,
            Set<String> consumedHeadings,
            DocumentSemanticSection semanticSection
    ) {
        DocumentSectionDraft matchedDraft = findBestDraftMatch(sections, semanticSection.aliases());
        if (matchedDraft != null) {
            consumedHeadings.add(normalizeHeadingForMatch(matchedDraft.getHeading()));
            return matchedDraft.getBody();
        }
        if (outline != null && outline.getSections() != null) {
            DocumentOutlineSection matchedOutline = findBestOutlineMatch(outline.getSections(), semanticSection.aliases());
            if (matchedOutline != null && matchedOutline.getKeyPoints() != null && !matchedOutline.getKeyPoints().isEmpty()) {
                return synthesizeBodyFromKeyPoints(matchedOutline.getHeading(), matchedOutline.getKeyPoints());
            }
        }
        return defaultSectionContent(semanticSection.aliases());
    }

    private DocumentSectionDraft findBestDraftMatch(List<DocumentSectionDraft> sections, List<String> aliases) {
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

    private DocumentOutlineSection findBestOutlineMatch(List<DocumentOutlineSection> sections, List<String> aliases) {
        DocumentOutlineSection bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        for (DocumentOutlineSection section : sections) {
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

    private int scoreHeadingMatch(String heading, List<String> aliases) {
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

    private String normalizeHeadingForMatch(String heading) {
        String normalized = bodyNormalizer.stripLeadingOrdinal(bodyNormalizer.normalizeHeading(heading)).toLowerCase(Locale.ROOT);
        return MATCH_PUNCTUATION.matcher(normalized).replaceAll("");
    }

    private String synthesizeBodyFromKeyPoints(String heading, List<String> keyPoints) {
        if (keyPoints == null || keyPoints.isEmpty()) {
            return defaultSectionContent(List.of(heading));
        }
        String lead = "本节围绕“" + bodyNormalizer.normalizeHeading(heading) + "”展开，结合当前任务目标对关键内容进行归纳说明。";
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

    private String defaultSectionContent(List<String> aliases) {
        String topic = aliases == null || aliases.isEmpty() ? "当前主题" : aliases.get(0).trim();
        return "本节用于说明“" + topic + "”在当前任务中的作用、范围与交付预期。系统在生成正式文档时，应优先围绕用户原始需求、补充约束和已有上下文给出完整说明。\n\n"
                + "从汇报视角看，需要明确该主题对应的背景动因、关键判断、执行方式以及后续落地影响，确保不同目标读者都能快速理解本节结论。";
    }
}
