package com.lark.imcollab.store.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRedisStringStoreTests {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DefaultRedisStringStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new DefaultRedisStringStore(redisTemplate);
    }

    @Test
    void shouldSaveAndReadStringValues() {
        when(valueOperations.get("key-1")).thenReturn("value-1");

        store.set("key-1", "value-1", Duration.ofMinutes(5));

        assertThat(store.get("key-1")).contains("value-1");
        verify(valueOperations).set("key-1", "value-1", Duration.ofMinutes(5));
    }

    @Test
    void shouldReportKeyExistence() {
        when(redisTemplate.hasKey("key-1")).thenReturn(true);

        assertThat(store.hasKey("key-1")).isTrue();
    }
}
