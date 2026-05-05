package com.lark.imcollab.gateway.im.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LarkSdkMessageEventConnectionFactoryTest {

    @Test
    void registersMessageReadHandlerAsNoop() throws Exception {
        LarkSdkMessageEventConnectionFactory factory = new LarkSdkMessageEventConnectionFactory(
                null,
                null,
                new LarkMessageEventMapper(new ObjectMapper()),
                mock(LarkSdkMessageEventProcessor.class)
        );

        EventDispatcher dispatcher = factory.buildEventDispatcher(event -> {
        });

        assertThat(registeredEventHandlers(dispatcher))
                .containsKeys("im.message.receive_v1", "im.message.message_read_v1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registeredEventHandlers(EventDispatcher dispatcher) throws Exception {
        Field field = EventDispatcher.class.getDeclaredField("eventType2EventHandler");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(dispatcher);
    }
}
