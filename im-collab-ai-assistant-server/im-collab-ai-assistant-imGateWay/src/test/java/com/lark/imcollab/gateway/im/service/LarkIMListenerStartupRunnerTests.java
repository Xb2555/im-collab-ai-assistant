package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerStartupRunnerTests {

    @Test
    void shouldStartConfiguredLarkCliProfileWhenItExists() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        LarkCliProperties cliProperties = new LarkCliProperties();
        cliProperties.setProfileName("imcollab-demo-app");
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService,
                cliProperties,
                new StubAuthorizationTool(List.of(profile("imcollab-demo-app")))
        );

        runner.run(null);

        assertThat(listenerService.startedProfileName).isEqualTo("imcollab-demo-app");
    }

    @Test
    void shouldUseLarkCliDefaultProfileWhenConfiguredLarkCliProfileIsBlank() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService,
                new LarkCliProperties(),
                new StubAuthorizationTool(List.of(profile("imcollab-demo-app")))
        );

        runner.run(null);

        assertThat(listenerService.startedProfileName).isNull();
    }

    @Test
    void shouldUseLarkCliDefaultProfileWhenConfiguredLarkCliProfileDoesNotExist() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(true);
        LarkCliProperties cliProperties = new LarkCliProperties();
        cliProperties.setProfileName("missing-profile");
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService,
                cliProperties,
                new StubAuthorizationTool(List.of(profile("imcollab-demo-app")))
        );

        runner.run(null);

        assertThat(listenerService.startedProfileName).isNull();
    }

    @Test
    void shouldNotStartDefaultListenerWhenAutoStartDisabled() {
        RecordingListenerService listenerService = new RecordingListenerService();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setAutoStartEnabled(false);
        LarkIMListenerStartupRunner runner = new LarkIMListenerStartupRunner(
                properties,
                listenerService,
                new LarkCliProperties(),
                new StubAuthorizationTool(List.of(profile("imcollab-demo-app")))
        );

        runner.run(null);

        assertThat(listenerService.startCalled).isFalse();
    }

    private static AdminAuthorizationProfile profile(String profileName) {
        return new AdminAuthorizationProfile(profileName, "cli_test", "feishu", false, null);
    }

    private static final class StubAuthorizationTool extends LarkAdminAuthorizationTool {

        private final List<AdminAuthorizationProfile> profiles;

        StubAuthorizationTool(List<AdminAuthorizationProfile> profiles) {
            super(null, null);
            this.profiles = profiles;
        }

        @Override
        public List<AdminAuthorizationProfile> listAuthorizationProfiles() {
            return profiles;
        }
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
