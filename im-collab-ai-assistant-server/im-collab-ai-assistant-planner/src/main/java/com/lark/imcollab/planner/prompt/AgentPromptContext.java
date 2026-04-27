package com.lark.imcollab.planner.prompt;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class AgentPromptContext {

    private AgentPromptContext() {}

    public static RunnableConfig withPlanningPromptContext(
            RunnableConfig base,
            PlanTaskSession session,
            String rawInstruction,
            String context) {
        RunnableConfig.Builder builder = RunnableConfig.builder(base);
        Map<String, Object> values = new HashMap<>(base.metadata().orElse(Map.of()));
        if (session != null) {
            values.put(PromptContextKeys.TASK_ID, safe(session.getTaskId()));
            values.put(PromptContextKeys.PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name());
            String answers = session.getClarificationAnswers() == null
                    ? ""
                    : session.getClarificationAnswers().stream().collect(Collectors.joining("；"));
            values.put(PromptContextKeys.CLARIFICATION_ANSWERS, answers);
        }
        values.put(PromptContextKeys.RAW_INSTRUCTION, safe(rawInstruction));
        values.put(PromptContextKeys.CONTEXT, safe(context));
        values.forEach(builder::addMetadata);
        return builder.build();
    }

    public static RunnableConfig withSubmissionPromptContext(
            RunnableConfig base,
            PlanTaskSession session,
            TaskSubmissionResult submission) {
        RunnableConfig.Builder builder = RunnableConfig.builder(base);
        Map<String, Object> values = new HashMap<>(base.metadata().orElse(Map.of()));
        if (session != null) {
            values.put(PromptContextKeys.TASK_ID, safe(session.getTaskId()));
            values.put(PromptContextKeys.PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name());
        }
        if (submission != null) {
            values.put(PromptContextKeys.SUBMISSION_TASK_ID, safe(submission.getTaskId()));
            values.put(PromptContextKeys.SUBMISSION_AGENT_TASK_ID, safe(submission.getAgentTaskId()));
            values.put(PromptContextKeys.SUBMISSION_STATUS, safe(submission.getStatus()));
            values.put(PromptContextKeys.SUBMISSION_RAW_OUTPUT, safe(submission.getRawOutput()));
        }
        values.forEach(builder::addMetadata);
        return builder.build();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
