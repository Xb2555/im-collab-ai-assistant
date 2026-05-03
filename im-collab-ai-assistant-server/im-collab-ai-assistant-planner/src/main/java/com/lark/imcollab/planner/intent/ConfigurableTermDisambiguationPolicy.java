package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TermResolution;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.config.PlannerProperties;

import java.util.List;
import java.util.Optional;

public class ConfigurableTermDisambiguationPolicy implements TermDisambiguationPolicy {

    private final PlannerProperties.TermPolicyDefinition definition;
    private final LlmChoiceResolver choiceResolver;

    public ConfigurableTermDisambiguationPolicy(
            PlannerProperties.TermPolicyDefinition definition,
            LlmChoiceResolver choiceResolver
    ) {
        this.definition = definition;
        this.choiceResolver = choiceResolver;
    }

    @Override
    public Optional<TermDisambiguationService.DisambiguationOutcome> evaluate(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        String mergedText = buildMergedText(session, rawInstruction, workspaceContext);
        List<String> allowedChoices = new java.util.ArrayList<>();
        allowedChoices.add("NONE");
        allowedChoices.add("CLARIFY");
        definition.getMeanings().stream()
                .map(PlannerProperties.TermMeaningDefinition::getMeaningCode)
                .filter(value -> value != null && !value.isBlank())
                .forEach(allowedChoices::add);
        String choice = choiceResolver.chooseOne(mergedText, allowedChoices, buildSystemPrompt(session));
        if (choice == null || choice.isBlank() || "NONE".equalsIgnoreCase(choice.trim())) {
            return Optional.empty();
        }

        if ("CLARIFY".equalsIgnoreCase(choice.trim())) {
            RequireInput requireInput = RequireInput.builder()
                    .type("CHOICE")
                    .prompt(buildClarificationPrompt(session))
                    .options(definition.getMeanings().stream()
                            .map(PlannerProperties.TermMeaningDefinition::getUserLabel)
                            .toList())
                    .build();
            return Optional.of(new TermDisambiguationService.DisambiguationOutcome(List.of(), requireInput));
        }

        PlannerProperties.TermMeaningDefinition resolved = definition.getMeanings().stream()
                .filter(meaning -> choice.trim().equalsIgnoreCase(meaning.getMeaningCode()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            return Optional.empty();
        }
        TermResolution resolution = TermResolution.builder()
                .term(definition.getTerm())
                .resolvedMeaning(resolved.getMeaningCode())
                .confidence("MEDIUM")
                .rationale("Resolved by LLM term disambiguation policy: " + definition.getTerm())
                .candidateMeanings(definition.getMeanings().stream()
                        .map(PlannerProperties.TermMeaningDefinition::getMeaningCode)
                        .toList())
                .build();
        return Optional.of(new TermDisambiguationService.DisambiguationOutcome(List.of(resolution), null));
    }

    private String buildMergedText(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append(safe(rawInstruction)).append('\n')
                .append(safe(session == null ? null : session.getClarifiedInstruction())).append('\n')
                .append(String.join("；", defaultList(session == null ? null : session.getClarificationAnswers()))).append('\n')
                .append(safe(session == null ? null : session.getProfession())).append('\n')
                .append(safe(session == null ? null : session.getIndustry())).append('\n');
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

    private String buildSystemPrompt(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 Planner 的术语消歧子能力，只能在固定选项中选择。\n");
        builder.append("如果用户语境没有涉及术语 “").append(definition.getTerm()).append("”，选 NONE。\n");
        builder.append("如果涉及该术语但无法可靠判断，选 CLARIFY。\n");
        builder.append("如果可以判断，选对应 meaningCode。\n");
        builder.append("不要规划任务，不要解释。\n");
        builder.append("候选含义：\n");
        for (PlannerProperties.TermMeaningDefinition meaning : definition.getMeanings()) {
            builder.append("- ").append(meaning.getMeaningCode())
                    .append(": ").append(meaning.getUserLabel())
                    .append("\n");
        }
        builder.append("用户身份背景：")
                .append(safe(session == null ? null : session.getProfession()))
                .append(safe(session == null ? null : session.getIndustry()).isBlank()
                        ? ""
                        : " / " + safe(session.getIndustry()))
                .append("\n");
        return builder.toString();
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

}
