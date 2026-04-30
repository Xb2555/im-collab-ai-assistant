package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
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
        builder.append("\n\n没问题的话回复“开始执行”。要改的话直接说，比如“加一段群内摘要”。");
        return builder.toString();
    }

    public String planAdjusted(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder("计划已更新，我会按这个顺序推进：");
        appendCardSummary(builder, cards(session));
        builder.append("\n\n没问题的话回复“开始执行”。");
        return builder.toString();
    }

    public String clarification(PlanTaskSession session) {
        List<String> questions = clarificationQuestions(session);
        if (questions.isEmpty()) {
            return null;
        }
        if (questions.size() == 1) {
            return "我还需要确认一下：" + questions.get(0);
        }
        StringBuilder builder = new StringBuilder("我还需要确认一下：");
        for (int index = 0; index < Math.min(MAX_CLARIFICATION_QUESTIONS, questions.size()); index++) {
            builder.append("\n").append(index + 1).append(". ").append(questions.get(index));
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
        } else if (hasText(currentStep)) {
            builder.append("\n当前步骤：").append(currentStep);
        }
        if (!steps.isEmpty()) {
            builder.append("\n步骤进度：").append(completed).append("/").append(steps.size());
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
        return "我先保留当前计划。你可以继续补充想改的点；不用改的话回复“开始执行”就行。";
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

    private String toNaturalStep(UserPlanCard card, int index) {
        String title = firstNonBlank(card.getTitle(), "处理下一步");
        String lowerTitle = title.toLowerCase();
        if (index == 0 && (lowerTitle.contains("文档") || lowerTitle.contains("doc"))) {
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

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<TaskStepRecord> activeSteps(List<TaskStepRecord> steps) {
        return defaultList(steps).stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .toList();
    }
}
