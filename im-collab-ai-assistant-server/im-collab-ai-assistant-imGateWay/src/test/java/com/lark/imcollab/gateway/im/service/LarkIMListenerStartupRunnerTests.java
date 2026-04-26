package com.lark.imcollab.gateway.im.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerStartupRunnerTests {

    @Test
    void shouldStartDefaultListenerWhenAutoStartEnabled() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        properties.setDefaultProfileName("imcollab-demo-app");
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(properties, listenerService);

        runner.run(null);

        assertThat(listenerService.startedProfileName).isEqualTo("imcollab-demo-app");
    }

    @Test
    void shouldUseLarkCliDefaultProfileWhenConfiguredProfileIsBlank() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(properties, listenerService);

        runner.run(null);

        assertThat(listenerService.startedProfileName).isNull();
    }

    @Test
    void shouldNotStartDefaultListenerWhenAutoStartDisabled() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(false);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(properties, listenerService);

        runner.run(null);

        assertThat(listenerService.startCalled).isFalse();
    }

    private static final class RecordingListenerService extends LarkIMListenerService {

        private boolean startCalled;
        private String startedProfileName;

        RecordingListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse startDefault(String profileName) {
            startCalled = true;
            startedProfileName = profileName;
            return new LarkIMListenerStatusResponse(profileName == null ? "default" : profileName, true, "running",
                    null, null);
        }
    }
}
