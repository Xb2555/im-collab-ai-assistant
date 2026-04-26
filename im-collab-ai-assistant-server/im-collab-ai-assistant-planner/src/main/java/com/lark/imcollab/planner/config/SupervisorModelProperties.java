package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.dashscope.supervisor")
public class SupervisorModelProperties {
    private String modelName;
    private double temperature;
    private int maxTokens;
}
