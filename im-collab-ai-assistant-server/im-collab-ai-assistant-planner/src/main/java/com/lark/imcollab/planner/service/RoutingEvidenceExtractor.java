package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RoutingEvidenceExtractor {

    RoutingEvidence extract(String userInput) {
        if (!hasText(userInput)) {
            return RoutingEvidence.empty(userInput);
        }
        String normalized = compact(userInput);
        Set<ArtifactTypeEnum> mentionedDeliverables = EnumSet.noneOf(ArtifactTypeEnum.class);
        if (mentionsPpt(normalized)) {
            mentionedDeliverables.add(ArtifactTypeEnum.PPT);
        }
        if (mentionsDoc(normalized)) {
            mentionedDeliverables.add(ArtifactTypeEnum.DOC);
        }
        if (mentionsSummary(normalized)) {
            mentionedDeliverables.add(ArtifactTypeEnum.SUMMARY);
        }
        boolean concreteArtifactEditAnchor = hasConcreteArtifactEditAnchor(normalized);
        boolean ambiguousMaterialOrganizationRequest = hasAmbiguousMaterialOrganizationSignal(normalized, concreteArtifactEditAnchor);
        boolean newCompletedDeliverableRequest = hasNewCompletedDeliverableSignal(normalized);
        return new RoutingEvidence(
                userInput,
                normalized,
                hasExplicitFreshTaskSignal(normalized),
                hasCurrentTaskReference(normalized),
                hasContinuationSignal(normalized),
                concreteArtifactEditAnchor,
                newCompletedDeliverableRequest,
                ambiguousMaterialOrganizationRequest,
                mentionedDeliverables
        );
    }

    List<PendingFollowUpRecommendation> semanticCarryForwardCandidates(
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

    List<PendingFollowUpRecommendation> targetCandidates(
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (evidence == null || recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return recommendations.stream()
                .filter(recommendation -> recommendation != null && matchesTargetDeliverable(evidence, recommendation.getTargetDeliverable()))
                .toList();
    }

    boolean matchesSemanticCarryForward(RoutingEvidence evidence, PendingFollowUpRecommendation recommendation) {
        if (evidence == null || recommendation == null) {
            return false;
        }
        if (!matchesTargetDeliverable(evidence, recommendation.getTargetDeliverable())) {
            return false;
        }
        return matchesSourceContext(evidence, recommendation);
    }

    boolean matchesTargetDeliverable(RoutingEvidence evidence, ArtifactTypeEnum targetDeliverable) {
        return evidence != null && evidence.mentionsDeliverable(targetDeliverable);
    }

    boolean matchesSourceContext(RoutingEvidence evidence, PendingFollowUpRecommendation recommendation) {
        if (evidence == null || recommendation == null) {
            return false;
        }
        String normalized = evidence.normalizedInput();
        if (recommendation.getTargetDeliverable() == ArtifactTypeEnum.SUMMARY) {
            if (normalized.contains("当前任务内容")
                    || normalized.contains("任务内容")
                    || normalized.contains("当前内容")
                    || normalized.contains("这个任务")
                    || normalized.contains("可直接发送")
                    || normalized.contains("群里")
                    || normalized.contains("发群")
                    || normalized.contains("老板")
                    || normalized.contains("同步")) {
                return true;
            }
            if (looksLikeTaskLevelSummaryRequest(normalized)) {
                return true;
            }
        }
        if (recommendation.getSourceArtifactType() == null) {
            return normalized.contains("当前任务内容")
                    || normalized.contains("任务内容")
                    || normalized.contains("当前内容")
                    || recommendation.getTargetDeliverable() == ArtifactTypeEnum.SUMMARY
                    && (normalized.contains("可直接发送")
                    || normalized.contains("群里")
                    || normalized.contains("发群")
                    || normalized.contains("私聊")
                    || normalized.contains("老板")
                    || normalized.contains("同步"));
        }
        return switch (recommendation.getSourceArtifactType()) {
            case DOC -> normalized.contains("当前文档")
                    || normalized.contains("这个文档")
                    || normalized.contains("这份文档")
                    || normalized.contains("根据文档")
                    || normalized.contains("基于文档");
            case PPT -> normalized.contains("当前ppt")
                    || normalized.contains("这个ppt")
                    || normalized.contains("这份ppt")
                    || normalized.contains("当前演示稿")
                    || normalized.contains("这份演示稿")
                    || normalized.contains("根据ppt")
                    || normalized.contains("基于ppt");
            default -> false;
        };
    }

    boolean looksLikeExplicitFreshTask(String userInput) {
        return extract(userInput).explicitFreshTask();
    }

    boolean looksLikeNewCompletedDeliverableRequest(String userInput) {
        return extract(userInput).newCompletedDeliverableRequest();
    }

    boolean looksLikeAmbiguousMaterialOrganizationRequest(String userInput) {
        return extract(userInput).ambiguousMaterialOrganizationRequest();
    }

    private boolean hasExplicitFreshTaskSignal(String normalizedInput) {
        return normalizedInput.contains("新建一个任务")
                || normalizedInput.contains("新开一个任务")
                || normalizedInput.contains("另起一个任务")
                || normalizedInput.contains("再开一个任务")
                || normalizedInput.contains("重新开始一个新任务")
                || normalizedInput.contains("单独起一个新任务")
                || normalizedInput.contains("重新来一份")
                || normalizedInput.contains("重新生成一版")
                || normalizedInput.contains("忽略上一个任务");
    }

    private boolean hasCurrentTaskReference(String normalizedInput) {
        return normalizedInput.contains("这个任务")
                || normalizedInput.contains("当前任务")
                || normalizedInput.contains("任务内容")
                || normalizedInput.contains("这个文档")
                || normalizedInput.contains("这份文档")
                || normalizedInput.contains("当前文档")
                || normalizedInput.contains("这个ppt")
                || normalizedInput.contains("这份ppt")
                || normalizedInput.contains("当前ppt")
                || normalizedInput.contains("这个演示稿")
                || normalizedInput.contains("这份演示稿")
                || normalizedInput.contains("上述内容")
                || normalizedInput.contains("已有产物");
    }

    private boolean hasContinuationSignal(String normalizedInput) {
        return normalizedInput.contains("根据")
                || normalizedInput.contains("基于")
                || normalizedInput.contains("整理")
                || normalizedInput.contains("总结")
                || normalizedInput.contains("补充")
                || normalizedInput.contains("修改")
                || normalizedInput.contains("改")
                || normalizedInput.contains("生成一下")
                || normalizedInput.contains("做一版")
                || normalizedInput.contains("汇报给老板")
                || normalizedInput.contains("发给老板")
                || normalizedInput.contains("发群里")
                || normalizedInput.contains("发到群里");
    }

    private boolean hasNewCompletedDeliverableSignal(String normalizedInput) {
        return (normalizedInput.contains("生成")
                || normalizedInput.contains("整理")
                || normalizedInput.contains("做一版")
                || normalizedInput.contains("输出")
                || normalizedInput.contains("补一份"))
                && (mentionsPpt(normalizedInput)
                || mentionsDoc(normalizedInput)
                || mentionsSummary(normalizedInput));
    }

    private boolean hasConcreteArtifactEditAnchor(String normalizedInput) {
        return (normalizedInput.contains("第") && (normalizedInput.contains("页") || normalizedInput.contains("段") || normalizedInput.contains("节")))
                || normalizedInput.contains("标题")
                || normalizedInput.contains("改成")
                || normalizedInput.contains("替换")
                || normalizedInput.contains("删除")
                || normalizedInput.contains("新增")
                || normalizedInput.contains("添加")
                || normalizedInput.contains("插入")
                || normalizedInput.contains("补充到")
                || normalizedInput.contains("加到");
    }

    private boolean hasAmbiguousMaterialOrganizationSignal(String normalizedInput, boolean concreteArtifactEditAnchor) {
        if (!normalizedInput.contains("材料")) {
            return false;
        }
        boolean organize = normalizedInput.contains("整理")
                || normalizedInput.contains("汇总")
                || normalizedInput.contains("梳理")
                || normalizedInput.contains("总结");
        return organize && !concreteArtifactEditAnchor;
    }

    private boolean looksLikeTaskLevelSummaryRequest(String normalizedInput) {
        boolean summaryTarget = normalizedInput.contains("摘要")
                || normalizedInput.contains("汇报总结")
                || normalizedInput.contains("总结一下")
                || normalizedInput.contains("生成总结")
                || normalizedInput.contains("整理总结");
        if (!summaryTarget) {
            return false;
        }
        return normalizedInput.contains("生成")
                || normalizedInput.contains("整理")
                || normalizedInput.contains("总结")
                || normalizedInput.contains("输出")
                || normalizedInput.contains("写");
    }

    private boolean mentionsPpt(String normalizedInput) {
        return normalizedInput.contains("ppt")
                || normalizedInput.contains("演示稿")
                || normalizedInput.contains("幻灯片")
                || normalizedInput.contains("幻灯");
    }

    private boolean mentionsDoc(String normalizedInput) {
        return normalizedInput.contains("文档")
                || normalizedInput.contains("doc")
                || normalizedInput.contains("报告")
                || normalizedInput.contains("配套文档");
    }

    private boolean mentionsSummary(String normalizedInput) {
        return normalizedInput.contains("摘要")
                || normalizedInput.contains("总结")
                || normalizedInput.contains("汇报总结")
                || normalizedInput.contains("可直接发送")
                || normalizedInput.contains("群里")
                || normalizedInput.contains("发群")
                || normalizedInput.contains("私聊")
                || normalizedInput.contains("老板")
                || normalizedInput.contains("同步");
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
