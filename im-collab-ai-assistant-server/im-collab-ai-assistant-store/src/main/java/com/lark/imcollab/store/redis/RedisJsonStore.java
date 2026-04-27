package com.lark.imcollab.store.redis;

import java.time.Duration;
import java.util.Optional;

public interface RedisJsonStore {

    void set(String key, Object value, Duration ttl);

    <T> Optional<T> get(String key, Class<T> type);

    void delete(String key);
}
