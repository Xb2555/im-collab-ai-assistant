package com.lark.imcollab.gateway.im.event;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class LarkMessageEventSubscriptionService {

    private static final String DEFAULT_PROFILE_NAME = "default";
    public static final int FRONTEND_STREAM_CONSUMER_PRIORITY = 0;
    public static final int DEFAULT_CONSUMER_PRIORITY = 100;

    private final LarkMessageEventConnectionFactory connectionFactory;
    private final Map<String, SubscriptionState> subscriptions = new ConcurrentHashMap<>();

    public LarkMessageEventSubscriptionService(LarkMessageEventConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public LarkEventSubscriptionStatus startMessageSubscription(
            String profileName,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        return startMessageSubscription(profileName, consumerKey(messageConsumer), messageConsumer);
    }

    public LarkEventSubscriptionStatus startMessageSubscription(
            String profileName,
            String consumerId,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        return startMessageSubscription(profileName, consumerId, DEFAULT_CONSUMER_PRIORITY, messageConsumer);
    }

    public LarkEventSubscriptionStatus startMessageSubscription(
            String profileName,
            String consumerId,
            int priority,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        String normalizedProfileName = normalizeProfileName(profileName);
        String normalizedConsumerId = normalizeConsumerId(consumerId, messageConsumer);
        SubscriptionState existing = subscriptions.get(normalizedProfileName);
        if (existing != null && existing.isRunning()) {
            existing.putConsumer(normalizedConsumerId, priority, messageConsumer);
            return existing.toStatus();
        }

        SubscriptionState state = new SubscriptionState(normalizedProfileName, Instant.now().toString());
        state.putConsumer(normalizedConsumerId, priority, messageConsumer);
        try {
            LarkMessageEventConnection connection = connectionFactory.start(state::dispatch);
            state.connection = connection;
            subscriptions.put(normalizedProfileName, state);
            return state.toStatus();
        } catch (RuntimeException exception) {
            state.lastError = exception.getMessage();
            throw new IllegalStateException("Failed to start Lark SDK message event subscription", exception);
        }
    }

    public LarkEventSubscriptionStatus stopMessageSubscription(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        SubscriptionState state = subscriptions.remove(normalizedProfileName);
        if (state == null) {
            return new LarkEventSubscriptionStatus(normalizedProfileName, false, "stopped", null, null);
        }
        state.stop();
        return state.toStatus();
    }

    public LarkEventSubscriptionStatus getMessageSubscriptionStatus(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        SubscriptionState state = subscriptions.get(normalizedProfileName);
        if (state == null) {
            return new LarkEventSubscriptionStatus(normalizedProfileName, false, "stopped", null, null);
        }
        return state.toStatus();
    }

    public void stopAllMessageSubscriptions() {
        subscriptions.values().forEach(SubscriptionState::stop);
        subscriptions.clear();
    }

    private String normalizeProfileName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return DEFAULT_PROFILE_NAME;
        }
        return profileName.trim();
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

        private final String profileName;
        private final String startedAt;
        private final Map<String, ConsumerRegistration> consumers = new ConcurrentHashMap<>();
        private LarkMessageEventConnection connection;
        private volatile String lastError;

        private SubscriptionState(String profileName, String startedAt) {
            this.profileName = profileName;
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
                    profileName,
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
