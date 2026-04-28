package com.lark.imcollab.store.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "imcollab.store.redisson", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StoreRedissonConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(StoreRedisProperties properties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setPassword(resolvePassword(properties.getPassword()))
                .setDatabase(properties.getDatabase());
        return Redisson.create(config);
    }

    private String resolvePassword(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        return password;
    }
}
