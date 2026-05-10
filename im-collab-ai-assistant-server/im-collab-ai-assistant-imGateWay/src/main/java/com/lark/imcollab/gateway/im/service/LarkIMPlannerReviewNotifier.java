package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class LarkIMPlannerReviewNotifier implements TaskUserNotificationFacade {

    private static final Logger log = LoggerFactory.getLogger(LarkIMPlannerReviewNotifier.class);
    private static final int MAX_ARTIFACTS = 3;
    private static final int MAX_SUMMARY_BODY_CHARS = 12000;
    private static final int MAX_FAILURE_DETAIL_CHARS = 220;
    private static final int MAX_LARK_UUID_LENGTH = 50;

    private final LarkMessageReplyTool replyTool;
    private final LarkOutboundMessageRetryService retryService;
    private final LarkIMTaskReplyFormatter replyFormatter;

    public LarkIMPlannerReviewNotifier(
            LarkMessageReplyTool replyTool,
            LarkOutboundMessageRetryService retryService,
            LarkIMTaskReplyFormatter replyFormatter
    ) {
        this.replyTool = replyTool;
        this.retryService = retryService;
        this.replyFormatter = replyFormatter;
    }

    @Override
    public void notifyExecutionReviewed(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            TaskResultEvaluation evaluation
    ) {
        if (session == null || session.getInputContext() == null) {
            return;
        }
        send(session, formatReviewMessage(snapshot, evaluation), "reviewed");
    }

    @Override
    public void notifyExecutionFailed(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            String reason
    ) {
        if (session == null || session.getInputContext() == null) {
            return;
        }
        send(session, formatFailureMessage(snapshot, reason), "failed");
    }

    private String formatReviewMessage(TaskRuntimeSnapshot snapshot, TaskResultEvaluation evaluation) {
        StringBuilder builder = new StringBuilder();
        if (evaluation != null && evaluation.getVerdict() == ResultVerdictEnum.PASS) {
            builder.append("我检查了一下，当前产物已经生成完成。");
        } else if (evaluation != null && evaluation.getVerdict() == ResultVerdictEnum.HUMAN_REVIEW) {
            builder.append("我检查了一下，还需要你确认产物是否符合预期。");
            appendEvaluationIssues(builder, evaluation);
        } else {
            builder.append("我检查了一下，当前任务结果还需要处理。");
        }
        appendArtifacts(builder, snapshot == null ? List.of() : snapshot.getArtifacts());
        appendNextStepRecommendations(builder, evaluation);
        String status = replyFormatter.status(shareableStatusSnapshot(snapshot));
        if (hasText(status)) {
            builder.append("\n\n").append(status);
        }
        return builder.toString();
    }

    private void appendEvaluationIssues(StringBuilder builder, TaskResultEvaluation evaluation) {
        if (evaluation == null || evaluation.getIssues() == null || evaluation.getIssues().isEmpty()) {
            return;
        }
        List<String> issues = evaluation.getIssues().stream()
                .filter(this::hasText)
                .map(String::trim)
                .limit(2)
                .toList();
        if (issues.isEmpty()) {
            return;
        }
        builder.append("\n\n需要确认：");
        for (String issue : issues) {
            builder.append("\n- ").append(issue.length() > 120 ? issue.substring(0, 120) + "..." : issue);
        }
    }

    private String formatFailureMessage(TaskRuntimeSnapshot snapshot, String reason) {
        String readableReason = humanizeFailureReason(reason);
        StringBuilder builder = new StringBuilder("任务执行失败了");
        if (hasText(readableReason)) {
            builder.append("：").append(readableReason).append("。");
        } else {
            builder.append("。");
        }
        appendFailureDetails(builder, snapshot);
        appendArtifacts(builder, snapshot == null ? List.of() : snapshot.getArtifacts());
        String status = replyFormatter.status(shareableStatusSnapshot(snapshot));
        if (hasText(status)) {
            builder.append("\n\n").append(status);
        }
        builder.append("\n\n你可以稍后回复“重试”或“重新执行”再试一次。");
        return builder.toString();
    }

    private String humanizeFailureReason(String reason) {
        if (!hasText(reason)) {
            return null;
        }
        String normalized = reason.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("server time out") || lower.contains("timeout") || lower.contains("time out")) {
            return "飞书文档创建超时";
        }
        if (lower.contains("lark") && lower.contains("doc")) {
            return "飞书文档创建失败";
        }
        normalized = normalized
                .replace("Harness execution failed after IM confirmation:", "")
                .replace("Failed to submit IM execution task:", "")
                .replace("Error:", "")
                .trim();
        if (!hasText(normalized)) {
            return null;
        }
        if (normalized.length() > 80) {
            return normalized.substring(0, 80) + "...";
        }
        return normalized;
    }

    private void appendArtifacts(StringBuilder builder, List<ArtifactRecord> artifacts) {
        List<ArtifactRecord> shareableArtifacts = shareableArtifacts(artifacts);
        List<ArtifactRecord> summaryArtifacts = summaryArtifacts(artifacts);
        if (shareableArtifacts.isEmpty() && summaryArtifacts.isEmpty()) {
            builder.append("\n\n暂时没有拿到可展示的产物。");
            return;
        }
        if (!shareableArtifacts.isEmpty()) {
            builder.append("\n\n产物：");
            int limit = Math.min(MAX_ARTIFACTS, shareableArtifacts.size());
            for (int index = 0; index < limit; index++) {
                ArtifactRecord artifact = shareableArtifacts.get(index);
                if (artifact == null) {
                    continue;
                }
                builder.append("\n").append(index + 1).append(". ")
                        .append(firstNonBlank(artifact.getTitle(), artifact.getArtifactId(), "未命名产物"))
                        .append("\n   ").append(artifact.getUrl().trim());
            }
            if (shareableArtifacts.size() > limit) {
                builder.append("\n还有 ").append(shareableArtifacts.size() - limit).append(" 个链接产物可在任务详情里查看。");
            }
        }
        appendSummaryPreview(builder, summaryArtifacts);
    }

    private void appendSummaryPreview(StringBuilder builder, List<ArtifactRecord> summaryArtifacts) {
        if (summaryArtifacts == null || summaryArtifacts.isEmpty()) {
            return;
        }
        ArtifactRecord summary = summaryArtifacts.get(summaryArtifacts.size() - 1);
        String preview = summary == null ? null : summary.getPreview();
        if (!hasText(preview)) {
            return;
        }
        String label = hasText(summary == null ? null : summary.getTitle())
                ? "SUMMARY（" + summary.getTitle().trim() + "）"
                : "SUMMARY";
        builder.append("\n\n").append(label).append("：\n")
                .append(truncateSummaryBody(stripMarkdownHeading(preview), MAX_SUMMARY_BODY_CHARS));
    }

    private void appendNextStepRecommendations(StringBuilder builder, TaskResultEvaluation evaluation) {
        if (evaluation == null || evaluation.getVerdict() != ResultVerdictEnum.PASS) {
            return;
        }
        List<NextStepRecommendation> recommendations = evaluation.getNextStepRecommendations();
        if (recommendations == null || recommendations.isEmpty()) {
            return;
        }
        builder.append("\n\n推荐下一步：");
        int index = 1;
        for (NextStepRecommendation recommendation : recommendations.stream().limit(2).toList()) {
            if (recommendation == null) {
                continue;
            }
            builder.append("\n").append(index++).append(". ")
                    .append(firstNonBlank(recommendation.getTitle(), "继续推进下一步"));
            if (hasText(recommendation.getReason())) {
                builder.append("：").append(recommendation.getReason().trim());
            }
            if (hasText(recommendation.getSuggestedUserInstruction())) {
                builder.append("\n  直接回复：")
                        .append(recommendation.getSuggestedUserInstruction().trim());
            }
        }
    }

    private void appendFailureDetails(StringBuilder builder, TaskRuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        List<String> details = failedStepDetails(snapshot).stream()
                .filter(this::hasText)
                .distinct()
                .limit(2)
                .toList();
        if (details.isEmpty()) {
            details = failedEventDetails(snapshot).stream()
                    .filter(this::hasText)
                    .distinct()
                    .limit(2)
                    .toList();
        }
        if (details.isEmpty()) {
            return;
        }
        builder.append("\n\n失败位置：");
        for (String detail : details) {
            builder.append("\n- ").append(detail);
        }
    }

    private List<String> failedStepDetails(TaskRuntimeSnapshot snapshot) {
        if (snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return List.of();
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.FAILED)
                .map(this::formatFailedStep)
                .filter(this::hasText)
                .toList();
    }

    private String formatFailedStep(TaskStepRecord step) {
        String stepName = firstNonBlank(step.getName(), step.getType() == null ? null : step.getType().name(), step.getStepId(), "未命名步骤");
        String reason = firstNonBlank(step.getOutputSummary(), step.getInputSummary());
        if (!hasText(reason)) {
            return stepName;
        }
        return stepName + "：" + limitOneLine(reason, MAX_FAILURE_DETAIL_CHARS);
    }

    private List<String> failedEventDetails(TaskRuntimeSnapshot snapshot) {
        if (snapshot.getEvents() == null || snapshot.getEvents().isEmpty()) {
            return List.of();
        }
        return snapshot.getEvents().stream()
                .filter(event -> event != null
                        && (event.getType() == TaskEventTypeEnum.STEP_FAILED
                        || event.getType() == TaskEventTypeEnum.TASK_FAILED))
                .map(this::formatFailedEvent)
                .filter(this::hasText)
                .toList();
    }

    private String formatFailedEvent(TaskEventRecord event) {
        String reason = readablePayload(event.getPayloadJson());
        if (!hasText(reason)) {
            return null;
        }
        String prefix = hasText(event.getStepId()) ? event.getStepId() : "任务执行";
        return prefix + "：" + limitOneLine(reason, MAX_FAILURE_DETAIL_CHARS);
    }

    private List<ArtifactRecord> shareableArtifacts(List<ArtifactRecord> artifacts) {
        return artifacts == null ? List.of() : artifacts.stream()
                .filter(artifact -> artifact != null && hasText(artifact.getUrl()))
                .filter(artifact -> artifact.getType() != ArtifactTypeEnum.DIAGRAM)
                .toList();
    }

    private List<ArtifactRecord> summaryArtifacts(List<ArtifactRecord> artifacts) {
        return artifacts == null ? List.of() : artifacts.stream()
                .filter(artifact -> artifact != null && artifact.getType() == ArtifactTypeEnum.SUMMARY)
                .filter(artifact -> hasText(artifact.getPreview()))
                .toList();
    }

    private TaskRuntimeSnapshot shareableStatusSnapshot(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return TaskRuntimeSnapshot.builder()
                .task(snapshot.getTask())
                .steps(snapshot.getSteps())
                .artifacts(statusVisibleArtifacts(snapshot.getArtifacts()))
                .events(snapshot.getEvents())
                .build();
    }

    private List<ArtifactRecord> statusVisibleArtifacts(List<ArtifactRecord> artifacts) {
        return artifacts == null ? List.of() : artifacts.stream()
                .filter(artifact -> artifact != null)
                .filter(artifact -> hasText(artifact.getUrl())
                        || artifact.getType() == ArtifactTypeEnum.SUMMARY && hasText(artifact.getPreview()))
                .toList();
    }

    private String stripMarkdownHeading(String value) {
        if (!hasText(value)) {
            return value;
        }
        return value.lines()
                .filter(line -> !line.trim().startsWith("# "))
                .reduce((left, right) -> left + "\n" + right)
                .orElse(value)
                .trim();
    }

    private String truncateSummaryBody(String value, int maxLength) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength
                ? trimmed
                : trimmed.substring(0, maxLength) + "\n\n（内容太长，IM 先发到这里；完整内容可在任务详情中查看。）";
    }

    private String readablePayload(String payloadJson) {
        if (!hasText(payloadJson) || "null".equalsIgnoreCase(payloadJson.trim())) {
            return null;
        }
        String value = payloadJson.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
        return value;
    }

    private String limitOneLine(String value, int maxLength) {
        if (!hasText(value)) {
            return "";
        }
        String compact = value.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(value.trim());
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "...";
    }

    private void send(PlanTaskSession session, String text, String suffix) {
        TaskInputContext context = session.getInputContext();
        if (!hasText(text)) {
            return;
        }
        String idempotencyKey = reviewIdempotencyKey(session, suffix);
        try {
            if (isP2P(context) && hasText(context.getSenderOpenId())) {
                replyTool.sendPrivateText(context.getSenderOpenId(), text, idempotencyKey);
                return;
            }
            if (hasText(context.getMessageId())) {
                replyTool.replyText(context.getMessageId(), text, idempotencyKey);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to send planner review IM notification: taskId={}", session.getTaskId(), exception);
            enqueueRetry(context, text, idempotencyKey);
        }
    }

    private void enqueueRetry(TaskInputContext context, String text, String idempotencyKey) {
        if (retryService == null || context == null) {
            return;
        }
        if (isP2P(context) && hasText(context.getSenderOpenId())) {
            retryService.enqueuePrivateText(context.getSenderOpenId(), text, idempotencyKey);
            return;
        }
        if (hasText(context.getMessageId())) {
            retryService.enqueueReplyText(context.getMessageId(), text, idempotencyKey);
        }
    }

    private String reviewIdempotencyKey(PlanTaskSession session, String suffix) {
        TaskInputContext context = session == null ? null : session.getInputContext();
        String source = "planner-review::"
                + firstNonBlank(session == null ? null : session.getTaskId(), "unknown")
                + "::v" + (session == null ? 0 : session.getVersion())
                + "::" + firstNonBlank(context == null ? null : context.getMessageId(), "no-message")
                + "::" + firstNonBlank(suffix, "notify");
        String normalizedSuffix = firstNonBlank(suffix, "notify")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        if (normalizedSuffix.length() > 10) {
            normalizedSuffix = normalizedSuffix.substring(0, 10);
        }
        String key = "pr-" + sha256Hex(source).substring(0, 32) + "-" + normalizedSuffix;
        if (key.length() <= MAX_LARK_UUID_LENGTH) {
            return key;
        }
        return key.substring(0, MAX_LARK_UUID_LENGTH);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private boolean isP2P(TaskInputContext context) {
        return context != null && "p2p".equalsIgnoreCase(context.getChatType());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
