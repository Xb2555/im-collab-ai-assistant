package com.lark.imcollab.store.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StoreRedisProperties.class)
public class StoreInfrastructureConfiguration {
}
