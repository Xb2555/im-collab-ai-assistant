package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.dashscope.critic")
public class CriticModelProperties {
    private String modelName;
    private double temperature;
    private int maxTokens;
}
