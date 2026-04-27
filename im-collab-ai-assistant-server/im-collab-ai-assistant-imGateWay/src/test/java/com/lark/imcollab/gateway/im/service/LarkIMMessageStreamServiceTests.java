package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMMessageStreamServiceTests {

    @Test
    void shouldPublishMessageToMatchingChatSubscribers() {
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(null, null);
        RecordingEmitter matchingEmitter = new RecordingEmitter();
        RecordingEmitter otherEmitter = new RecordingEmitter();
        service.register("oc_group", matchingEmitter);
        service.register("oc_other", otherEmitter);

        service.publish(new LarkMessageEvent(
                "evt-1",
                "om_1",
                "oc_group",
                "group",
                "text",
                "hello",
                "ou_1",
                "1773491924409",
                false
        ));

        assertThat(matchingEmitter.sendCount).isEqualTo(1);
        assertThat(otherEmitter.sendCount).isZero();
    }

    @Test
    void shouldPublishMentionMessageToMatchingChatSubscribers() {
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(null, null);
        RecordingEmitter emitter = new RecordingEmitter();
        service.register("oc_group", emitter);

        service.publish(new LarkMessageEvent(
                "evt-mention",
                "om_mention",
                "oc_group",
                "group",
                "text",
                "@bot hello",
                "ou_1",
                "1773491924411",
                true
        ));

        assertThat(emitter.sendCount).isEqualTo(1);
    }

    @Test
    void shouldPublishLocalBotReplyToMatchingChatSubscribers() {
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(null, null);
        RecordingEmitter emitter = new RecordingEmitter();
        service.register("oc_group", emitter);

        service.publishBotReply(new LarkMessageEvent(
                "evt-1",
                "om_1",
                "oc_group",
                "group",
                "text",
                "生成方案",
                "ou_1",
                "1773491924409",
                true
        ), "任务已收到，正在处理");

        assertThat(emitter.sendCount).isEqualTo(1);
    }

    @Test
    void shouldSubscribeLarkEventsWithResolvedProfileName() {
        RecordingSubscriptionService subscriptionService = new RecordingSubscriptionService();
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(
                new AuthenticatedOAuthService(),
                subscriptionService,
                new StubProfileResolver("imcollab-demo-app")
        );

        service.subscribe("Bearer business-token", "oc_group");

        assertThat(subscriptionService.startedProfileName).isEqualTo("imcollab-demo-app");
    }

    @Test
    void shouldRemoveSubscriberWhenEmitterCompletes() {
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(null, null);
        RecordingEmitter emitter = new RecordingEmitter();
        service.register("oc_group", emitter);

        emitter.triggerCompletion();

        assertThat(service.subscriberCount("oc_group")).isZero();
    }

    @Test
    void shouldRemoveSubscriberWhenSendFails() {
        LarkIMMessageStreamService service = new LarkIMMessageStreamService(null, null);
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failOnSend = true;
        service.register("oc_group", emitter);

        service.publish(new LarkMessageEvent(
                "evt-2",
                "om_2",
                "oc_group",
                "group",
                "text",
                "hello",
                "ou_2",
                "1773491924410",
                false
        ));

        assertThat(service.subscriberCount("oc_group")).isZero();
    }

    private static final class RecordingEmitter extends SseEmitter {

        private int sendCount;
        private boolean failOnSend;
        private Runnable completionCallback;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("send failed");
            }
            sendCount++;
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completionCallback = callback;
        }

        private void triggerCompletion() {
            completionCallback.run();
        }
    }

    private static final class AuthenticatedOAuthService extends LarkOAuthService {

        private AuthenticatedOAuthService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public Optional<LarkAuthenticatedSession> resolveAuthenticatedSessionByBusinessToken(String businessToken) {
            return Optional.of(new LarkAuthenticatedSession(
                    "user-access-token",
                    new LarkOAuthUserResponse("ou_1", null, null, null, "User One", null)
            ));
        }
    }

    private static final class RecordingSubscriptionService extends LarkMessageEventSubscriptionService {

        private String startedProfileName;

        private RecordingSubscriptionService() {
            super(null);
        }

        @Override
        public LarkEventSubscriptionStatus getMessageSubscriptionStatus(String profileName) {
            return new LarkEventSubscriptionStatus(profileName == null ? "default" : profileName, false, "stopped",
                    null, null);
        }

        @Override
        public LarkEventSubscriptionStatus startMessageSubscription(
                String profileName,
                String consumerId,
                int priority,
                Consumer<LarkMessageEvent> messageConsumer
        ) {
            this.startedProfileName = profileName;
            return new LarkEventSubscriptionStatus(profileName == null ? "default" : profileName, true, "running",
                    null, null);
        }
    }

    private static final class StubProfileResolver extends LarkCliProfileResolver {

        private final String profileName;

        private StubProfileResolver(String profileName) {
            super(new LarkAppProperties(), null);
            this.profileName = profileName;
        }

        @Override
        public String resolveConfiguredAppProfileName() {
            return profileName;
        }
    }
}
