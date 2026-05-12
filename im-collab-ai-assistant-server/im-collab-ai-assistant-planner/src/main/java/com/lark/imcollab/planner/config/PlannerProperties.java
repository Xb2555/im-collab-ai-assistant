package com.lark.imcollab.planner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    private Prompt prompt = new Prompt();
    private Quality quality = new Quality();
    private Summarization summarization = new Summarization();
    private Replan replan = new Replan();
    private Intent intent = new Intent();
    private Disambiguation disambiguation = new Disambiguation();
    private Auth auth = new Auth();
    private Clarification clarification = new Clarification();
    private Memory memory = new Memory();
    private Graph graph = new Graph();
    private ContextCollection contextCollection = new ContextCollection();
    private Routing routing = new Routing();

    public void initDefaults() {
        if (disambiguation.getTermPolicies() != null && !disambiguation.getTermPolicies().isEmpty()) {
            return;
        }
        TermPolicyDefinition harness = new TermPolicyDefinition();
        harness.setTerm("harness");
        harness.setClarificationPrompt("请确认你说的是哪一种 harness：");
        harness.setMinimumScore(2);
        harness.setDecisiveGap(2);
        harness.setHighConfidenceScore(6);

        TermMeaningDefinition projectModule = new TermMeaningDefinition();
        projectModule.setMeaningCode("WORKSPACE_INTERNAL_CAPABILITY");
        projectModule.setUserLabel("当前项目里的 Harness 模块");
        projectModule.setSignals(List.of("当前项目", "模块", "场景 c", "文档生成", "链路"));
        projectModule.setStrongSignals(List.of("harness 模块", "当前项目里的 harness", "场景 c"));

        TermMeaningDefinition testHarness = new TermMeaningDefinition();
        testHarness.setMeaningCode("TEST_HARNESS");
        testHarness.setUserLabel("测试框架或测试 harness");
        testHarness.setSignals(List.of("测试", "test", "mock", "用例", "自动化"));
        testHarness.setStrongSignals(List.of("test harness", "测试 harness"));

        TermMeaningDefinition safetyHarness = new TermMeaningDefinition();
        safetyHarness.setMeaningCode("SAFETY_HARNESS");
        safetyHarness.setUserLabel("安全带或硬件 harness");
        safetyHarness.setSignals(List.of("硬件", "设备", "安全带", "线束", "hardware"));
        safetyHarness.setStrongSignals(List.of("安全 harness", "wire harness"));

        harness.setMeanings(List.of(projectModule, testHarness, safetyHarness));
        disambiguation.setTermPolicies(List.of(harness));
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
    public static class Replan {
        private boolean patchIntentModelEnabled = true;
        private int patchIntentTimeoutSeconds = 4;
        private double patchIntentPassThreshold = 0.65d;
    }

    @Data
    public static class Intent {
        private boolean modelEnabled = true;
        private int timeoutSeconds = 3;
        private double passThreshold = 0.65d;
        private boolean fallbackToLocalRules = true;
        private boolean unknownReplyModelEnabled = true;
        private int unknownReplyTimeoutSeconds = 2;
    }

    @Data
    public static class Disambiguation {
        private List<TermPolicyDefinition> termPolicies = List.of();
    }

    @Data
    public static class Auth {
        private boolean enabled = true;
    }

    @Data
    public static class TermPolicyDefinition {
        private String term;
        private String clarificationPrompt = "";
        private int minimumScore = 2;
        private int decisiveGap = 2;
        private int highConfidenceScore = 6;
        private List<TermMeaningDefinition> meanings = List.of();
    }

    @Data
    public static class TermMeaningDefinition {
        private String meaningCode;
        private String userLabel;
        private List<String> signals = List.of();
        private List<String> strongSignals = List.of();
    }

    @Data
    public static class Clarification {
        private boolean modelEnabled = true;
        private int timeoutSeconds = 3;
        private double passThreshold = 0.65d;
    }

    @Data
    public static class Memory {
        private boolean enabled = true;
        private int recentTurns = 8;
        private int maxTurnChars = 500;
        private int maxContextChars = 3000;
        private int summaryMaxChars = 1200;
    }

    @Data
    public static class Graph {
        private boolean enabled = true;
        private int supervisorDecisionTimeoutSeconds = 3;
        private double supervisorDecisionPassThreshold = 0.6d;
    }

    @Data
    public static class ContextCollection {
        private boolean enabled = true;
        private int timeoutSeconds = 6;
        private int maxImMessages = 30;
        private int maxDocChars = 8000;
        private int defaultLookbackMinutes = 120;
    }

    @Data
    public static class Routing {
        private int signalLowUpperBound = 34;
        private int signalMediumUpperBound = 69;
        private int freshTaskExplicitScore = 100;
        private int freshTaskResetScore = 85;
        private int currentTaskReferenceStrongScore = 80;
        private int currentArtifactReferenceScore = 70;
        private int continuationKeywordScore = 20;
        private int continuationAudienceScore = 25;
        private int continuationIntentCap = 100;
        private int artifactEditAnchorScore = 90;
        private int artifactEditMutationScore = 35;
        private int artifactEditStrongThreshold = 70;
        private int newDeliverableActionScore = 80;
        private int newDeliverableMentionOnlyScore = 35;
        private int newDeliverableMentionWithEditAnchorScore = 20;
        private int ambiguousMaterialBaseScore = 75;
        private int ambiguousMaterialWithDeliverableScore = 20;
        private int affinityDeliverableMediumScore = 35;
        private int affinityDeliverableHighScore = 70;
        private int affinitySourceMediumScore = 30;
        private int affinitySourceHighScore = 60;
        private int affinityContinuationMediumScore = 10;
        private int affinityContinuationHighScore = 20;
        private int affinityCurrentTaskMediumScore = 10;
        private int affinityCurrentTaskHighScore = 20;
        private int semanticCandidateMinScore = 50;
        private int semanticUniqueGap = 25;
        private int proceedCurrentTaskMinScore = 75;
        private int proceedNewTaskMinScore = 70;
        private int continueCurrentTaskMinScore = 40;
        private int artifactEditPreferenceMinScore = 70;
    }
}
