package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;

public interface DocumentEditIntentFacade {

    DocumentEditIntent resolve(String instruction);
}
