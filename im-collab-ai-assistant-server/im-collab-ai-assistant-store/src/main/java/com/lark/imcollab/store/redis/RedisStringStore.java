package com.lark.imcollab.store.redis;

import java.time.Duration;
import java.util.Optional;

public interface RedisStringStore {

    void set(String key, String value, Duration ttl);

    Optional<String> get(String key);

    boolean hasKey(String key);

    void delete(String key);
}
