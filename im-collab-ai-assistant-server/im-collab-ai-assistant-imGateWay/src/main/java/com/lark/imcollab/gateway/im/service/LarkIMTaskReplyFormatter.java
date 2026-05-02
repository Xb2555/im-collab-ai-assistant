package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LarkIMTaskReplyFormatter {

    private static final int MAX_IM_STEPS = 4;
    private static final int MAX_CLARIFICATION_QUESTIONS = 2;

    public String planReady(PlanTaskSession session) {
        List<UserPlanCard> cards = cards(session);
        StringBuilder builder = new StringBuilder("我准备这样推进：");
        appendCardSummary(builder, cards);
        builder.append("\n\n没问题的话回复“开始执行”。要改的话直接说");
        appendEditHint(builder, cards);
        builder.append("。");
        return builder.toString();
    }

    public String planAdjusted(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder("计划已更新，我会按这个顺序推进：");
        List<UserPlanCard> cards = cards(session);
        appendCardSummary(builder, cards);
        builder.append("\n\n没问题的话回复“开始执行”。要继续改的话也可以直接说");
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
            return stripClarificationPrefix(questions.get(0));
        }
        StringBuilder builder = new StringBuilder("我先确认两点：");
        for (int index = 0; index < Math.min(MAX_CLARIFICATION_QUESTIONS, questions.size()); index++) {
            builder.append("\n").append(index + 1).append(". ").append(stripClarificationPrefix(questions.get(index)));
        }
        return builder.toString();
    }

    public String executionStarted(TaskRuntimeSnapshot snapshot) {
        String nextStep = nextStepName(snapshot);
        if (hasText(nextStep)) {
            return "好的，开始执行。我会先处理：" + nextStep + "。完成后继续推进后续步骤。";
        }
        return "好的，开始执行。我会按计划推进，并持续更新任务进度。";
    }

    public String retryStarted(TaskRuntimeSnapshot snapshot) {
        String nextStep = nextStepName(snapshot);
        if (hasText(nextStep)) {
            return "好的，我会从失败的步骤重新试一次。当前先处理：" + nextStep + "。";
        }
        return "好的，我会从失败的步骤重新试一次。";
    }

    public String retryUnavailable(TaskRuntimeSnapshot snapshot) {
        TaskStatusEnum status = snapshot == null || snapshot.getTask() == null ? null : snapshot.getTask().getStatus();
        if (status == TaskStatusEnum.FAILED) {
            return "这个任务现在可以重试，但我还没能成功提交执行。你可以稍后再试一次。";
        }
        return "当前任务不是失败状态，不需要重试。你可以查看进度或继续调整计划。";
    }

    public String status(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getTask() == null) {
            return "我还没有找到这个会话里的任务进度。你可以先发一个任务给我。";
        }
        TaskRecord task = snapshot.getTask();
        List<TaskStepRecord> steps = activeSteps(snapshot.getSteps());
        long completed = steps.stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.COMPLETED)
                .count();
        StringBuilder builder = new StringBuilder();
        builder.append("任务状态：").append(readableStatus(task));
        String currentStep = currentStepName(steps);
        if (task.getStatus() == com.lark.imcollab.common.model.enums.TaskStatusEnum.WAITING_APPROVAL) {
            builder.append("\n等待你确认计划");
            if (hasText(currentStep)) {
                builder.append("\n确认后下一步：").append(currentStep);
            }
        } else if (task.getStatus() == TaskStatusEnum.COMPLETED) {
            if (!steps.isEmpty() && completed < steps.size()) {
                builder.append("\n主执行链路已完成，部分计划步骤可能已合并到同一产物中。");
            }
        } else if (hasText(currentStep)) {
            builder.append("\n当前步骤：").append(currentStep);
        }
        if (!steps.isEmpty()) {
            if (task.getStatus() == TaskStatusEnum.COMPLETED && completed < steps.size()) {
                builder.append("\n计划项：").append(steps.size()).append(" 个");
            } else {
                builder.append("\n步骤进度：").append(completed).append("/").append(steps.size());
            }
        }
        int artifactCount = defaultList(snapshot.getArtifacts()).size();
        if (artifactCount > 0) {
            builder.append("\n已有产物：").append(artifactCount).append(" 个");
        }
        return builder.toString();
    }

    public String failure(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        String reason = session == null ? null : session.getTransitionReason();
        StringBuilder builder = new StringBuilder("这次处理没有成功");
        if (hasText(reason)) {
            builder.append("：").append(reason.trim());
        } else {
            builder.append("。");
        }
        String currentStep = snapshot == null ? null : currentStepName(defaultList(snapshot.getSteps()));
        if (hasText(currentStep)) {
            builder.append("\n卡住的位置：").append(currentStep);
        }
        builder.append("\n你可以直接换个说法继续修改，或者回复“进度怎么样”查看当前状态。");
        return builder.toString();
    }

    public String fullPlan(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder("详细计划如下：");
        List<UserPlanCard> cards = cards(session);
        if (cards.isEmpty()) {
            builder.append("\n当前还没有生成具体步骤。");
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
        return "任务已取消，后续不会继续规划或执行。";
    }

    public String taskAccepted() {
        return "收到，我先理解需求并生成计划。你可以随时回复“进度怎么样”查看当前状态。";
    }

    public String uncertainIntent() {
        return "我先按当前计划停在确认这一步。想看细节、调整步骤，或开始执行，都可以直接说。";
    }

    public String uncertainIntent(PlanTaskSession session) {
        String reply = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getAssistantReply();
        return hasText(reply) ? reply.trim() : uncertainIntent();
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
            builder.append("\n").append(index + 1).append(". ").append(toNaturalStep(card, index));
        }
        if (cards.size() > limit) {
            builder.append("\n").append(limit + 1).append(". 还有 ").append(cards.size() - limit).append(" 个后续步骤会继续串起来");
        }
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

    private List<String> suggestEdits(List<UserPlanCard> cards) {
        List<String> suggestions = new ArrayList<>();
        boolean hasDoc = hasType(cards, "DOC");
        boolean hasPpt = hasType(cards, "PPT");
        boolean hasSummary = hasType(cards, "SUMMARY");
        boolean mentionsRisk = containsKeyword(cards, "风险");
        boolean mentionsMermaid = containsKeyword(cards, "mermaid") || containsKeyword(cards, "架构图");
        boolean mentionsBoss = containsKeyword(cards, "老板") || containsKeyword(cards, "汇报");

        if (hasDoc && !mentionsRisk) {
            suggestions.add("补一段风险清单");
        }
        if (hasDoc && !hasPpt && mentionsBoss) {
            suggestions.add("再加一份汇报PPT初稿");
        }
        if (hasDoc && !hasSummary) {
            suggestions.add("最后补一段项目进展摘要");
        }
        if (hasDoc && !mentionsMermaid) {
            suggestions.add("文档里加一张Mermaid架构图");
        }
        if (hasPpt && !hasDoc) {
            suggestions.add("先补一份配套文档");
        }
        if (hasSummary && !hasDoc) {
            suggestions.add("再整理成一份文档");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("补一段风险清单");
        }
        return suggestions.stream().distinct().limit(2).toList();
    }

    private String toNaturalStep(UserPlanCard card, int index) {
        String title = normalizeActionTitle(card);
        if (startsWithSequenceWord(title)) {
            return title;
        }
        String lowerTitle = title.toLowerCase();
        if (index == 0) {
            return "先" + title;
        }
        if (lowerTitle.contains("ppt") || lowerTitle.contains("汇报")) {
            return "再" + title;
        }
        if (lowerTitle.contains("摘要") || lowerTitle.contains("总结")) {
            return "最后" + title;
        }
        return title;
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

    private boolean hasType(List<UserPlanCard> cards, String typeName) {
        if (cards == null || cards.isEmpty() || !hasText(typeName)) {
            return false;
        }
        return cards.stream()
                .anyMatch(card -> card != null
                        && card.getType() != null
                        && typeName.equalsIgnoreCase(card.getType().name()));
    }

    private boolean containsKeyword(List<UserPlanCard> cards, String keyword) {
        if (cards == null || cards.isEmpty() || !hasText(keyword)) {
            return false;
        }
        String normalizedKeyword = keyword.trim().toLowerCase();
        return cards.stream().anyMatch(card -> containsText(card, normalizedKeyword));
    }

    private boolean containsText(UserPlanCard card, String keyword) {
        if (card == null || !hasText(keyword)) {
            return false;
        }
        return lower(card.getTitle()).contains(keyword)
                || lower(card.getDescription()).contains(keyword);
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase();
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
