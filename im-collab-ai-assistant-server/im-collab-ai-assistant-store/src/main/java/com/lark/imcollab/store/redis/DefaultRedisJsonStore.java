package com.lark.imcollab.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class DefaultRedisJsonStore implements RedisJsonStore {

    private static final Logger log = LoggerFactory.getLogger(DefaultRedisJsonStore.class);

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
            throw new IllegalStateException("Failed to serialize value for redis key=" + key, exception);
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
            String preview = payload.get().length() > 200 ? payload.get().substring(0, 200) + "..." : payload.get();
            log.error("Failed to deserialize redis key={} type={} payload={}", key, type.getSimpleName(), preview, exception);
            throw new IllegalStateException("Failed to deserialize value from redis key=" + key + " type=" + type.getSimpleName(), exception);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
