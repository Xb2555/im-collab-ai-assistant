package com.lark.imcollab.gateway.im.event;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class LarkMessageEventSubscriptionService {

    public static final int FRONTEND_STREAM_CONSUMER_PRIORITY = 0;
    public static final int DEFAULT_CONSUMER_PRIORITY = 100;

    private final LarkMessageEventConnectionFactory connectionFactory;
    private volatile SubscriptionState subscription;

    public LarkMessageEventSubscriptionService(LarkMessageEventConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public synchronized LarkEventSubscriptionStatus startMessageSubscription(Consumer<LarkMessageEvent> messageConsumer) {
        return startMessageSubscription(consumerKey(messageConsumer), messageConsumer);
    }

    public synchronized LarkEventSubscriptionStatus startMessageSubscription(
            String consumerId,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        return startMessageSubscription(consumerId, DEFAULT_CONSUMER_PRIORITY, messageConsumer);
    }

    public synchronized LarkEventSubscriptionStatus startMessageSubscription(
            String consumerId,
            int priority,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        String normalizedConsumerId = normalizeConsumerId(consumerId, messageConsumer);
        SubscriptionState existing = subscription;
        if (existing != null && existing.isRunning()) {
            existing.putConsumer(normalizedConsumerId, priority, messageConsumer);
            return existing.toStatus();
        }

        SubscriptionState state = new SubscriptionState(Instant.now().toString());
        state.putConsumer(normalizedConsumerId, priority, messageConsumer);
        try {
            LarkMessageEventConnection connection = connectionFactory.start(state::dispatch);
            state.connection = connection;
            subscription = state;
            return state.toStatus();
        } catch (RuntimeException exception) {
            state.lastError = exception.getMessage();
            throw new IllegalStateException("Failed to start Lark SDK message event subscription", exception);
        }
    }

    public synchronized LarkEventSubscriptionStatus stopMessageSubscription() {
        SubscriptionState state = subscription;
        subscription = null;
        if (state == null) {
            return new LarkEventSubscriptionStatus(false, "stopped", null, null);
        }
        state.stop();
        return state.toStatus();
    }

    public LarkEventSubscriptionStatus getMessageSubscriptionStatus() {
        SubscriptionState state = subscription;
        if (state == null) {
            return new LarkEventSubscriptionStatus(false, "stopped", null, null);
        }
        return state.toStatus();
    }

    public synchronized void stopAllMessageSubscriptions() {
        if (subscription != null) {
            subscription.stop();
            subscription = null;
        }
    }

    private String normalizeConsumerId(String consumerId, Consumer<LarkMessageEvent> messageConsumer) {
        if (consumerId == null || consumerId.isBlank()) {
            return consumerKey(messageConsumer);
        }
        return consumerId.trim();
    }

    private String consumerKey(Consumer<LarkMessageEvent> messageConsumer) {
        return "consumer-" + System.identityHashCode(messageConsumer);
    }

    private static final class SubscriptionState {

        private final String startedAt;
        private final ConcurrentHashMap<String, ConsumerRegistration> consumers = new ConcurrentHashMap<>();
        private LarkMessageEventConnection connection;
        private volatile String lastError;

        private SubscriptionState(String startedAt) {
            this.startedAt = startedAt;
        }

        private boolean isRunning() {
            return connection != null && connection.isRunning();
        }

        private void stop() {
            if (connection != null) {
                connection.stop();
            }
        }

        private void putConsumer(String consumerId, int priority, Consumer<LarkMessageEvent> consumer) {
            consumers.put(consumerId, new ConsumerRegistration(consumerId, priority, consumer));
        }

        private void dispatch(LarkMessageEvent event) {
            List<ConsumerRegistration> orderedConsumers = consumers.values().stream()
                    .sorted(Comparator.comparingInt(ConsumerRegistration::priority)
                            .thenComparing(ConsumerRegistration::consumerId))
                    .toList();
            for (ConsumerRegistration registration : orderedConsumers) {
                try {
                    registration.consumer().accept(event);
                } catch (RuntimeException exception) {
                    lastError = exception.getMessage();
                }
            }
        }

        private LarkEventSubscriptionStatus toStatus() {
            boolean running = isRunning();
            return new LarkEventSubscriptionStatus(
                    running,
                    running ? "running" : "stopped",
                    startedAt,
                    lastError
            );
        }
    }

    private record ConsumerRegistration(
            String consumerId,
            int priority,
            Consumer<LarkMessageEvent> consumer
    ) {
    }
}
