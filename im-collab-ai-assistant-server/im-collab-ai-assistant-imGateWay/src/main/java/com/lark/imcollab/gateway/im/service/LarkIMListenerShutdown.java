package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.event.LarkMessageEventSubscriptionTool;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class LarkIMListenerShutdown {

    private final LarkMessageEventSubscriptionTool subscriptionTool;

    public LarkIMListenerShutdown(LarkMessageEventSubscriptionTool subscriptionTool) {
        this.subscriptionTool = subscriptionTool;
    }

    @PreDestroy
    public void destroy() {
        subscriptionTool.stopAllMessageSubscriptions();
    }
}
