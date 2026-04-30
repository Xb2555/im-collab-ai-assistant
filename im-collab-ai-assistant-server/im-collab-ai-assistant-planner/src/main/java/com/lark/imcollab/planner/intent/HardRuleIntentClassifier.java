package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
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
        if (!existingSession || session == null) {
            return Optional.of(result(TaskCommandTypeEnum.START_TASK, 0.95d, "new conversation", normalized, false));
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.INTAKE
                && session.getIntentSnapshot() == null
                && session.getPlanBlueprint() == null) {
            return Optional.of(result(TaskCommandTypeEnum.START_TASK, 0.9d, "accepted intake", normalized, false));
        }
        if (isCancelCommand(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.CANCEL_TASK, 1.0d, "hard rule cancel", normalized, false));
        }
        if (isConfirmCommand(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.CONFIRM_ACTION, 1.0d, "hard rule confirm", normalized, false));
        }
        if (isStatusQuery(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.QUERY_STATUS, 1.0d, "hard rule status query", normalized, false));
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
            return Optional.of(result(TaskCommandTypeEnum.ANSWER_CLARIFICATION, 0.9d,
                    "session waiting clarification", normalized, false));
        }
        return Optional.empty();
    }

    public Optional<IntentRoutingResult> fallback(PlanTaskSession session, String rawInput, boolean existingSession) {
        String normalized = rawInput == null ? "" : rawInput.trim();
        if (normalized.isBlank()) {
            return Optional.of(result(TaskCommandTypeEnum.UNKNOWN, 0.0d, "blank input", normalized, true));
        }
        if (!existingSession || session == null) {
            return Optional.of(result(TaskCommandTypeEnum.START_TASK, 0.9d, "fallback new conversation", normalized, false));
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
            return Optional.of(result(TaskCommandTypeEnum.ANSWER_CLARIFICATION, 0.75d,
                    "fallback clarification answer", normalized, false));
        }
        if (hasPlanMutationVerb(normalized)) {
            return Optional.of(result(TaskCommandTypeEnum.ADJUST_PLAN, 0.6d,
                    "fallback local edit signal", normalized, false));
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
        String normalized = normalize(input);
        String compact = compact(normalized);
        return normalized.contains("确认执行")
                || normalized.contains("开始执行")
                || normalized.contains("按这个执行")
                || normalized.contains("就这样")
                || normalized.contains("可以执行")
                || normalized.contains("执行吧")
                || normalized.contains("开始吧")
                || normalized.contains("按计划来")
                || normalized.contains("就按这个来")
                || normalized.contains("重试")
                || normalized.contains("重新执行")
                || normalized.contains("再试一次")
                || normalized.contains("继续执行")
                || (normalized.contains("执行") && containsApprovalWord(normalized))
                || "执行".equals(compact)
                || compact.contains("没问题执行")
                || compact.contains("可以执行")
                || compact.contains("好的执行")
                || compact.contains("好执行")
                || normalized.contains("confirm")
                || normalized.contains("execute");
    }

    private boolean containsApprovalWord(String normalized) {
        return normalized.contains("没问题")
                || normalized.contains("可以")
                || normalized.contains("好的")
                || normalized.contains("好")
                || normalized.contains("同意")
                || normalized.contains("确认")
                || normalized.contains("按计划")
                || normalized.contains("就按")
                || normalized.contains("ok");
    }

    boolean isStatusQuery(String input) {
        String normalized = normalize(input);
        String compact = compact(normalized);
        return normalized.contains("进度")
                || normalized.contains("状态")
                || normalized.contains("做到哪")
                || normalized.contains("怎么样了")
                || isTaskOverviewQuery(normalized)
                || normalized.contains("详细计划")
                || normalized.contains("完整计划")
                || normalized.contains("展开计划")
                || normalized.contains("所有步骤")
                || compact.contains("完整计划")
                || compact.contains("详细计划")
                || compact.contains("计划是什么")
                || "计划".equals(compact)
                || "当前计划".equals(compact)
                || normalized.contains("status")
                || normalized.contains("progress")
                || normalized.contains("full plan")
                || normalized.contains("detail");
    }

    boolean hasPlanMutationVerb(String input) {
        String normalized = normalize(input);
        return normalized.contains("再加")
                || normalized.contains("加一")
                || normalized.contains("加个")
                || normalized.contains("加上")
                || normalized.contains("新增")
                || normalized.contains("补一")
                || normalized.contains("补个")
                || normalized.contains("补一下")
                || normalized.contains("补充")
                || normalized.contains("追加")
                || normalized.contains("来一份")
                || normalized.contains("也来")
                || normalized.contains("删除")
                || normalized.contains("去掉")
                || normalized.contains("移除")
                || normalized.contains("不要")
                || normalized.contains("不想")
                || normalized.contains("改成")
                || normalized.contains("修改")
                || normalized.contains("调整")
                || normalized.contains("替换")
                || normalized.contains("生成")
                || normalized.contains("输出")
                || normalized.contains("重排")
                || normalized.contains("先做")
                || normalized.contains("最后")
                || normalized.contains("add ")
                || normalized.contains("remove ")
                || normalized.contains("delete ")
                || normalized.contains("change ")
                || normalized.contains("update ");
    }

    private boolean isTaskOverviewQuery(String normalized) {
        if (normalized.isBlank() || hasPlanMutationVerb(normalized)) {
            return false;
        }
        String compact = compact(normalized);
        boolean refersToTaskOrPlan = compact.contains("任务")
                || compact.contains("计划")
                || compact.contains("步骤")
                || compact.contains("todo");
        if (!refersToTaskOrPlan) {
            return false;
        }
        boolean asksOverview = compact.contains("概况")
                || compact.contains("概览")
                || compact.contains("总览")
                || compact.contains("概要")
                || compact.contains("清单")
                || compact.contains("列表")
                || compact.contains("当前任务")
                || compact.contains("现在任务");
        if (asksOverview) {
            return true;
        }
        return compact.length() <= 8 && (compact.contains("任务") || compact.contains("计划"));
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

    private String compact(String input) {
        return input == null ? "" : input
                .replaceAll("\\s+", "")
                .replace("的", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace(".", "");
    }
}
