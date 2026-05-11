package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.DiagramRequirement;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TermResolution;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.intent.ArtifactIntentResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ExecutionContractFactory {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContractFactory.class);

    private final ArtifactIntentResolver artifactIntentResolver;

    public ExecutionContractFactory() {
        this(new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "DOC"));
    }

    @Autowired
    public ExecutionContractFactory(ArtifactIntentResolver artifactIntentResolver) {
        this.artifactIntentResolver = artifactIntentResolver;
    }

    public ExecutionContract build(PlanTaskSession session) {
        String rawInstruction = resolveExecutionInstruction(session);
        List<String> requestedArtifacts = deriveRequestedArtifacts(session);
        List<String> allowedArtifacts = requestedArtifacts.isEmpty() ? List.of("DOC") : requestedArtifacts;
        String clarifiedInstruction = buildClarifiedInstruction(session, rawInstruction);
        List<String> resolvedConstraints = resolveConstraints(session);

        WorkspaceContext sourceScope = session.getPlanBlueprint() != null && session.getPlanBlueprint().getSourceScope() != null
                ? session.getPlanBlueprint().getSourceScope()
                : session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getSourceScope();
        return ExecutionContract.builder()
                .taskId(session.getTaskId())
                .rawInstruction(rawInstruction)
                .clarifiedInstruction(clarifiedInstruction)
                .taskBrief(firstNonBlank(
                        session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getTaskBrief(),
                        session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal(),
                        session.getPlanBlueprintSummary(),
                        rawInstruction
                ))
                .requestedArtifacts(requestedArtifacts)
                .allowedArtifacts(allowedArtifacts)
                .primaryArtifact(allowedArtifacts.get(0))
                .crossArtifactPolicy("FORBID_UNLESS_EXPLICIT")
                .audience(firstNonBlank(
                        session.getAudience(),
                        session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getAudience()
                ))
                .timeScope(firstNonBlank(
                        session.getPlanBlueprint() == null || session.getPlanBlueprint().getSourceScope() == null
                                ? null : session.getPlanBlueprint().getSourceScope().getTimeRange(),
                        session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getTimeRange()
                ))
                .constraints(resolvedConstraints)
                .sourceScope(sourceScope)
                .contextRefs(resolveContextRefs(sourceScope))
                .domainContext(resolveDomainContext(session))
                .termResolutions(defaultList(session.getTermResolutions()))
                .templateStrategy(resolveTemplateStrategy(rawInstruction, clarifiedInstruction))
                .diagramRequirement(resolveDiagramRequirement(rawInstruction, clarifiedInstruction, resolvedConstraints))
                .frozenAt(Instant.now())
                .build();
    }

    public PlanBlueprint applyArtifactGate(PlanBlueprint blueprint, ExecutionContract contract) {
        if (blueprint == null || contract == null || contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
            return blueprint;
        }
        Set<String> allowed = new LinkedHashSet<>(contract.getAllowedArtifacts());
        List<String> deliverables = defaultList(blueprint.getDeliverables()).stream()
                .map(this::normalizeArtifact)
                .filter(allowed::contains)
                .toList();
        var cards = defaultList(blueprint.getPlanCards()).stream()
                .filter(card -> card.getType() == null || allowed.contains(normalizeArtifact(card.getType().name())))
                .toList();
        if (!deliverables.isEmpty()) {
            blueprint.setDeliverables(deliverables);
        }
        if (!cards.isEmpty()) {
            blueprint.setPlanCards(cards);
        }
        return blueprint;
    }

    private List<String> deriveRequestedArtifacts(PlanTaskSession session) {
        Set<String> artifacts = new LinkedHashSet<>();
        IntentSnapshot intentSnapshot = session.getIntentSnapshot();
        if (intentSnapshot != null && intentSnapshot.getDeliverableTargets() != null) {
            intentSnapshot.getDeliverableTargets().stream()
                    .map(this::normalizeArtifact)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(artifacts::add);
        }
        PlanBlueprint blueprint = session.getPlanBlueprint();
        if (blueprint != null && blueprint.getDeliverables() != null) {
            blueprint.getDeliverables().stream()
                    .map(this::normalizeArtifact)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(artifacts::add);
        }
        if (blueprint != null && blueprint.getPlanCards() != null) {
            blueprint.getPlanCards().stream()
                    .map(card -> card.getType() == null ? null : normalizeArtifact(card.getType().name()))
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(artifacts::add);
        }
        if (artifacts.isEmpty()) {
            artifactIntentResolver.resolveArtifacts(firstNonBlank(
                    session.getClarifiedInstruction(),
                    session.getRawInstruction(),
                    session.getPlanBlueprintSummary(),
                    session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal()))
                    .forEach(artifacts::add);
        }
        return List.copyOf(artifacts);
    }

    private String buildClarifiedInstruction(PlanTaskSession session, String rawInstruction) {
        List<String> answers = mergeUnique(defaultList(session.getClarificationAnswers()), extractSupplementalNotes(session));
        List<String> constraints = resolveConstraints(session);
        List<String> planRequirements = resolvePlanRequirements(session);
        StringBuilder builder = new StringBuilder(safe(resolveClarifiedInstructionBase(session, rawInstruction)));
        appendUniqueSection(builder, "补充说明：", answers);
        appendUniqueSection(builder, "当前计划要求：", planRequirements);
        appendUniqueSection(builder, "执行约束：", constraints);
        List<TermResolution> termResolutions = defaultList(session.getTermResolutions());
        if (!termResolutions.isEmpty()) {
            appendUniqueSection(builder, "术语消歧：", termResolutions.stream()
                    .map(item -> item.getTerm() + "=" + item.getResolvedMeaning())
                    .toList());
        }
        return builder.toString();
    }

    private String resolveExecutionInstruction(PlanTaskSession session) {
        String planBasedInstruction = buildPlanBasedInstruction(session);
        if (planBasedInstruction != null && !planBasedInstruction.isBlank()) {
            return planBasedInstruction;
        }
        return firstNonBlank(
                session.getRawInstruction(),
                session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal(),
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getTaskBrief(),
                session.getPlanBlueprintSummary()
        );
    }

    private String resolveClarifiedInstructionBase(PlanTaskSession session, String rawInstruction) {
        String planBasedInstruction = buildPlanBasedInstruction(session);
        if (planBasedInstruction != null && !planBasedInstruction.isBlank()) {
            return planBasedInstruction;
        }
        return firstNonBlank(
                session.getClarifiedInstruction(),
                session.getRawInstruction(),
                rawInstruction
        );
    }

    private String buildPlanBasedInstruction(PlanTaskSession session) {
        PlanBlueprint blueprint = session.getPlanBlueprint();
        if (blueprint == null) {
            return null;
        }
        List<String> planRequirements = resolvePlanRequirements(session);
        String brief = firstNonBlank(
                blueprint.getTaskBrief(),
                session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal(),
                session.getPlanBlueprintSummary()
        );
        if ((brief == null || brief.isBlank()) && planRequirements.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (brief != null && !brief.isBlank()) {
            builder.append(brief.trim());
        }
        if (!planRequirements.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("执行任务：").append(String.join("；", planRequirements));
        }
        return builder.toString().trim();
    }

    private List<String> extractSupplementalNotes(PlanTaskSession session) {
        String clarifiedInstruction = session.getClarifiedInstruction();
        if (clarifiedInstruction == null || clarifiedInstruction.isBlank()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (String line : clarifiedInstruction.split("\\R")) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("补充说明：")) {
                String note = trimmed.substring("补充说明：".length()).trim();
                if (!note.isBlank()) {
                    notes.add(note);
                }
            }
        }
        return notes;
    }

    private List<String> mergeUnique(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        defaultList(first).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(values::add);
        defaultList(second).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private String resolveDomainContext(PlanTaskSession session) {
        List<TermResolution> termResolutions = defaultList(session.getTermResolutions());
        if (!termResolutions.isEmpty()) {
            return termResolutions.stream()
                    .map(TermResolution::getResolvedMeaning)
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
        return firstNonBlank(session.getIndustry(), session.getProfession(), "general");
    }

    private List<String> resolvePlanRequirements(PlanTaskSession session) {
        List<UserPlanCard> cards = session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getPlanCards();
        if (cards == null || cards.isEmpty()) {
            cards = session.getPlanCards();
        }
        return defaultList(cards).stream()
                .filter(card -> card != null)
                .filter(card -> !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .map(card -> {
                    String title = firstNonBlank(card.getTitle(), "未命名步骤");
                    String description = firstNonBlank(card.getDescription(), card.getType() == null ? null : card.getType().name());
                    if (description == null) {
                        return title;
                    }
                    return title + " - " + description;
                })
                .distinct()
                .toList();
    }

    private void appendUniqueSection(StringBuilder builder, String label, List<String> values) {
        List<String> uniqueValues = defaultList(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .filter(value -> !builder.toString().contains(value))
                .toList();
        if (!uniqueValues.isEmpty()) {
            builder.append("\n").append(label).append(String.join("；", uniqueValues));
        }
    }

    private List<String> resolveConstraints(PlanTaskSession session) {
        if (session.getPlanBlueprint() != null && session.getPlanBlueprint().getConstraints() != null
                && !session.getPlanBlueprint().getConstraints().isEmpty()) {
            return session.getPlanBlueprint().getConstraints();
        }
        if (session.getIntentSnapshot() != null && session.getIntentSnapshot().getConstraints() != null) {
            return session.getIntentSnapshot().getConstraints();
        }
        return List.of();
    }

    private List<String> resolveContextRefs(WorkspaceContext sourceScope) {
        if (sourceScope == null) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        refs.addAll(defaultList(sourceScope.getDocRefs()));
        refs.addAll(defaultList(sourceScope.getAttachmentRefs()));
        refs.addAll(defaultList(sourceScope.getSelectedMessageIds()));
        if (sourceScope.getSourceArtifacts() != null) {
            refs.addAll(sourceScope.getSourceArtifacts().stream()
                    .map(artifact -> firstNonBlank(
                            artifact == null ? null : artifact.getUrl(),
                            artifact == null ? null : artifact.getArtifactId()))
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        }
        return refs;
    }

    private DiagramRequirement resolveDiagramRequirement(String rawInstruction, String clarifiedInstruction, List<String> constraints) {
        return DiagramRequirement.builder()
                .required(false)
                .types(List.of())
                .format("MERMAID")
                .placement("INLINE_DOC")
                .count(0)
                .build();
    }

    private String resolveTemplateStrategy(String rawInstruction, String clarifiedInstruction) {
        return "REPORT";
    }

    private String normalizeArtifact(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PPT", "SLIDE", "SLIDES", "PRESENTATION" -> "PPT";
            case "WHITEBOARD", "BOARD" -> "WHITEBOARD";
            case "DOC", "DOCUMENT" -> "DOC";
            case "SUMMARY" -> "SUMMARY";
            default -> upper;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
