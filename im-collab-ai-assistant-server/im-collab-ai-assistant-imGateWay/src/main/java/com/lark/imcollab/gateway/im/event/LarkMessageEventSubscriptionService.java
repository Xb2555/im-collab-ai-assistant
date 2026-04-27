package com.lark.imcollab.gateway.im.event;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class LarkMessageEventSubscriptionService {

    private static final String DEFAULT_PROFILE_NAME = "default";

    private final LarkMessageEventConnectionFactory connectionFactory;
    private final Map<String, SubscriptionState> subscriptions = new ConcurrentHashMap<>();

    public LarkMessageEventSubscriptionService(LarkMessageEventConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public LarkEventSubscriptionStatus startMessageSubscription(
            String profileName,
            Consumer<LarkMessageEvent> messageConsumer
    ) {
        String normalizedProfileName = normalizeProfileName(profileName);
        SubscriptionState existing = subscriptions.get(normalizedProfileName);
        if (existing != null && existing.isRunning()) {
            return existing.toStatus();
        }

        SubscriptionState state = new SubscriptionState(normalizedProfileName, Instant.now().toString());
        try {
            LarkMessageEventConnection connection = connectionFactory.start(event -> {
                try {
                    messageConsumer.accept(event);
                } catch (RuntimeException exception) {
                    state.lastError = exception.getMessage();
                }
            });
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

    private static final class SubscriptionState {

        private final String profileName;
        private final String startedAt;
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
}
