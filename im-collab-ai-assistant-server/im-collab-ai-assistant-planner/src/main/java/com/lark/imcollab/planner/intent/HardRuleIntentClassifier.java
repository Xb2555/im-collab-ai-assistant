package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class HardRuleIntentClassifier {

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

    private IntentRoutingResult result(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String input,
            boolean needsClarification
    ) {
        return new IntentRoutingResult(type, confidence, reason, input, needsClarification);
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

}
