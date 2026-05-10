package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

public interface DocumentEditIntentFacade {

    DocumentEditIntent resolve(String instruction);

    default DocumentEditIntent resolve(String instruction, WorkspaceContext workspaceContext) {
        return resolve(instruction);
    }
}
