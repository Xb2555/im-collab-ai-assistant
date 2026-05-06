package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.IntentRouterService;
import com.lark.imcollab.planner.intent.UnknownIntentReplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TaskIntakeService {

    private final IntentRouterService intentRouterService;
    private final UnknownIntentReplyService unknownIntentReplyService;

    @Autowired
    public TaskIntakeService(
            IntentRouterService intentRouterService,
            UnknownIntentReplyService unknownIntentReplyService
    ) {
        this.intentRouterService = intentRouterService;
        this.unknownIntentReplyService = unknownIntentReplyService;
    }

    public TaskIntakeDecision decide(
            PlanTaskSession session,
            String rawInstruction,
            String userFeedback,
            boolean existingSession
    ) {
        String effectiveInput = firstText(userFeedback, rawInstruction);
        effectiveInput = stripLeadingMentionPlaceholders(effectiveInput);
        IntentRoutingResult result = intentRouterService.classify(session, effectiveInput, existingSession);
        TaskIntakeTypeEnum intakeType = map(result.type());
        String assistantReply = intakeType == TaskIntakeTypeEnum.UNKNOWN
                ? unknownIntentReplyService.reply(session, effectiveInput, result.reason())
                : null;
        return new TaskIntakeDecision(intakeType, result.normalizedInput(), result.reason(), assistantReply, result.readOnlyView());
    }

    public TaskIntakeService(IntentRouterService intentRouterService) {
        this(intentRouterService, new UnknownIntentReplyService());
    }

    public boolean isForcedNewTaskDecision(TaskIntakeDecision decision) {
        return decision != null
                && decision.intakeType() == TaskIntakeTypeEnum.NEW_TASK
                && "hard rule force new task".equals(decision.routingReason());
    }

    private TaskIntakeTypeEnum map(TaskCommandTypeEnum type) {
        if (type == null) {
            return TaskIntakeTypeEnum.UNKNOWN;
        }
        return switch (type) {
            case START_TASK -> TaskIntakeTypeEnum.NEW_TASK;
            case ANSWER_CLARIFICATION -> TaskIntakeTypeEnum.CLARIFICATION_REPLY;
            case ADJUST_PLAN -> TaskIntakeTypeEnum.PLAN_ADJUSTMENT;
            case QUERY_STATUS -> TaskIntakeTypeEnum.STATUS_QUERY;
            case CONFIRM_ACTION -> TaskIntakeTypeEnum.CONFIRM_ACTION;
            case CANCEL_TASK -> TaskIntakeTypeEnum.CANCEL_TASK;
            case UNKNOWN -> TaskIntakeTypeEnum.UNKNOWN;
        };
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
            normalized = normalized.replaceFirst("^@[_a-zA-Z0-9\\-]+\\s+", "").trim();
            normalized = normalized.replaceFirst("^<at\\b[^>]*>[^<]*</at>\\s*", "").trim();
        } while (!normalized.equals(previous));
        return normalized;
    }
}
