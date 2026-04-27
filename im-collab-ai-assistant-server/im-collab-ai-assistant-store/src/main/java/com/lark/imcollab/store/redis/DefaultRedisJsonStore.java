package com.lark.imcollab.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class DefaultRedisJsonStore implements RedisJsonStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DefaultRedisJsonStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize value for redis", exception);
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Optional<String> payload = Optional.ofNullable(redisTemplate.opsForValue().get(key))
                .filter(value -> !value.isBlank());
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload.get(), type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize value from redis", exception);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
