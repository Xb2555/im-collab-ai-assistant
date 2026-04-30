package com.lark.imcollab.planner.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    private Prompt prompt = new Prompt();
    private Quality quality = new Quality();
    private Summarization summarization = new Summarization();
    private Disambiguation disambiguation = new Disambiguation();

    @PostConstruct
    public void initDefaults() {
        disambiguation.applyDefaultsIfMissing();
    }

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

    @Data
    public static class Disambiguation {
        private List<TermPolicyDefinition> termPolicies = new ArrayList<>();

        private void applyDefaultsIfMissing() {
            if (!termPolicies.isEmpty()) {
                return;
            }
            termPolicies = List.of(defaultHarnessPolicy());
        }

        private TermPolicyDefinition defaultHarnessPolicy() {
            return TermPolicyDefinition.builder()
                    .term("harness")
                    .clarificationPrompt("我还不能高置信度判断你说的是哪一类对象。请确认更接近以下哪一项：")
                    .decisiveGap(2)
                    .minimumScore(4)
                    .highConfidenceScore(8)
                    .meanings(List.of(
                            TermMeaningDefinition.builder()
                                    .meaningCode("WORKSPACE_INTERNAL_CAPABILITY")
                                    .userLabel("当前工作上下文中的内部模块或能力")
                                    .signals(List.of("模块", "场景 c", "场景c", "文档生成", "执行编排", "办公助手", "协同办公"))
                                    .strongSignals(List.of("当前项目", "本项目", "仓库", "代码", "planner", "orchestrator", "harness 模块", "harness模块"))
                                    .build(),
                            TermMeaningDefinition.builder()
                                    .meaningCode("EXTERNAL_VENDOR_PRODUCT")
                                    .userLabel("外部产品、平台或厂商方案")
                                    .signals(List.of("pipeline", "devops", "云原生", "k8s", "kubernetes", "gitops"))
                                    .strongSignals(List.of("ci/cd", "cicd", "delegate", "helm"))
                                    .build(),
                            TermMeaningDefinition.builder()
                                    .meaningCode("GENERIC_TECHNICAL_CONCEPT")
                                    .userLabel("通用技术概念或框架")
                                    .signals(List.of("驱动", "框架"))
                                    .strongSignals(List.of("test harness", "测试框架", "测试桩"))
                                    .build()
                    ))
                    .build();
        }
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TermPolicyDefinition {
        private String term;
        private String clarificationPrompt;
        private int decisiveGap;
        private int minimumScore;
        private int highConfidenceScore;
        @lombok.Builder.Default
        private List<TermMeaningDefinition> meanings = new ArrayList<>();
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TermMeaningDefinition {
        private String meaningCode;
        private String userLabel;
        @lombok.Builder.Default
        private List<String> signals = new ArrayList<>();
        @lombok.Builder.Default
        private List<String> strongSignals = new ArrayList<>();
    }
}
