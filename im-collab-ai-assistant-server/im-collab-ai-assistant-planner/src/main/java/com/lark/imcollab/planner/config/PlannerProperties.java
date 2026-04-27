package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    private Prompt prompt = new Prompt();
    private Quality quality = new Quality();
    private Summarization summarization = new Summarization();

    @Data
    public static class Prompt {
        private String profile = "default";
        private String version = "v1";
        private String fallbackProfile = "default";
        private String fallbackVersion = "v1";
        private String defaultProfession = "通用知识工作者";
        private String defaultIndustry = "通用行业";
        private String defaultAudience = "通用受众";
        private String defaultTone = "专业、清晰、可执行";
        private String defaultLanguage = "中文";
    }

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
