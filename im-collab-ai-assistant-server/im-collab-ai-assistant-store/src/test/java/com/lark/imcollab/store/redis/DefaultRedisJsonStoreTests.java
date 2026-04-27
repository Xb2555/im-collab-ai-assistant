package com.lark.imcollab.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRedisJsonStoreTests {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DefaultRedisJsonStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new DefaultRedisJsonStore(redisTemplate, new ObjectMapper());
    }

    @Test
    void shouldSerializeAndDeserializeJsonValues() {
        TestPayload payload = new TestPayload("value-1");
        when(valueOperations.get("key-1")).thenReturn("{\"value\":\"value-1\"}");

        store.set("key-1", payload, Duration.ofMinutes(5));

        assertThat(store.get("key-1", TestPayload.class)).contains(payload);
        verify(valueOperations).set("key-1", "{\"value\":\"value-1\"}", Duration.ofMinutes(5));
    }

    @Test
    void shouldFailWhenRedisJsonPayloadIsInvalid() {
        when(valueOperations.get("key-1")).thenReturn("{invalid");

        assertThrows(IllegalStateException.class, () -> store.get("key-1", TestPayload.class));
    }

    private record TestPayload(String value) {
    }
}
