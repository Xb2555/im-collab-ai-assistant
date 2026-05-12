package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;

import java.util.EnumSet;
import java.util.Set;

record RoutingEvidence(
        String rawInput,
        String normalizedInput,
        boolean explicitFreshTask,
        boolean currentTaskReference,
        boolean continuationSignal,
        boolean concreteArtifactEditAnchor,
        boolean newCompletedDeliverableRequest,
        boolean ambiguousMaterialOrganizationRequest,
        Set<ArtifactTypeEnum> mentionedDeliverables
) {

    boolean mentionsDeliverable(ArtifactTypeEnum artifactType) {
        return artifactType != null
                && mentionedDeliverables != null
                && mentionedDeliverables.contains(artifactType);
    }

    static RoutingEvidence empty(String rawInput) {
        return new RoutingEvidence(
                rawInput,
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                EnumSet.noneOf(ArtifactTypeEnum.class)
        );
    }
}
