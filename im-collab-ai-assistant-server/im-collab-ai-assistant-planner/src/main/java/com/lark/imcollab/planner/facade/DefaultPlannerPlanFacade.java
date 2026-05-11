package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
import com.lark.imcollab.planner.service.ConversationTaskStateService;
import com.lark.imcollab.planner.service.PendingFollowUpRecommendationMatcher;
import com.lark.imcollab.planner.service.PlannerConversationService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskSessionResolution;
import com.lark.imcollab.planner.service.TaskSessionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final ConversationTaskStateService conversationTaskStateService;
    private final PendingFollowUpRecommendationMatcher pendingFollowUpRecommendationMatcher;

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
        String followUpPreview = previewPendingFollowUpImmediateReply(session, resolution, effectiveInput, result.get());
        if (!followUpPreview.isBlank()) {
            return followUpPreview;
        }
        return immediateReceipt(result.get().type(), result.get().adjustmentTarget(), session, workspaceContext);
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

    private String immediateReceipt(TaskCommandTypeEnum type, AdjustmentTargetEnum adjustmentTarget, PlanTaskSession session, WorkspaceContext workspaceContext) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case START_TASK -> "🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。";
            case ADJUST_PLAN -> {
                if (session != null && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
                    // 明确要中断重规划，不受澄清标记影响
                    if (adjustmentTarget == AdjustmentTargetEnum.RUNNING_PLAN) {
                        yield "🔄 这条调整我收到了，我会先中断当前执行，再根据你的新要求重新规划，请稍候。";
                    }
                    // 用户在回应我们的澄清问题
                    if (session.getIntakeState() != null
                            && session.getIntakeState().getPendingInteractionType()
                                    == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT) {
                        yield "🧩 你的补充我接上了，我会带着这条信息继续往下处理。";
                    }
                    // 有可编辑产物时会先澄清，不会直接中断
                    if (taskSessionResolver.hasEditableArtifacts(session.getTaskId())
                            || taskSessionResolver.conversationHasEditableArtifacts(workspaceContext)) {
                        yield "🔄 这条调整我收到了，稍等我先确认一下你的意图。";
                    }
                    yield "🔄 这条调整我收到了，我会先中断当前执行，再根据你的新要求重新规划，请稍候。";
                }
                yield "🔄 这条调整我收到了，我先顺着当前任务梳理一下，再把更新结果回给你。";
            }
            case ANSWER_CLARIFICATION -> "🧩 你的补充我接上了，我会带着这条信息继续往下处理。";
            default -> "";
        };
    }

    private String previewPendingFollowUpImmediateReply(
            PlanTaskSession session,
            TaskSessionResolution resolution,
            String effectiveInput,
            IntentRoutingResult routingResult
    ) {
        if (conversationTaskStateService == null
                || pendingFollowUpRecommendationMatcher == null
                || resolution == null
                || resolution.continuationKey() == null
                || effectiveInput == null
                || effectiveInput.isBlank()) {
            return "";
        }
        if (hasPendingSelection(session)) {
            return "";
        }
        Optional<com.lark.imcollab.common.model.entity.ConversationTaskState> stateOptional =
                conversationTaskStateService.find(resolution.continuationKey());
        boolean awaitingSelection = stateOptional.map(com.lark.imcollab.common.model.entity.ConversationTaskState::isPendingFollowUpAwaitingSelection)
                .orElse(false);
        if (!shouldPreviewPendingFollowUpImmediateReply(routingResult, awaitingSelection)) {
            return "";
        }
        List<PendingFollowUpRecommendation> recommendations = stateOptional
                .map(state -> state.getPendingFollowUpRecommendations())
                .orElse(List.of());
        if (recommendations == null || recommendations.isEmpty()) {
            return "";
        }
        PendingFollowUpRecommendationMatcher.MatchResult match = pendingFollowUpRecommendationMatcher.match(
                effectiveInput,
                recommendations,
                awaitingSelection,
                routingResult.type() == TaskCommandTypeEnum.START_TASK
        );
        if (match == null) {
            return "";
        }
        if (match.type() == PendingFollowUpRecommendationMatcher.Type.SELECTED) {
            return "🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。";
        }
        if (match.type() == PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION) {
            return "🔢 我这边有多个后续动作，请直接回复编号。";
        }
        return "";
    }

    private boolean shouldPreviewPendingFollowUpImmediateReply(
            IntentRoutingResult routingResult,
            boolean awaitingSelection
    ) {
        if (routingResult == null || routingResult.type() == null) {
            return awaitingSelection;
        }
        if (awaitingSelection) {
            return routingResult.type() != TaskCommandTypeEnum.QUERY_STATUS
                    && routingResult.type() != TaskCommandTypeEnum.CANCEL_TASK;
        }
        return routingResult.type() == TaskCommandTypeEnum.START_TASK
                || routingResult.type() == TaskCommandTypeEnum.ADJUST_PLAN
                || routingResult.type() == TaskCommandTypeEnum.CONFIRM_ACTION;
    }

    private boolean hasPendingSelection(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && (session.getIntakeState().getPendingTaskSelection() != null
                || session.getIntakeState().getPendingArtifactSelection() != null);
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
