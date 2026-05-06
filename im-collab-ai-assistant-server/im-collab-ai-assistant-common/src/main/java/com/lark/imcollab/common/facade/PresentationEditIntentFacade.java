package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PresentationEditIntent;

public interface PresentationEditIntentFacade {

    PresentationEditIntent resolve(String instruction);
}
