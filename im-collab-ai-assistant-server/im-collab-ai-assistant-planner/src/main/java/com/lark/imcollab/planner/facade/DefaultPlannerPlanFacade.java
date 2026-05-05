package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
import com.lark.imcollab.planner.service.PlannerConversationService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskSessionResolution;
import com.lark.imcollab.planner.service.TaskSessionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaultPlannerPlanFacade implements PlannerPlanFacade {

    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");

    private final PlannerConversationService plannerConversationService;
    private final TaskSessionResolver taskSessionResolver;
    private final PlannerSessionService plannerSessionService;
    private final LlmIntentClassifier llmIntentClassifier;

    @Override
    public String previewImmediateReply(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        String effectiveInput = stripLeadingMentionPlaceholders(firstText(userFeedback, rawInstruction));
        if (effectiveInput.isBlank()) {
            return "";
        }
        TaskSessionResolution resolution = taskSessionResolver.resolve(taskId, workspaceContext);
        PlanTaskSession session = resolution.existingSession() ? safeGet(resolution.taskId()) : null;
        Optional<IntentRoutingResult> result = llmIntentClassifier.classify(session, effectiveInput, resolution.existingSession());
        if (result.isEmpty()) {
            return "";
        }
        return immediateReceipt(result.get().type());
    }

    @Override
    public PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        return plannerConversationService.handlePlanRequest(rawInstruction, workspaceContext, taskId, userFeedback);
    }

    private String immediateReceipt(TaskCommandTypeEnum type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case START_TASK -> "🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。";
            case ADJUST_PLAN -> "🔄 这条调整我收到了，我先顺着当前任务梳理一下，再把更新结果回给你。";
            case ANSWER_CLARIFICATION -> "🧩 你的补充我接上了，我会带着这条信息继续往下处理。";
            default -> "";
        };
    }

    private PlanTaskSession safeGet(String taskId) {
        try {
            return plannerSessionService.get(taskId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String stripLeadingMentionPlaceholders(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.trim();
        String previous;
        do {
            previous = normalized;
            normalized = FEISHU_AT_TAG.matcher(normalized).replaceFirst("").trim();
            normalized = FEISHU_MENTION_TOKEN.matcher(normalized).replaceFirst("").trim();
            normalized = normalized.replaceFirst("^@[\\p{L}0-9_\\-]+\\s+", "").trim();
        } while (!normalized.equals(previous));
        return normalized;
    }
}
