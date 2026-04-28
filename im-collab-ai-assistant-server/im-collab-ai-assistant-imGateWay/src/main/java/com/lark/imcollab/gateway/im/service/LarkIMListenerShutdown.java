package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class LarkIMListenerShutdown {

    private final LarkMessageEventSubscriptionService subscriptionService;

    public LarkIMListenerShutdown(LarkMessageEventSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PreDestroy
    public void destroy() {
        subscriptionService.stopAllMessageSubscriptions();
    }
}
