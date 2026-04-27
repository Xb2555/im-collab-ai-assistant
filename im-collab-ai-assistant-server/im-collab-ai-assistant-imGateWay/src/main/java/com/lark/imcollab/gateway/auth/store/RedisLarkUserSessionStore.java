package com.lark.imcollab.gateway.auth.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisLarkUserSessionStore implements LarkUserSessionStore {

    private static final String STATE_KEY_PREFIX = "imcollab:auth:lark:state:";
    private static final String SESSION_KEY_PREFIX = "imcollab:auth:lark:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisLarkUserSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveState(String state, Duration ttl) {
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, "1", ttl);
    }

    @Override
    public boolean consumeState(String state) {
        String key = STATE_KEY_PREFIX + state;
        Boolean exists = redisTemplate.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }
        redisTemplate.delete(key);
        return true;
    }

    @Override
    public void saveSession(String sessionId, LarkOAuthLoginSession session, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, objectMapper.writeValueAsString(session), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize lark oauth session", exception);
        }
    }

    @Override
    public Optional<LarkOAuthLoginSession> findSession(String sessionId) {
        String payload = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, LarkOAuthLoginSession.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize lark oauth session", exception);
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
    }
}
