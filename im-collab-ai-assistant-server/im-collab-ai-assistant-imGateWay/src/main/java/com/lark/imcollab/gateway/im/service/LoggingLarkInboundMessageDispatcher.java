package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingLarkInboundMessageDispatcher implements LarkInboundMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingLarkInboundMessageDispatcher.class);

    @Override
    public void dispatch(LarkInboundMessage message) {
        log.info("Scenario A inbound Lark message accepted: messageId={}, chatId={}, inputSource={}",
                message.messageId(), message.chatId(), message.inputSource());
    }
}
