package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;

import java.util.Optional;

public interface FollowUpArtifactContextResolver {

    Optional<ArtifactTypeEnum> resolvePreferredArtifactType(
            PlanTaskSession previousSession,
            String userInput,
            WorkspaceContext workspaceContext
    );
}
