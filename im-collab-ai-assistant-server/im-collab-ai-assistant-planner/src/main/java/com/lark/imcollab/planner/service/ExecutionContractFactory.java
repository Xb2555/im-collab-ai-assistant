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

    private final ArtifactIntentResolver artifactIntentResolver;

    public ExecutionContractFactory() {
        this(new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "DOC"));
    }

    @Autowired
    public ExecutionContractFactory(ArtifactIntentResolver artifactIntentResolver) {
        this.artifactIntentResolver = artifactIntentResolver;
    }

    public ExecutionContract build(PlanTaskSession session) {
        String rawInstruction = firstNonBlank(
                session.getRawInstruction(),
                session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal(),
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getTaskBrief(),
                session.getPlanBlueprintSummary()
        );
        List<String> requestedArtifacts = deriveRequestedArtifacts(session);
        List<String> allowedArtifacts = requestedArtifacts.isEmpty() ? List.of("DOC") : requestedArtifacts;
        String clarifiedInstruction = buildClarifiedInstruction(session, rawInstruction);
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
                .constraints(resolveConstraints(session))
                .sourceScope(sourceScope)
                .contextRefs(resolveContextRefs(sourceScope))
                .domainContext(resolveDomainContext(session))
                .termResolutions(defaultList(session.getTermResolutions()))
                .templateStrategy(resolveTemplateStrategy(rawInstruction, clarifiedInstruction))
                .diagramRequirement(resolveDiagramRequirement(rawInstruction, clarifiedInstruction, resolveConstraints(session)))
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
        List<String> answers = defaultList(session.getClarificationAnswers());
        List<String> constraints = resolveConstraints(session);
        List<String> planRequirements = resolvePlanRequirements(session);
        StringBuilder builder = new StringBuilder(safe(firstNonBlank(
                rawInstruction,
                session.getRawInstruction(),
                session.getClarifiedInstruction()
        )));
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
        return refs;
    }

    private DiagramRequirement resolveDiagramRequirement(String rawInstruction, String clarifiedInstruction, List<String> constraints) {
        String text = (safe(rawInstruction) + "\n" + safe(clarifiedInstruction) + "\n" + String.join("\n", defaultList(constraints)))
                .toLowerCase(Locale.ROOT);
        boolean requiresDiagram = text.contains("mermaid")
                || text.contains("流程图")
                || text.contains("时序图")
                || text.contains("状态图")
                || text.contains("架构图")
                || text.contains("数据流图");
        if (!requiresDiagram) {
            return DiagramRequirement.builder()
                    .required(false)
                    .types(List.of())
                    .format("MERMAID")
                    .placement("INLINE_DOC")
                    .count(0)
                    .build();
        }
        Set<String> types = new LinkedHashSet<>();
        if (text.contains("时序")) {
            types.add("SEQUENCE");
        }
        if (text.contains("状态")) {
            types.add("STATE");
        }
        if (text.contains("上下文") || text.contains("context")) {
            types.add("CONTEXT");
        }
        if (text.contains("数据流") || text.contains("流程")) {
            types.add("DATA_FLOW");
        }
        if (types.isEmpty()) {
            types.add("DATA_FLOW");
        }
        return DiagramRequirement.builder()
                .required(true)
                .types(List.copyOf(types))
                .format("MERMAID")
                .placement("INLINE_DOC")
                .count(1)
                .build();
    }

    private String resolveTemplateStrategy(String rawInstruction, String clarifiedInstruction) {
        String text = (safe(rawInstruction) + "\n" + safe(clarifiedInstruction)).toLowerCase(Locale.ROOT);
        if (text.contains("架构评审") || text.contains("architecture review")) {
            return "ARCHITECTURE_REVIEW";
        }
        if (text.contains("技术介绍") || text.contains("spring ai") || text.contains("介绍")) {
            return "TECHNICAL_INTRODUCTION";
        }
        if (text.contains("架构") || text.contains("harness")) {
            return "TECHNICAL_ARCHITECTURE";
        }
        if (text.contains("需求") || text.contains("prd")) {
            return "REQUIREMENTS";
        }
        if (text.contains("会议") || text.contains("纪要")) {
            return "MEETING_SUMMARY";
        }
        return "REPORT";
    }

    private String normalizeArtifact(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("PPT") || upper.contains("SLIDE")) {
            return "PPT";
        }
        if (upper.contains("WHITEBOARD") || upper.contains("BOARD")) {
            return "WHITEBOARD";
        }
        if (upper.contains("DOC")) {
            return "DOC";
        }
        if (upper.contains(PlanCardTypeEnum.SUMMARY.name())) {
            return "SUMMARY";
        }
        return upper;
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
