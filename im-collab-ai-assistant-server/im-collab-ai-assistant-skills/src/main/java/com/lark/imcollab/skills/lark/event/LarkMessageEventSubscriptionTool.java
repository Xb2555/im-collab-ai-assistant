package com.lark.imcollab.skills.lark.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliStreamHandle;
import com.lark.imcollab.skills.framework.cli.CliStreamListener;
import com.lark.imcollab.skills.framework.cli.CliStreamingCommandExecutor;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class LarkMessageEventSubscriptionTool {

    private static final String MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";
    private static final String DEFAULT_PROFILE_NAME = "default";

    private final CliStreamingCommandExecutor streamingCommandExecutor;
    private final LarkCliProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, SubscriptionState> subscriptions = new ConcurrentHashMap<>();

    public LarkMessageEventSubscriptionTool(
            CliStreamingCommandExecutor streamingCommandExecutor,
            LarkCliProperties properties,
            ObjectMapper objectMapper
    ) {
        this.streamingCommandExecutor = streamingCommandExecutor;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            CliStreamHandle handle = streamingCommandExecutor.start(
                    new CliCommand(
                            properties.getExecutable(),
                            buildSubscribeArgs(normalizedProfileName),
                            normalizeWorkingDirectory(properties.getWorkingDirectory()),
                            null,
                            0
                    ),
                    new CliStreamListener() {
                        @Override
                        public void onLine(String line) {
                            parseMessageEvent(line).ifPresent(event -> {
                                try {
                                    messageConsumer.accept(event);
                                } catch (RuntimeException exception) {
                                    state.lastError = exception.getMessage();
                                }
                            });
                        }

                        @Override
                        public void onError(Exception exception) {
                            state.lastError = exception.getMessage();
                        }

                        @Override
                        public void onExit(int exitCode) {
                            if (exitCode != 0 && exitCode != 143) {
                                state.lastError = "lark-cli event subscription exited with code " + exitCode;
                            }
                        }
                    }
            );
            state.handle = handle;
            subscriptions.put(normalizedProfileName, state);
            return state.toStatus();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Lark message event subscription", exception);
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

    private List<String> buildSubscribeArgs(String profileName) {
        if (DEFAULT_PROFILE_NAME.equals(profileName)) {
            return List.of(
                    "event", "+subscribe",
                    "--event-types", MESSAGE_RECEIVE_EVENT,
                    "--as", "bot",
                    "--quiet"
            );
        }
        return List.of(
                "--profile", profileName,
                "event", "+subscribe",
                "--event-types", MESSAGE_RECEIVE_EVENT,
                "--as", "bot",
                "--quiet"
        );
    }

    private Optional<LarkMessageEvent> parseMessageEvent(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(line.trim());
            String eventType = firstText(root.path("header").path("event_type"), root.path("type"));
            if (!MESSAGE_RECEIVE_EVENT.equals(eventType)) {
                return Optional.empty();
            }

            JsonNode message = root.path("event").path("message");
            if (message.isMissingNode()) {
                message = root;
            }

            String messageId = firstText(message.path("message_id"), root.path("message_id"));
            String chatId = firstText(message.path("chat_id"), root.path("chat_id"));
            String chatType = firstText(message.path("chat_type"), root.path("chat_type"));
            String messageType = firstText(message.path("message_type"), root.path("message_type"));
            String createTime = firstText(message.path("create_time"), root.path("timestamp"),
                    root.path("header").path("create_time"));
            String senderOpenId = firstText(
                    root.path("event").path("sender").path("sender_id").path("open_id"),
                    root.path("sender_id")
            );
            String content = parseMessageContent(firstNode(message.path("content"), root.path("content")));
            boolean mentionDetected = hasMentions(message.path("mentions"), root.path("mentions"))
                    || containsAtMarkup(content)
                    || containsAtMarkup(firstText(message.path("content"), root.path("content")));

            if (messageId == null || messageId.isBlank()) {
                return Optional.empty();
            }
            if (!isPrivateChat(chatType) && !mentionDetected) {
                return Optional.empty();
            }
            return Optional.of(new LarkMessageEvent(
                    firstText(root.path("header").path("event_id"), root.path("id")),
                    messageId,
                    chatId,
                    chatType,
                    messageType,
                    content,
                    senderOpenId,
                    createTime,
                    mentionDetected
            ));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private boolean isPrivateChat(String chatType) {
        return "p2p".equalsIgnoreCase(chatType);
    }

    private boolean hasMentions(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray() && !node.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAtMarkup(String value) {
        return value != null && value.contains("<at ");
    }

    private String parseMessageContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isObject()) {
            return firstText(contentNode.path("text"), contentNode.path("content"));
        }
        String rawContent = contentNode.asText();
        if (rawContent == null || rawContent.isBlank()) {
            return rawContent;
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawContent);
            String text = firstText(parsed.path("text"), parsed.path("content"));
            return text == null ? rawContent : text;
        } catch (IOException ignored) {
            return rawContent;
        }
    }

    private JsonNode firstNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private String normalizeProfileName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return DEFAULT_PROFILE_NAME;
        }
        return profileName.trim();
    }

    private String normalizeWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }
        return workingDirectory.trim();
    }

    private static final class SubscriptionState {

        private final String profileName;
        private final String startedAt;
        private CliStreamHandle handle;
        private volatile String lastError;

        private SubscriptionState(String profileName, String startedAt) {
            this.profileName = profileName;
            this.startedAt = startedAt;
        }

        private boolean isRunning() {
            return handle != null && handle.isRunning();
        }

        private void stop() {
            if (handle != null) {
                handle.stop();
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
