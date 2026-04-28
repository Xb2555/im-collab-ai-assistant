package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;

public interface LarkInboundMessageDispatcher {

    PlanTaskSession dispatch(LarkInboundMessage message);
}
