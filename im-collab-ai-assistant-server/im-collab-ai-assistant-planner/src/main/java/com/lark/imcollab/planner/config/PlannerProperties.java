package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    private Quality quality = new Quality();
    private Summarization summarization = new Summarization();

    @Data
    public static class Quality {
        private int passThreshold = 70;
        private int maxReplanAttempts = 2;
    }

    @Data
    public static class Summarization {
        private int maxTokensBeforeSummary = 6000;
        private int messagesToKeep = 20;
    }
}
