package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

public interface PresentationEditIntentFacade {

    PresentationEditIntent resolve(String instruction);

    default PresentationEditIntent resolve(String instruction, WorkspaceContext workspaceContext) {
        return resolve(instruction);
    }
}
