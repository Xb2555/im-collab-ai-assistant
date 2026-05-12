package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RoutingEvidenceExtractor {

    private final RoutingTuning tuning;

    public RoutingEvidenceExtractor() {
        this(RoutingTuning.defaults());
    }

    public RoutingEvidenceExtractor(PlannerProperties properties) {
        this(RoutingTuning.from(properties));
    }

    public RoutingEvidenceExtractor(RoutingTuning tuning) {
        this.tuning = tuning == null ? RoutingTuning.defaults() : tuning;
    }

    public RoutingEvidence extract(String userInput) {
        if (!hasText(userInput)) {
            return RoutingEvidence.empty(userInput, tuning);
        }
        String normalized = compact(userInput);
        EnumMap<ArtifactTypeEnum, Integer> deliverableMentionScores = new EnumMap<>(ArtifactTypeEnum.class);
        EnumMap<ArtifactTypeEnum, Integer> sourceContextScores = new EnumMap<>(ArtifactTypeEnum.class);

        int freshTaskScore = scoreFreshTask(normalized);
        int currentTaskReferenceScore = scoreCurrentTaskReference(normalized);
        int continuationIntentScore = scoreContinuationIntent(normalized);
        int artifactEditScore = scoreArtifactEdit(normalized);
        int newDeliverableScore = scoreNewDeliverable(normalized, deliverableMentionScores, artifactEditScore >= tuning.artifactEditStrongThreshold());
        int ambiguousMaterialOrganizationScore = scoreAmbiguousMaterialOrganization(normalized, artifactEditScore, deliverableMentionScores);
        populateSourceContextScores(normalized, sourceContextScores);
        ensureDeliverableMentionScores(normalized, deliverableMentionScores);

        return new RoutingEvidence(
                userInput,
                normalized,
                tuning,
                freshTaskScore,
                currentTaskReferenceScore,
                continuationIntentScore,
                artifactEditScore,
                newDeliverableScore,
                ambiguousMaterialOrganizationScore,
                deliverableMentionScores,
                sourceContextScores
        );
    }

    public int scoreRecommendationAffinity(RoutingEvidence evidence, PendingFollowUpRecommendation recommendation) {
        if (evidence == null || recommendation == null || recommendation.getTargetDeliverable() == null) {
            return 0;
        }
        SignalLevel deliverableLevel = evidence.deliverableMentionLevel(recommendation.getTargetDeliverable());
        if (deliverableLevel == SignalLevel.NONE) {
            return 0;
        }
        int deliverableScore = bucketScore(deliverableLevel, 0, tuning.affinityDeliverableMediumScore(), tuning.affinityDeliverableHighScore());
        int sourceScore = bucketScore(sourceContextLevelForRecommendation(evidence, recommendation), 0, tuning.affinitySourceMediumScore(), tuning.affinitySourceHighScore());
        int continuationScore = bucketScore(evidence.continuationIntentLevel(), 0, tuning.affinityContinuationMediumScore(), tuning.affinityContinuationHighScore());
        int currentTaskScore = bucketScore(evidence.currentTaskReferenceLevel(), 0, tuning.affinityCurrentTaskMediumScore(), tuning.affinityCurrentTaskHighScore());
        return clamp(deliverableScore + sourceScore + continuationScore + currentTaskScore);
    }

    public List<PendingFollowUpRecommendation> semanticCarryForwardCandidates(
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (evidence == null || recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return recommendations.stream()
                .filter(recommendation -> matchesSemanticCarryForward(evidence, recommendation))
                .toList();
    }

    public List<PendingFollowUpRecommendation> targetCandidates(
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (evidence == null || recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return recommendations.stream()
                .filter(recommendation -> recommendation != null
                        && recommendation.getTargetDeliverable() != null
                        && evidence.deliverableMentionLevel(recommendation.getTargetDeliverable()).ordinal() >= SignalLevel.MEDIUM.ordinal())
                .toList();
    }

    public boolean matchesSemanticCarryForward(RoutingEvidence evidence, PendingFollowUpRecommendation recommendation) {
        if (evidence == null || recommendation == null || recommendation.getTargetDeliverable() == null) {
            return false;
        }
        return evidence.deliverableMentionLevel(recommendation.getTargetDeliverable()).ordinal() >= SignalLevel.MEDIUM.ordinal()
                && sourceContextLevelForRecommendation(evidence, recommendation).ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public SignalLevel sourceContextLevelForRecommendation(RoutingEvidence evidence, PendingFollowUpRecommendation recommendation) {
        if (evidence == null || recommendation == null) {
            return SignalLevel.NONE;
        }
        if (recommendation.getTargetDeliverable() == ArtifactTypeEnum.SUMMARY) {
            int taskLevelScore = max(
                    evidence.sourceContextScore(ArtifactTypeEnum.SUMMARY),
                    evidence.currentTaskReferenceScore()
            );
            return tuning.levelOf(taskLevelScore);
        }
        if (recommendation.getSourceArtifactType() == null) {
            int taskLevelScore = max(
                    evidence.sourceContextScore(ArtifactTypeEnum.SUMMARY),
                    evidence.currentTaskReferenceScore()
            );
            return tuning.levelOf(taskLevelScore);
        }
        return evidence.sourceContextLevel(recommendation.getSourceArtifactType());
    }

    public boolean looksLikeExplicitFreshTask(String userInput) {
        return extract(userInput).freshTaskLevel() == SignalLevel.HIGH;
    }

    public boolean looksLikeNewCompletedDeliverableRequest(String userInput) {
        return extract(userInput).newDeliverableLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    public boolean looksLikeAmbiguousMaterialOrganizationRequest(String userInput) {
        return extract(userInput).ambiguousMaterialOrganizationLevel().ordinal() >= SignalLevel.MEDIUM.ordinal();
    }

    private int scoreFreshTask(String normalizedInput) {
        if (containsAny(normalizedInput, "新建一个任务", "新开一个任务", "另起一个任务", "单独起一个新任务", "再开一个任务", "重新开始一个新任务")) {
            return tuning.freshTaskExplicitScore();
        }
        if (containsAny(normalizedInput, "重新来一份", "重新生成一版", "忽略上一个任务")) {
            return tuning.freshTaskResetScore();
        }
        return 0;
    }

    private int scoreCurrentTaskReference(String normalizedInput) {
        int score = 0;
        if (containsAny(normalizedInput, "当前任务", "这个任务", "任务内容", "当前内容")) {
            score += tuning.currentTaskReferenceStrongScore();
        }
        if (containsAny(normalizedInput,
                "这个文档", "这份文档", "当前文档",
                "这个ppt", "这份ppt", "当前ppt",
                "这个演示稿", "这份演示稿",
                "上述内容", "已有产物")) {
            score += tuning.currentArtifactReferenceScore();
        }
        return clamp(score);
    }

    private int scoreContinuationIntent(String normalizedInput) {
        int score = 0;
        for (String token : List.of("根据", "基于", "整理", "总结", "梳理", "汇总", "做一版", "生成一下")) {
            if (normalizedInput.contains(token)) {
                score += tuning.continuationKeywordScore();
            }
        }
        for (String token : List.of("发给老板", "发群里", "发到群里", "汇报给老板")) {
            if (normalizedInput.contains(token)) {
                score += tuning.continuationAudienceScore();
            }
        }
        return Math.min(clamp(score), tuning.continuationIntentCap());
    }

    private int scoreArtifactEdit(String normalizedInput) {
        int score = 0;
        if (normalizedInput.contains("第") && containsAny(normalizedInput, "页", "段", "节")) {
            score = max(score, tuning.artifactEditAnchorScore());
        }
        for (String token : List.of(
                "标题", "改成", "替换", "删除", "新增", "添加", "插入", "补充到", "加到",
                "改", "补一段", "补一小节", "加一段", "加一小节"
        )) {
            if (normalizedInput.contains(token)) {
                score += tuning.artifactEditMutationScore();
            }
        }
        return clamp(score);
    }

    private int scoreNewDeliverable(
            String normalizedInput,
            Map<ArtifactTypeEnum, Integer> deliverableMentionScores,
            boolean strongArtifactEditAnchor
    ) {
        boolean deliverableMentioned = false;
        if (mentionsPpt(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.PPT, 100);
            deliverableMentioned = true;
        }
        if (mentionsDoc(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.DOC, 100);
            deliverableMentioned = true;
        }
        if (mentionsSummary(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.SUMMARY, 100);
            deliverableMentioned = true;
        }
        if (!deliverableMentioned) {
            return 0;
        }
        if (containsAny(normalizedInput, "生成", "整理", "做一版", "输出", "补一份")) {
            return tuning.newDeliverableActionScore();
        }
        return strongArtifactEditAnchor ? tuning.newDeliverableMentionWithEditAnchorScore() : tuning.newDeliverableMentionOnlyScore();
    }

    private int scoreAmbiguousMaterialOrganization(String normalizedInput, int artifactEditScore, Map<ArtifactTypeEnum, Integer> deliverableMentionScores) {
        if (!normalizedInput.contains("材料") || !containsAny(normalizedInput, "整理", "汇总", "梳理", "总结")) {
            return 0;
        }
        if (tuning.levelOf(artifactEditScore).ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            return 0;
        }
        boolean explicitNewDeliverable = deliverableMentionScores.getOrDefault(ArtifactTypeEnum.PPT, 0) > 0
                || deliverableMentionScores.getOrDefault(ArtifactTypeEnum.DOC, 0) > 0
                || deliverableMentionScores.getOrDefault(ArtifactTypeEnum.SUMMARY, 0) > 0;
        return explicitNewDeliverable ? tuning.ambiguousMaterialWithDeliverableScore() : tuning.ambiguousMaterialBaseScore();
    }

    private void populateSourceContextScores(String normalizedInput, Map<ArtifactTypeEnum, Integer> sourceContextScores) {
        int summaryScore = 0;
        if (containsAny(normalizedInput, "当前任务内容", "任务内容", "当前内容", "这个任务")) {
            summaryScore = max(summaryScore, 80);
        }
        if (containsAny(normalizedInput, "可直接发送", "群里", "发群", "私聊")) {
            summaryScore = max(summaryScore, 70);
        }
        if (looksLikeTaskLevelSummaryRequest(normalizedInput)) {
            summaryScore = max(summaryScore, 70);
        }
        if (summaryScore > 0) {
            sourceContextScores.put(ArtifactTypeEnum.SUMMARY, summaryScore);
        }

        if (containsAny(normalizedInput, "当前文档", "这个文档", "这份文档", "根据文档", "基于文档")) {
            sourceContextScores.put(ArtifactTypeEnum.DOC, 85);
        }
        if (containsAny(normalizedInput, "当前ppt", "这个ppt", "这份ppt", "当前演示稿", "根据ppt", "基于ppt", "这份演示稿")) {
            sourceContextScores.put(ArtifactTypeEnum.PPT, 85);
        }
    }

    private void ensureDeliverableMentionScores(String normalizedInput, Map<ArtifactTypeEnum, Integer> deliverableMentionScores) {
        if (mentionsPpt(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.PPT, 100);
        }
        if (mentionsDoc(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.DOC, 100);
        }
        if (mentionsSummary(normalizedInput)) {
            deliverableMentionScores.put(ArtifactTypeEnum.SUMMARY, 100);
        }
    }

    private boolean mentionsPpt(String normalizedInput) {
        return containsAny(normalizedInput, "ppt", "演示稿", "幻灯片", "幻灯");
    }

    private boolean mentionsDoc(String normalizedInput) {
        return containsAny(normalizedInput, "文档", "doc", "报告", "配套文档");
    }

    private boolean mentionsSummary(String normalizedInput) {
        if (containsAny(normalizedInput, "摘要", "汇报总结", "任务摘要", "可直接发送", "群里", "发群", "私聊")) {
            return true;
        }
        return normalizedInput.contains("总结")
                && containsAny(normalizedInput,
                "生成", "整理", "输出", "写",
                "发给老板", "汇报给老板", "发群里", "发到群里", "同步",
                "当前任务内容", "任务内容", "当前内容", "这个任务");
    }

    private boolean looksLikeTaskLevelSummaryRequest(String normalizedInput) {
        boolean summaryTarget = containsAny(normalizedInput, "摘要", "汇报总结", "任务摘要", "总结一下", "生成总结", "整理总结")
                || (normalizedInput.contains("总结")
                && containsAny(normalizedInput,
                "生成", "整理", "输出", "写",
                "发给老板", "汇报给老板", "发群里", "发到群里", "同步",
                "当前任务内容", "任务内容", "当前内容", "这个任务"));
        if (!summaryTarget) {
            return false;
        }
        return containsAny(normalizedInput, "生成", "整理", "总结", "输出", "写",
                "发给老板", "汇报给老板", "发群里", "发到群里", "同步");
    }

    private String compact(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("！", "")
                .replace("!", "");
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private int bucketScore(SignalLevel level, int none, int medium, int high) {
        return switch (level) {
            case NONE, LOW -> none;
            case MEDIUM -> medium;
            case HIGH -> high;
        };
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private int max(int left, int right) {
        return Math.max(left, right);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
