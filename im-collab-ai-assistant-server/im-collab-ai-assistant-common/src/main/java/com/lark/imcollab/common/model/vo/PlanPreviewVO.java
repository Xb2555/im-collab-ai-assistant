package com.lark.imcollab.common.model.vo;

import java.util.List;

public record PlanPreviewVO(
        String taskId,
        int version,
        String planningPhase,
        String title,
        String summary,
        List<PlanCardVO> cards,
        List<String> clarificationQuestions,
        List<String> clarificationAnswers,
        TaskActionVO actions,
        boolean accepted,
        boolean runtimeAvailable,
        boolean transientReply,
        String assistantReply,
        List<String> capabilityHints,
        List<TriggerGuidanceVO> interactionGuidance
) {
    public PlanPreviewVO(
            String taskId,
            int version,
            String planningPhase,
            String title,
            String summary,
            List<PlanCardVO> cards,
            List<String> clarificationQuestions,
            List<String> clarificationAnswers,
            TaskActionVO actions,
            boolean accepted,
            boolean runtimeAvailable,
            boolean transientReply,
            String assistantReply,
            List<String> capabilityHints
    ) {
        this(taskId, version, planningPhase, title, summary, cards, clarificationQuestions, clarificationAnswers,
                actions, accepted, runtimeAvailable, transientReply, assistantReply, capabilityHints, List.of());
    }

    public PlanPreviewVO(
            String taskId,
            int version,
            String planningPhase,
            String title,
            String summary,
            List<PlanCardVO> cards,
            List<String> clarificationQuestions,
            List<String> clarificationAnswers,
            TaskActionVO actions
    ) {
        this(taskId, version, planningPhase, title, summary, cards, clarificationQuestions, clarificationAnswers,
                actions, true, true, false, null, List.of(), List.of());
    }
}
