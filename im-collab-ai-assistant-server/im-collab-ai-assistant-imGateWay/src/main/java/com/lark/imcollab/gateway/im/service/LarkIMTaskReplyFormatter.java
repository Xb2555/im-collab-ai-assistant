package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.util.PlanCapabilityHints;
import com.lark.imcollab.common.util.TriggerGuidanceResolver;
import com.lark.imcollab.common.model.vo.TriggerGuidanceVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LarkIMTaskReplyFormatter {

    private static final int MAX_IM_STEPS = 4;
    private static final int MAX_IM_ARTIFACTS = 5;
    private static final int MAX_CLARIFICATION_QUESTIONS = 2;
    private static final int MAX_FAILURE_DETAIL_CHARS = 180;
    private final TriggerGuidanceResolver triggerGuidanceResolver = new TriggerGuidanceResolver();

    public String planReady(PlanTaskSession session) {
        List<UserPlanCard> cards = cards(session);
        StringBuilder builder = new StringBuilder("🧭 我准备这样推进：");
        appendCardSummary(builder, cards);
        appendCapabilityHints(builder, cards);
        appendInteractionGuidance(builder, session);
        builder.append("\n\n🚀 你确认没问题我就开工。要改的话直接说");
        appendEditHint(builder, cards);
        builder.append("。");
        return builder.toString();
    }

    public String planAdjusted(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder("🔄 计划已更新，我会按这个顺序推进：");
        List<UserPlanCard> cards = cards(session);
        appendCardSummary(builder, cards);
        appendCapabilityHints(builder, cards);
        appendInteractionGuidance(builder, session);
        builder.append("\n\n🚀 你确认没问题我就继续推进。要继续改的话也可以直接说");
        appendEditHint(builder, cards);
        builder.append("。");
        return builder.toString();
    }

    public String clarification(PlanTaskSession session) {
        List<String> questions = clarificationQuestions(session);
        if (questions.isEmpty()) {
            return null;
        }
        if (questions.size() == 1) {
            return "❓ 我还差这一点信息：" + stripClarificationPrefix(questions.get(0));
        }
        StringBuilder builder = new StringBuilder("❓ 我先确认两点：");
        for (int index = 0; index < Math.min(MAX_CLARIFICATION_QUESTIONS, questions.size()); index++) {
            builder.append("\n").append(index + 1).append(". ").append(stripClarificationPrefix(questions.get(index)));
        }
        return builder.toString();
    }

    public String executionStarted(TaskRuntimeSnapshot snapshot) {
        String nextStep = nextStepName(snapshot);
        if (hasText(nextStep)) {
            return "🚀 好，我开始推进了。先处理：" + nextStep
                    + "。您可以在任务执行途中打断任务，并调整计划。后面有进展我会继续同步。";
        }
        return "🚀 好，我开始推进了。接下来会按计划往下做。您可以在任务执行途中打断任务，并调整计划，我也会持续同步进度。";
    }

    public String retryStarted(TaskRuntimeSnapshot snapshot) {
        String nextStep = nextStepName(snapshot);
        if (hasText(nextStep)) {
            return "🔁 好，我接着重试这一步。当前先处理：" + nextStep + "。";
        }
        return "🔁 好，我接着从失败的地方重试。";
    }

    public String retryUnavailable(TaskRuntimeSnapshot snapshot) {
        TaskStatusEnum status = snapshot == null || snapshot.getTask() == null ? null : snapshot.getTask().getStatus();
        if (status == TaskStatusEnum.FAILED) {
            return "⚠️ 我想帮你把这个任务重新拉起来，但这次还没提交成功。你稍后再试一次就行。";
        }
        return "ℹ️ 当前任务不在失败状态，暂时不用重试。想看进度或继续调整都可以直接说。";
    }

    public String status(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getTask() == null) {
            return "🔎 这个会话里我还没查到任务进度。你可以直接给我一个新任务。";
        }
        TaskRecord task = snapshot.getTask();
        List<TaskStepRecord> steps = activeSteps(snapshot.getSteps());
        List<ArtifactRecord> primaryArtifacts = defaultList(snapshot.getArtifacts()).stream()
                .filter(this::visiblePrimaryArtifact)
                .toList();
        long iterationRecordCount = defaultList(snapshot.getArtifacts()).stream()
                .filter(this::visibleIterationRecordArtifact)
                .count();
        long completed = steps.stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.COMPLETED)
                .count();
        StringBuilder builder = new StringBuilder();
        builder.append("📌 任务状态：").append(readableStatus(task));
        String currentStep = currentStepName(steps);
        if (task.getStatus() == com.lark.imcollab.common.model.enums.TaskStatusEnum.WAITING_APPROVAL) {
            builder.append("\n⏳ 等待你确认计划");
            if (hasText(currentStep)) {
                builder.append("\n➡️ 确认后下一步：").append(currentStep);
            }
        } else if (task.getStatus() == TaskStatusEnum.COMPLETED) {
            if (!steps.isEmpty() && completed < steps.size()) {
                builder.append("\n✅ 主执行链路已完成，部分计划步骤可能已合并到同一产物中。");
            }
        } else if (hasText(currentStep)) {
            builder.append("\n⏳ 当前步骤：").append(currentStep);
        }
        if (!steps.isEmpty()) {
            if (task.getStatus() == TaskStatusEnum.COMPLETED && completed < steps.size()) {
                builder.append("\n🧩 计划项：").append(steps.size()).append(" 个");
            } else {
                builder.append("\n📊 步骤进度：").append(completed).append("/").append(steps.size());
            }
        }
        int artifactCount = primaryArtifacts.size();
        if (artifactCount > 0) {
            builder.append("\n📎 已有产物：").append(artifactCount).append(" 个");
            appendArtifactList(builder, primaryArtifacts);
        }
        if (iterationRecordCount > 0) {
            builder.append("\n📝 迭代记录：").append(iterationRecordCount).append(" 条");
        }
        return builder.toString();
    }

    private void appendArtifactList(StringBuilder builder, List<ArtifactRecord> artifacts) {
        int index = 1;
        for (ArtifactRecord artifact : artifacts.stream().filter(this::visiblePrimaryArtifact).limit(MAX_IM_ARTIFACTS).toList()) {
            builder.append("\n").append(index++).append(". 📄 ");
            if (artifact.getType() != null) {
                builder.append("[").append(artifact.getType().name()).append("] ");
            }
            builder.append(firstNonBlank(artifact.getTitle(), artifact.getPreview(), "未命名产物"));
            if (hasText(artifact.getUrl())) {
                builder.append("\n   ").append(artifact.getUrl().trim());
            }
        }
        long remaining = artifacts.stream().filter(this::visiblePrimaryArtifact).count() - MAX_IM_ARTIFACTS;
        if (remaining > 0) {
            builder.append("\n📦 还有 ").append(remaining).append(" 个产物可在任务工作台查看。");
        }
    }

    private boolean visiblePrimaryArtifact(ArtifactRecord artifact) {
        return visibleArtifact(artifact) && !visibleIterationRecordArtifact(artifact);
    }

    private boolean visibleIterationRecordArtifact(ArtifactRecord artifact) {
        if (!visibleArtifact(artifact) || artifact.getType() != com.lark.imcollab.common.model.enums.ArtifactTypeEnum.SUMMARY) {
            return false;
        }
        String title = firstNonBlank(artifact.getTitle(), artifact.getPreview(), "");
        return title.startsWith("文档迭代结果") || title.startsWith("文档迭代待审批计划");
    }

    private boolean visibleArtifact(ArtifactRecord artifact) {
        return artifact != null && (hasText(artifact.getUrl()) || hasText(artifact.getTitle()) || hasText(artifact.getPreview()));
    }

    public String failure(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        String reason = session == null ? null : session.getTransitionReason();
        StringBuilder builder = new StringBuilder("⚠️ 这次处理没有成功");
        if (hasText(reason)) {
            builder.append("：").append(reason.trim());
        } else {
            builder.append("。");
        }
        String currentStep = snapshot == null ? null : currentStepName(defaultList(snapshot.getSteps()));
        if (hasText(currentStep)) {
            builder.append("\n📍 卡住的位置：").append(currentStep);
        }
        List<String> failedDetails = failedStepDetails(snapshot);
        if (!failedDetails.isEmpty()) {
            builder.append("\n🧾 具体原因：");
            for (String detail : failedDetails.stream().limit(2).toList()) {
                builder.append("\n- ").append(detail);
            }
        }
        builder.append("\n💬 你可以直接换个说法继续修改，或者回复“进度怎么样”查看当前状态。");
        return builder.toString();
    }

    public String fullPlan(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder("🧾 详细计划如下：");
        List<UserPlanCard> cards = cards(session);
        if (cards.isEmpty()) {
            builder.append("\n🔎 当前还没有生成具体步骤。");
            return builder.toString();
        }
        for (int index = 0; index < cards.size(); index++) {
            UserPlanCard card = cards.get(index);
            if (card == null) {
                continue;
            }
            builder.append("\n").append(index + 1).append(". ");
            if (card.getType() != null) {
                builder.append("[").append(card.getType().name()).append("] ");
            }
            builder.append(firstNonBlank(card.getTitle(), "未命名步骤"));
            if (hasText(card.getDescription())) {
                builder.append(" - ").append(card.getDescription().trim());
            }
        }
        return builder.toString();
    }

    public String taskCancelled() {
        return "🛑 我先把这个任务停下来了，后续不会再继续规划或执行。";
    }

    public String taskAccepted() {
        return "👀 我先接住这条需求，整理完重点后就给你计划。你随时都可以问我进度。";
    }

    public String uncertainIntent() {
        return "🤔 我先把当前任务停在这里等你一句话。想看细节、继续调整，或者直接让我开工，都可以直说。";
    }

    public String uncertainIntent(PlanTaskSession session) {
        String reply = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getAssistantReply();
        return hasText(reply) ? assistantReply(reply) : uncertainIntent();
    }

    public String assistantReply(String reply) {
        if (!hasText(reply)) {
            return reply;
        }
        String trimmed = reply.trim();
        return startsWithIcon(trimmed) ? trimmed : "💬 " + trimmed;
    }

    public String completedArtifactEditApplied(String detail) {
        String noun = completedArtifactNoun(detail);
        String phrase = "按现有" + noun + "修改处理";
        StringBuilder builder = new StringBuilder("💬 当前上一轮任务已完成，我").append(phrase).append("。");
        appendDetail(builder, detail, "当前上一轮任务已完成", phrase);
        return builder.toString();
    }

    public String completedArtifactEditClarification(String detail) {
        String noun = completedArtifactNoun(detail);
        String phrase = "按现有" + noun + "修改处理";
        StringBuilder builder = new StringBuilder("💬 当前上一轮任务已完成，我")
                .append(phrase)
                .append("，这次不是重新启动执行。");
        appendDetail(builder, detail, "当前上一轮任务已完成", phrase, "不是重新启动执行");
        return builder.toString();
    }

    private String completedArtifactNoun(String detail) {
        if (!hasText(detail)) {
            return "产物";
        }
        String normalized = detail.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("DOC") || detail.contains("文档")) {
            return "文档";
        }
        if (normalized.contains("PPT") || detail.contains("幻灯片") || detail.contains("演示稿")) {
            return "PPT";
        }
        return "产物";
    }

    private void appendCardSummary(StringBuilder builder, List<UserPlanCard> cards) {
        if (cards.isEmpty()) {
            builder.append("\n1. 先理解你的需求并生成可执行步骤");
            return;
        }
        int limit = Math.min(MAX_IM_STEPS, cards.size());
        for (int index = 0; index < limit; index++) {
            UserPlanCard card = cards.get(index);
            if (card == null) {
                continue;
            }
            builder.append("\n").append(index + 1).append(". ").append(toNaturalStep(card, index, limit));
        }
        if (cards.size() > limit) {
            builder.append("\n").append(limit + 1).append(". 还有 ").append(cards.size() - limit).append(" 个后续步骤会继续串起来");
        }
    }

    private void appendInteractionGuidance(StringBuilder builder, PlanTaskSession session) {
        List<TriggerGuidanceVO> guidance = triggerGuidanceResolver.resolve(session);
        if (guidance.isEmpty()) {
            return;
        }
        builder.append("\n\n💡 你也可以直接这样说：");
        for (TriggerGuidanceVO item : guidance) {
            if (item == null || !item.visible()) {
                continue;
            }
            builder.append("\n- ")
                    .append(firstNonBlank(item.label(), "继续修改"))
                    .append("：")
                    .append(firstNonBlank(item.description(), "直接说你想改哪一步、改成什么"));
        }
    }

    private void appendDetail(StringBuilder builder, String detail, String... suppressedFragments) {
        if (!hasText(detail)) {
            return;
        }
        String normalized = stripLeadingIcon(detail.trim());
        for (String fragment : suppressedFragments) {
            if (hasText(fragment) && normalized.contains(fragment)) {
                normalized = normalized.replace(fragment, "").trim();
            }
        }
        normalized = normalized.replaceFirst("^[，。；;、\\s]+", "").trim();
        if (!hasText(normalized)) {
            return;
        }
        builder.append("\n").append(normalized);
    }

    private String stripLeadingIcon(String text) {
        if (!hasText(text)) {
            return text;
        }
        return text.replaceFirst("^[^\\p{IsHan}\\p{L}\\p{N}]+\\s*", "").trim();
    }

    private void appendEditHint(StringBuilder builder, List<UserPlanCard> cards) {
        List<String> suggestions = suggestEdits(cards);
        if (suggestions.isEmpty()) {
            return;
        }
        builder.append("，比如");
        if (suggestions.size() == 1) {
            builder.append("“").append(suggestions.get(0)).append("”");
            return;
        }
        builder.append("“").append(suggestions.get(0)).append("”或“").append(suggestions.get(1)).append("”");
    }

    private void appendCapabilityHints(StringBuilder builder, List<UserPlanCard> cards) {
        List<String> hints = PlanCapabilityHints.fromPlanCards(cards);
        if (hints.isEmpty()) {
            return;
        }
        builder.append("\n\n✨ 你还可以指定：");
        for (String hint : hints.stream().limit(3).toList()) {
            builder.append("\n- ").append(hint);
        }
    }

    private List<String> suggestEdits(List<UserPlanCard> cards) {
        List<String> suggestions = new ArrayList<>();
        boolean hasDoc = hasType(cards, "DOC");
        boolean hasPpt = hasType(cards, "PPT");
        boolean hasSummary = hasType(cards, "SUMMARY");

        if (hasDoc) {
            suggestions.add("文档里补一段风险清单");
            suggestions.add("文档里加一段行动项");
        }
        if (hasPpt) {
            suggestions.add("PPT控制在5页以内");
            suggestions.add("PPT改成面向管理层汇报");
        }
        if (hasDoc && !hasPpt) {
            suggestions.add("再基于文档生成一份PPT");
        }
        if (hasPpt && !hasDoc) {
            suggestions.add("先补一份配套文档");
        }
        if (hasSummary && !hasDoc) {
            suggestions.add("再整理成一份文档");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("补充受众和输出格式");
        }
        return suggestions.stream().distinct().limit(2).toList();
    }

    private String toNaturalStep(UserPlanCard card, int index, int total) {
        String title = normalizeActionTitle(card);
        if (startsWithSequenceWord(title)) {
            return title;
        }
        if (index == 0) {
            return "先" + title;
        }
        if (total > 2 && index == total - 1) {
            return "最后" + title;
        }
        return "再" + title;
    }

    private boolean startsWithSequenceWord(String title) {
        if (!hasText(title)) {
            return false;
        }
        String trimmed = title.trim();
        return trimmed.startsWith("先")
                || trimmed.startsWith("再")
                || trimmed.startsWith("然后")
                || trimmed.startsWith("最后");
    }

    private String normalizeActionTitle(UserPlanCard card) {
        String title = firstNonBlank(card == null ? null : card.getTitle(), "处理下一步");
        if (startsWithActionVerb(title)) {
            return title;
        }
        if (card == null || card.getType() == null) {
            return "处理" + title;
        }
        return switch (card.getType()) {
            case DOC -> "生成" + title;
            case PPT -> "生成" + title;
            case SUMMARY -> "生成" + title;
        };
    }

    private boolean startsWithActionVerb(String title) {
        if (!hasText(title)) {
            return false;
        }
        String trimmed = title.trim();
        return trimmed.startsWith("生成")
                || trimmed.startsWith("整理")
                || trimmed.startsWith("提炼")
                || trimmed.startsWith("转换")
                || trimmed.startsWith("更新")
                || trimmed.startsWith("补充")
                || trimmed.startsWith("撰写")
                || trimmed.startsWith("基于")
                || trimmed.startsWith("输出");
    }

    private String currentStepName(List<TaskStepRecord> steps) {
        for (TaskStepRecord step : steps) {
            if (step != null && step.getStatus() == StepStatusEnum.RUNNING && hasText(step.getName())) {
                return step.getName().trim();
            }
        }
        for (TaskStepRecord step : steps) {
            if (step != null
                    && (step.getStatus() == StepStatusEnum.READY || step.getStatus() == StepStatusEnum.PENDING)
                    && hasText(step.getName())) {
                return step.getName().trim();
            }
        }
        return null;
    }

    private String nextStepName(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return currentStepName(activeSteps(snapshot.getSteps()));
    }

    private List<String> failedStepDetails(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getSteps() == null) {
            return List.of();
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.FAILED)
                .map(step -> {
                    String name = firstNonBlank(step.getName(), step.getType() == null ? null : step.getType().name(), step.getStepId(), "未命名步骤");
                    String reason = firstNonBlank(step.getOutputSummary(), step.getInputSummary());
                    return hasText(reason)
                            ? name + "：" + limitOneLine(reason, MAX_FAILURE_DETAIL_CHARS)
                            : name;
                })
                .filter(this::hasText)
                .distinct()
                .toList();
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

    private String readableStatus(TaskRecord task) {
        if (task == null || task.getStatus() == null) {
            return "处理中";
        }
        return switch (task.getStatus()) {
            case WAITING_APPROVAL -> "等待你确认";
            case EXECUTING -> "正在执行";
            case CLARIFYING -> "等待补充信息";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            default -> "处理中";
        };
    }

    private List<String> clarificationQuestions(PlanTaskSession session) {
        List<String> questions = new ArrayList<>();
        if (session != null && session.getActivePromptSlots() != null) {
            for (PromptSlotState slot : session.getActivePromptSlots()) {
                if (slot != null && !slot.isAnswered() && hasText(slot.getPrompt())) {
                    questions.add(slot.getPrompt().trim());
                }
            }
        }
        if (questions.isEmpty() && session != null && session.getClarificationQuestions() != null) {
            for (String question : session.getClarificationQuestions()) {
                if (hasText(question)) {
                    questions.add(question.trim());
                }
            }
        }
        return questions.stream().distinct().limit(MAX_CLARIFICATION_QUESTIONS).toList();
    }

    private List<UserPlanCard> cards(PlanTaskSession session) {
        if (session == null) {
            return List.of();
        }
        if (session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            return visibleCards(session.getPlanCards());
        }
        PlanBlueprint blueprint = session.getPlanBlueprint();
        return blueprint == null || blueprint.getPlanCards() == null ? List.of() : visibleCards(blueprint.getPlanCards());
    }

    private List<UserPlanCard> visibleCards(List<UserPlanCard> cards) {
        return defaultList(cards).stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
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

    private boolean startsWithIcon(String value) {
        if (!hasText(value)) {
            return false;
        }
        int codePoint = value.trim().codePointAt(0);
        return Character.getType(codePoint) == Character.OTHER_SYMBOL
                || Character.getType(codePoint) == Character.MATH_SYMBOL;
    }

    private boolean hasType(List<UserPlanCard> cards, String typeName) {
        if (cards == null || cards.isEmpty() || !hasText(typeName)) {
            return false;
        }
        return cards.stream()
                .anyMatch(card -> card != null
                        && card.getType() != null
                        && typeName.equalsIgnoreCase(card.getType().name()));
    }

    private String stripClarificationPrefix(String question) {
        if (!hasText(question)) {
            return "";
        }
        return question.trim()
                .replaceFirst("^(我还需要确认一下|我需要确认一下|还需要确认一下|请确认一下)[:：，,\\s]*", "")
                .trim();
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<TaskStepRecord> activeSteps(List<TaskStepRecord> steps) {
        return defaultList(steps).stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .toList();
    }
}
