package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "planner.async")
public class PlannerAsyncProperties {

    private boolean enabled = true;
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 100;
    private int taskTimeoutSeconds = 180;
}
