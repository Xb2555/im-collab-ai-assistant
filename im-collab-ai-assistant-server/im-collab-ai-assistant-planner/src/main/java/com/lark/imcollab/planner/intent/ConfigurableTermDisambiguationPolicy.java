package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TermResolution;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.config.PlannerProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class ConfigurableTermDisambiguationPolicy implements TermDisambiguationPolicy {

    private final PlannerProperties.TermPolicyDefinition definition;

    public ConfigurableTermDisambiguationPolicy(PlannerProperties.TermPolicyDefinition definition) {
        this.definition = definition;
    }

    @Override
    public Optional<TermDisambiguationService.DisambiguationOutcome> evaluate(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        String mergedText = buildMergedText(session, rawInstruction, workspaceContext);
        if (!mergedText.toLowerCase(Locale.ROOT).contains(definition.getTerm().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }

        List<ScoredMeaning> scoredMeanings = scoreMeanings(mergedText);
        if (scoredMeanings.isEmpty()) {
            return Optional.empty();
        }
        scoredMeanings.sort(Comparator.comparingInt(ScoredMeaning::score).reversed());
        ScoredMeaning top = scoredMeanings.get(0);
        ScoredMeaning second = scoredMeanings.size() > 1 ? scoredMeanings.get(1) : new ScoredMeaning(null, 0);

        boolean decisive = top.definition() != null
                && top.score() >= definition.getMinimumScore()
                && top.score() >= second.score() + definition.getDecisiveGap();
        if (!decisive) {
            RequireInput requireInput = RequireInput.builder()
                    .type("CHOICE")
                    .prompt(buildClarificationPrompt(session))
                    .options(definition.getMeanings().stream()
                            .map(PlannerProperties.TermMeaningDefinition::getUserLabel)
                            .toList())
                    .build();
            return Optional.of(new TermDisambiguationService.DisambiguationOutcome(List.of(), requireInput));
        }

        TermResolution resolution = TermResolution.builder()
                .term(definition.getTerm())
                .resolvedMeaning(top.definition().getMeaningCode())
                .confidence(top.score() >= definition.getHighConfidenceScore() ? "HIGH" : "MEDIUM")
                .rationale("Matched configurable term policy: " + definition.getTerm())
                .candidateMeanings(definition.getMeanings().stream()
                        .map(PlannerProperties.TermMeaningDefinition::getMeaningCode)
                        .toList())
                .build();
        return Optional.of(new TermDisambiguationService.DisambiguationOutcome(List.of(resolution), null));
    }

    private List<ScoredMeaning> scoreMeanings(String mergedText) {
        String lower = mergedText.toLowerCase(Locale.ROOT);
        List<ScoredMeaning> scored = new ArrayList<>();
        for (PlannerProperties.TermMeaningDefinition meaning : definition.getMeanings()) {
            int score = 0;
            for (String signal : defaultList(meaning.getSignals())) {
                if (lower.contains(signal.toLowerCase(Locale.ROOT))) {
                    score += 2;
                }
            }
            for (String signal : defaultList(meaning.getStrongSignals())) {
                if (lower.contains(signal.toLowerCase(Locale.ROOT))) {
                    score += 4;
                }
            }
            scored.add(new ScoredMeaning(meaning, score));
        }
        return scored;
    }

    private String buildMergedText(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append(safe(rawInstruction)).append('\n')
                .append(safe(session.getClarifiedInstruction())).append('\n')
                .append(String.join("；", defaultList(session.getClarificationAnswers()))).append('\n')
                .append(safe(session.getProfession())).append('\n')
                .append(safe(session.getIndustry())).append('\n');
        if (workspaceContext != null) {
            builder.append(safe(workspaceContext.getProfession())).append('\n')
                    .append(safe(workspaceContext.getIndustry())).append('\n')
                    .append(String.join("\n", defaultList(workspaceContext.getSelectedMessages()))).append('\n')
                    .append(String.join("\n", defaultList(workspaceContext.getDocRefs())));
        }
        return builder.toString();
    }

    private String buildClarificationPrompt(PlanTaskSession session) {
        String persona = safe(session.getProfession()) + (safe(session.getIndustry()).isBlank() ? "" : " / " + safe(session.getIndustry()));
        return "术语“" + definition.getTerm() + "”在当前语境里可能对应不同含义。"
                + (persona.isBlank() ? "" : "结合你的身份背景（" + persona + "），")
                + safe(definition.getClarificationPrompt());
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredMeaning(PlannerProperties.TermMeaningDefinition definition, int score) {
    }
}
