package com.lark.imcollab.gateway.im.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageEventSubscriptionServiceTests {

    @Test
    void shouldStartSdkMessageReceiveSubscriptionForProfile() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);

        LarkEventSubscriptionStatus status = service.startMessageSubscription("profile-123", event -> {
        });

        assertThat(status.profileName()).isEqualTo("profile-123");
        assertThat(status.running()).isTrue();
        assertThat(factory.startCount).isEqualTo(1);
    }

    @Test
    void shouldStartSdkMessageReceiveSubscriptionForDefaultProfile() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);

        LarkEventSubscriptionStatus status = service.startMessageSubscription(null, event -> {
        });

        assertThat(status.profileName()).isEqualTo("default");
        assertThat(status.running()).isTrue();
        assertThat(factory.startCount).isEqualTo(1);
    }

    @Test
    void shouldReuseRunningSubscriptionForSameProfile() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);

        service.startMessageSubscription("profile-123", event -> {
        });
        LarkEventSubscriptionStatus status = service.startMessageSubscription("profile-123", event -> {
        });

        assertThat(status.profileName()).isEqualTo("profile-123");
        assertThat(status.running()).isTrue();
        assertThat(factory.startCount).isEqualTo(1);
    }

    @Test
    void shouldDeliverSdkEventsToConsumer() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);
        List<LarkMessageEvent> events = new ArrayList<>();
        service.startMessageSubscription("profile-123", events::add);

        factory.connection.emit(new LarkMessageEvent(
                "evt-1",
                "om_1",
                "oc_p2p",
                "p2p",
                "text",
                "生成周报",
                "ou_1",
                "1773491924409",
                false
        ));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).messageId()).isEqualTo("om_1");
        assertThat(events.get(0).content()).isEqualTo("生成周报");
    }

    @Test
    void shouldStopExistingSubscription() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);
        service.startMessageSubscription("profile-123", event -> {
        });

        LarkEventSubscriptionStatus status = service.stopMessageSubscription("profile-123");

        assertThat(factory.connection.stopped).isTrue();
        assertThat(status.running()).isFalse();
    }

    @Test
    void shouldStopAllSubscriptions() {
        StubConnectionFactory factory = new StubConnectionFactory();
        LarkMessageEventSubscriptionService service = new LarkMessageEventSubscriptionService(factory);
        service.startMessageSubscription("profile-123", event -> {
        });
        service.startMessageSubscription("profile-456", event -> {
        });

        service.stopAllMessageSubscriptions();

        assertThat(factory.connections).allSatisfy(connection -> assertThat(connection.stopped).isTrue());
        assertThat(service.getMessageSubscriptionStatus("profile-123").running()).isFalse();
        assertThat(service.getMessageSubscriptionStatus("profile-456").running()).isFalse();
    }

    private static final class StubConnectionFactory implements LarkMessageEventConnectionFactory {

        private final List<StubConnection> connections = new ArrayList<>();
        private StubConnection connection;
        private int startCount;

        @Override
        public LarkMessageEventConnection start(Consumer<LarkMessageEvent> messageConsumer) {
            startCount++;
            connection = new StubConnection(messageConsumer);
            connections.add(connection);
            return connection;
        }
    }

    private static final class StubConnection implements LarkMessageEventConnection {

        private final Consumer<LarkMessageEvent> consumer;
        private boolean stopped;

        private StubConnection(Consumer<LarkMessageEvent> consumer) {
            this.consumer = consumer;
        }

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        private void emit(LarkMessageEvent event) {
            consumer.accept(event);
        }
    }
}
