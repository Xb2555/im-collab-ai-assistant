package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class HardRuleIntentClassifier {

    private static final Set<String> PLAN_QUERY_SENTENCES = Set.of(
            "计划",
            "完整计划",
            "详细计划",
            "完整的计划",
            "详细的计划",
            "完整计划给我看看",
            "详细计划给我看看",
            "完整的计划给我看看",
            "详细的计划给我看看",
            "计划给我看看",
            "看看计划",
            "看一下计划",
            "发一下计划",
            "把计划发我",
            "把完整计划发我",
            "完整计划发我"
    );

    private static final Set<String> ARTIFACT_QUERY_SENTENCES = Set.of(
            "已有产物",
            "当前产物",
            "产物链接",
            "文档链接",
            "输出物",
            "已有产物给我看看",
            "当前产物给我看看",
            "产物给我看看",
            "文档链接给我",
            "把产物发我",
            "把文档链接发我"
    );

    private static final Set<String> STATUS_QUERY_SENTENCES = Set.of(
            "任务状态",
            "当前任务状态",
            "任务进度",
            "当前进度",
            "进度怎么样",
            "现在进度怎么样了",
            "现在做到哪了",
            "做到哪了",
            "做到哪一步了",
            "任务概况",
            "当前任务概况",
            "现在什么状态"
    );

    private static final Set<String> META_UNKNOWN_SENTENCES = Set.of(
            "你是谁",
            "你能做什么",
            "你可以做什么",
            "你是干嘛的",
            "你有什么用",
            "介绍一下你",
            "自我介绍一下"
    );

    public Optional<IntentRoutingResult> classify(
            PlanTaskSession session,
            String rawInput,
            boolean existingSession
    ) {
        String normalized = rawInput == null ? "" : rawInput.trim();
        if (normalized.isBlank()) {
            return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 0.0d, "blank input", normalized, true));
        }
        if (isCancelCommand(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.CANCEL_TASK, 1.0d, "hard rule cancel", normalized, false));
        }
        if (isConfirmCommand(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.CONFIRM_ACTION, 1.0d, "hard rule confirm", normalized, false));
        }
        Optional<IntentRoutingResult> readOnlyQuery = classifyReadOnlyQuery(normalized);
        if (readOnlyQuery.isPresent()) {
            return readOnlyQuery;
        }
        Optional<IntentRoutingResult> metaUnknown = classifyMetaUnknown(normalized);
        if (metaUnknown.isPresent()) {
            return metaUnknown;
        }
        if (!existingSession || session == null) {
            return Optional.empty();
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.INTAKE
                && session.getIntentSnapshot() == null
                && session.getPlanBlueprint() == null) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Optional<IntentRoutingResult> fallback(PlanTaskSession session, String rawInput, boolean existingSession) {
        String normalized = rawInput == null ? "" : rawInput.trim();
        if (normalized.isBlank()) {
            return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 0.0d, "blank input", normalized, true));
        }
        if (!existingSession || session == null) {
            return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 0.2d,
                    "fallback new conversation without confident task intent", normalized, true));
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
            return Optional.of(result(TaskCommandTypeEnum.ANSWER_CLARIFICATION, 0.75d,
                    "fallback clarification answer", normalized, false));
        }
        return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 0.2d,
                "fallback no confident intent", normalized, true));
    }

    boolean isCancelCommand(String input) {
        String normalized = normalize(input);
        return normalized.contains("取消任务")
                || normalized.contains("取消这个任务")
                || normalized.contains("取消当前任务")
                || normalized.contains("停止任务")
                || normalized.contains("终止任务")
                || normalized.contains("不用做了")
                || normalized.contains("不要做了")
                || normalized.contains("abort task")
                || normalized.contains("cancel task")
                || normalized.contains("stop task");
    }

    boolean isConfirmCommand(String input) {
        return ExecutionCommandGuard.isExplicitExecutionRequest(input);
    }

    private Optional<IntentRoutingResult> classifyReadOnlyQuery(String input) {
        String sentence = normalizeSentence(input);
        if (PLAN_QUERY_SENTENCES.contains(sentence)) {
            return Optional.of(result(TaskCommandTypeEnum.QUERY_STATUS, 1.0d,
                    "hard rule read-only plan query", input, false, "PLAN"));
        }
        if (ARTIFACT_QUERY_SENTENCES.contains(sentence)) {
            return Optional.of(result(TaskCommandTypeEnum.QUERY_STATUS, 1.0d,
                    "hard rule read-only artifact query", input, false, "ARTIFACTS"));
        }
        if (STATUS_QUERY_SENTENCES.contains(sentence)) {
            return Optional.of(result(TaskCommandTypeEnum.QUERY_STATUS, 1.0d,
                    "hard rule read-only status query", input, false, "STATUS"));
        }
        return Optional.empty();
    }

    private Optional<IntentRoutingResult> classifyMetaUnknown(String input) {
        String sentence = normalizeSentence(input);
        if (META_UNKNOWN_SENTENCES.contains(sentence)) {
            return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 1.0d,
                    "whole sentence meta question", input, true));
        }
        return Optional.empty();
    }

    private IntentRoutingResult result(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String input,
            boolean needsClarification
    ) {
        return new IntentRoutingResult(type, confidence, reason, input, needsClarification);
    }

    private IntentRoutingResult result(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String input,
            boolean needsClarification,
            String readOnlyView
    ) {
        return new IntentRoutingResult(type, confidence, reason, input, needsClarification, readOnlyView);
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSentence(String input) {
        String normalized = normalize(input)
                .replaceAll("[\\s，,。.!！?？：:；;、“”\"'‘’（）()【】\\[\\]]+", "");
        return stripTrailingParticles(normalized);
    }

    private String stripTrailingParticles(String input) {
        String sentence = input;
        while (!sentence.isEmpty()
                && (sentence.endsWith("吗")
                || sentence.endsWith("呢")
                || sentence.endsWith("啊")
                || sentence.endsWith("呀")
                || sentence.endsWith("吧"))) {
            sentence = sentence.substring(0, sentence.length() - 1);
        }
        return sentence;
    }

}
