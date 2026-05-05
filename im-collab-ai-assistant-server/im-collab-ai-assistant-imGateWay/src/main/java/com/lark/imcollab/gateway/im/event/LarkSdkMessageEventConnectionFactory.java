package com.lark.imcollab.gateway.im.event;

import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class LarkSdkMessageEventConnectionFactory implements LarkMessageEventConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(LarkSdkMessageEventConnectionFactory.class);

    private final LarkAppProperties appProperties;
    private final LarkIMEventProperties properties;
    private final LarkMessageEventMapper mapper;
    private final LarkSdkMessageEventProcessor eventProcessor;

    public LarkSdkMessageEventConnectionFactory(
            LarkAppProperties appProperties,
            LarkIMEventProperties properties,
            LarkMessageEventMapper mapper,
            LarkSdkMessageEventProcessor eventProcessor
    ) {
        this.appProperties = appProperties;
        this.properties = properties;
        this.mapper = mapper;
        this.eventProcessor = eventProcessor;
    }

    @Override
    public LarkMessageEventConnection start(Consumer<LarkMessageEvent> messageConsumer) {
        String appId = requireValue(appProperties.getAppId(), "imcollab.gateway.lark.app-id");
        String appSecret = requireValue(appProperties.getAppSecret(), "imcollab.gateway.lark.app-secret");
        EventDispatcher eventHandler = buildEventDispatcher(messageConsumer);

        Client.Builder builder = new Client.Builder(appId, appSecret)
                .eventHandler(eventHandler)
                .autoReconnect(properties.isAutoReconnectEnabled());
        String domain = normalize(properties.getDomain());
        if (domain != null) {
            builder.domain(domain);
        }

        Client client = builder.build();
        client.start();
        return new SdkConnection(client);
    }

    EventDispatcher buildEventDispatcher(Consumer<LarkMessageEvent> messageConsumer) {
        return EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        try {
                            mapper.fromSdkEvent(event)
                                    .ifPresent(mappedEvent -> eventProcessor.submit(mappedEvent, messageConsumer));
                        } catch (RuntimeException exception) {
                            log.warn("Failed to map Lark SDK message receive event.", exception);
                        }
                    }
                })
                .onP2MessageReadV1(new ImService.P2MessageReadV1Handler() {
                    @Override
                    public void handle(P2MessageReadV1 event) {
                        if (log.isDebugEnabled()) {
                            log.debug("Ignored Lark SDK message read event.");
                        }
                    }
                })
                .build();
    }

    private String requireValue(String value, String propertyName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalStateException(propertyName + " must be configured for Lark SDK event subscription");
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static final class SdkConnection implements LarkMessageEventConnection {

        private final Client client;
        private final AtomicBoolean running = new AtomicBoolean(true);

        private SdkConnection(Client client) {
            this.client = client;
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            disableReconnect();
            disconnect();
            shutdownExecutor();
        }

        private void disableReconnect() {
            setField("autoReconnect", Boolean.FALSE);
        }

        private void disconnect() {
            try {
                Method disconnect = Client.class.getDeclaredMethod("disconnect");
                disconnect.setAccessible(true);
                disconnect.invoke(client);
            } catch (NoSuchMethodException | IllegalAccessException exception) {
                throw new IllegalStateException("Failed to stop Lark SDK WebSocket client", exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Failed to stop Lark SDK WebSocket client",
                        exception.getTargetException());
            }
        }

        private void shutdownExecutor() {
            Object value = getField("executor");
            if (value instanceof ExecutorService executorService) {
                executorService.shutdownNow();
            }
        }

        private void setField(String fieldName, Object value) {
            try {
                Field field = Client.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(client, value);
            } catch (NoSuchFieldException | IllegalAccessException exception) {
                throw new IllegalStateException("Failed to update Lark SDK WebSocket client field " + fieldName,
                        exception);
            }
        }

        private Object getField(String fieldName) {
            try {
                Field field = Client.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(client);
            } catch (NoSuchFieldException | IllegalAccessException exception) {
                throw new IllegalStateException("Failed to read Lark SDK WebSocket client field " + fieldName,
                        exception);
            }
        }
    }
}
