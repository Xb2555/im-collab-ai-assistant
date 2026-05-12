package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;

import java.util.EnumMap;
import java.util.Map;

public record RoutingEvidence(
        String rawInput,
        String normalizedInput,
        RoutingTuning tuning,
        int freshTaskScore,
        int currentTaskReferenceScore,
        int continuationIntentScore,
        int artifactEditScore,
        int newDeliverableScore,
        int ambiguousMaterialOrganizationScore,
        Map<ArtifactTypeEnum, Integer> deliverableMentionScores,
        Map<ArtifactTypeEnum, Integer> sourceContextScores
) {

    public boolean explicitFreshTask() {
        return freshTaskLevel() == SignalLevel.HIGH;
    }

    public boolean currentTaskReference() {
        return currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public boolean continuationSignal() {
        return continuationIntentLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public boolean concreteArtifactEditAnchor() {
        return artifactEditLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public boolean newCompletedDeliverableRequest() {
        return newDeliverableLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public boolean ambiguousMaterialOrganizationRequest() {
        return ambiguousMaterialOrganizationLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public SignalLevel freshTaskLevel() {
        return tuning.levelOf(freshTaskScore);
    }

    public SignalLevel currentTaskReferenceLevel() {
        return tuning.levelOf(currentTaskReferenceScore);
    }

    public SignalLevel continuationIntentLevel() {
        return tuning.levelOf(continuationIntentScore);
    }

    public SignalLevel artifactEditLevel() {
        return tuning.levelOf(artifactEditScore);
    }

    public SignalLevel newDeliverableLevel() {
        return tuning.levelOf(newDeliverableScore);
    }

    public SignalLevel ambiguousMaterialOrganizationLevel() {
        return tuning.levelOf(ambiguousMaterialOrganizationScore);
    }

    public int deliverableMentionScore(ArtifactTypeEnum artifactType) {
        if (artifactType == null || deliverableMentionScores == null) {
            return 0;
        }
        return deliverableMentionScores.getOrDefault(artifactType, 0);
    }

    public SignalLevel deliverableMentionLevel(ArtifactTypeEnum artifactType) {
        return tuning.levelOf(deliverableMentionScore(artifactType));
    }

    public int sourceContextScore(ArtifactTypeEnum artifactType) {
        if (artifactType == null || sourceContextScores == null) {
            return 0;
        }
        return sourceContextScores.getOrDefault(artifactType, 0);
    }

    public SignalLevel sourceContextLevel(ArtifactTypeEnum artifactType) {
        return tuning.levelOf(sourceContextScore(artifactType));
    }

    public boolean mentionsDeliverable(ArtifactTypeEnum artifactType) {
        return deliverableMentionScore(artifactType) > 0;
    }

    static RoutingEvidence empty(String rawInput) {
        return empty(rawInput, RoutingTuning.defaults());
    }

    static RoutingEvidence empty(String rawInput, RoutingTuning tuning) {
        return new RoutingEvidence(
                rawInput,
                "",
                tuning,
                0,
                0,
                0,
                0,
                0,
                0,
                new EnumMap<>(ArtifactTypeEnum.class),
                new EnumMap<>(ArtifactTypeEnum.class)
        );
    }
}
