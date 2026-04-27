package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.dto.LarkRealtimeMessage;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LarkIMMessageStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final String CONSUMER_ID = LarkIMMessageStreamService.class.getName();

    private final LarkOAuthService oauthService;
    private final LarkMessageEventSubscriptionService subscriptionService;
    private final LarkCliProfileResolver profileResolver;
    private final Map<String, Set<SseEmitter>> emittersByChatId = new ConcurrentHashMap<>();
    private final AtomicBoolean subscriptionRegistered = new AtomicBoolean(false);

    public LarkIMMessageStreamService(
            LarkOAuthService oauthService,
            LarkMessageEventSubscriptionService subscriptionService
    ) {
        this(oauthService, subscriptionService, null);
    }

    @Autowired
    public LarkIMMessageStreamService(
            LarkOAuthService oauthService,
            LarkMessageEventSubscriptionService subscriptionService,
            LarkCliProfileResolver profileResolver
    ) {
        this.oauthService = oauthService;
        this.subscriptionService = subscriptionService;
        this.profileResolver = profileResolver;
    }

    public SseEmitter subscribe(String authorization, String chatId) {
        requireAuthenticated(authorization);
        String normalizedChatId = requireValue(chatId, "chatId");
        ensureMessageSubscription();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        register(normalizedChatId, emitter);
        sendHeartbeat(normalizedChatId, emitter);
        return emitter;
    }

    void publish(LarkMessageEvent event) {
        if (event == null || event.chatId() == null || event.chatId().isBlank()) {
            return;
        }
        LarkRealtimeMessage message = mapMessage(event);
        for (Map.Entry<String, Set<SseEmitter>> entry : emittersByChatId.entrySet()) {
            String subscribedChatId = entry.getKey();
            if (!shouldPublishToFrontend(subscribedChatId, event)) {
                continue;
            }
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(message, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException exception) {
                    remove(subscribedChatId, emitter);
                }
            }
        }
    }

    void publishBotReply(LarkMessageEvent sourceEvent, String text) {
        if (sourceEvent == null || sourceEvent.chatId() == null || sourceEvent.chatId().isBlank()) {
            return;
        }
        publish(new LarkMessageEvent(
                "local-bot-reply-" + sourceEvent.messageId(),
                "local-bot-reply-" + sourceEvent.messageId(),
                sourceEvent.chatId(),
                sourceEvent.chatType(),
                "text",
                text,
                "bot",
                String.valueOf(Instant.now().toEpochMilli()),
                false
        ));
    }

    private boolean shouldPublishToFrontend(String subscribedChatId, LarkMessageEvent event) {
        return subscribedChatId.equals(event.chatId());
    }

    void register(String chatId, SseEmitter emitter) {
        String normalizedChatId = requireValue(chatId, "chatId");
        emittersByChatId.computeIfAbsent(normalizedChatId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(normalizedChatId, emitter));
        emitter.onTimeout(() -> remove(normalizedChatId, emitter));
        emitter.onError(ignored -> remove(normalizedChatId, emitter));
    }

    int subscriberCount(String chatId) {
        Set<SseEmitter> emitters = emittersByChatId.get(chatId);
        return emitters == null ? 0 : emitters.size();
    }

    private void ensureMessageSubscription() {
        String profileName = resolveSubscriptionProfileName();
        LarkEventSubscriptionStatus status = subscriptionService.getMessageSubscriptionStatus(profileName);
        if (!status.running()) {
            subscriptionRegistered.set(false);
        }
        if (!subscriptionRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            subscriptionService.startMessageSubscription(
                    profileName,
                    CONSUMER_ID,
                    LarkMessageEventSubscriptionService.FRONTEND_STREAM_CONSUMER_PRIORITY,
                    this::publish
            );
        } catch (RuntimeException exception) {
            subscriptionRegistered.set(false);
            throw exception;
        }
    }

    private String resolveSubscriptionProfileName() {
        if (profileResolver == null) {
            return null;
        }
        return profileResolver.resolveConfiguredAppProfileName();
    }

    private void requireAuthenticated(String authorization) {
        oauthService.resolveAuthenticatedSessionByBusinessToken(extractBearerToken(authorization))
                .orElseThrow(() -> new LarkIMUnauthorizedException("Unauthorized"));
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }

    private void sendHeartbeat(String chatId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("heartbeat")
                    .data(Map.of("chatId", chatId, "state", "connected"), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            remove(chatId, emitter);
        }
    }

    private void remove(String chatId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByChatId.get(chatId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByChatId.remove(chatId, emitters);
        }
    }

    private LarkRealtimeMessage mapMessage(LarkMessageEvent event) {
        return new LarkRealtimeMessage(
                event.eventId(),
                event.messageId(),
                event.chatId(),
                event.chatType(),
                event.messageType(),
                event.content(),
                event.senderOpenId(),
                event.createTime(),
                event.mentionDetected()
        );
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
