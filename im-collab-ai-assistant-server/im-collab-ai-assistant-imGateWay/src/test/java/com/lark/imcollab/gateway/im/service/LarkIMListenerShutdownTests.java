package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.event.LarkMessageEventSubscriptionTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerShutdownTests {

    @Test
    void shouldStopAllSubscriptionsOnShutdown() {
        RecordingSubscriptionTool subscriptionTool = new RecordingSubscriptionTool();
        LarkIMListenerShutdown shutdown = new LarkIMListenerShutdown(subscriptionTool);

        shutdown.destroy();

        assertThat(subscriptionTool.stopAllCalled).isTrue();
    }

    private static final class RecordingSubscriptionTool extends LarkMessageEventSubscriptionTool {

        private boolean stopAllCalled;

        RecordingSubscriptionTool() {
            super(null, null, null);
        }

        @Override
        public void stopAllMessageSubscriptions() {
            stopAllCalled = true;
        }
    }
}
