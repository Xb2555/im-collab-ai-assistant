package com.lark.imcollab.gateway.im.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerStartupRunnerTests {

    @Test
    void shouldStartDefaultListenerWhenAutoStartEnabled() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService
        );

        runner.run(null);

        assertThat(listenerService.startCalled).isTrue();
    }

    @Test
    void shouldNotStartDefaultListenerWhenAutoStartDisabled() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(false);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService
        );

        runner.run(null);

        assertThat(listenerService.startCalled).isFalse();
    }

    private static final class RecordingListenerService extends LarkIMListenerService {

        private boolean startCalled;

        RecordingListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse start() {
            startCalled = true;
            return new LarkIMListenerStatusResponse(true, "running", null, null);
        }
    }
}
