package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;

public interface LarkInboundMessageDispatcher {

    void dispatch(LarkInboundMessage message);
}
