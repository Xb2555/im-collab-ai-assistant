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
        Map<String, Object> values = new HashMap<>();
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
        values.forEach(builder::addContext);
        return builder.build();
    }

    public static RunnableConfig withSubmissionPromptContext(
            RunnableConfig base,
            PlanTaskSession session,
            TaskSubmissionResult submission) {
        RunnableConfig.Builder builder = RunnableConfig.builder(base);
        if (session != null) {
            builder.addContext(PromptContextKeys.TASK_ID, safe(session.getTaskId()));
            builder.addContext(PromptContextKeys.PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name());
        }
        if (submission != null) {
            builder.addContext(PromptContextKeys.SUBMISSION_TASK_ID, safe(submission.getTaskId()));
            builder.addContext(PromptContextKeys.SUBMISSION_AGENT_TASK_ID, safe(submission.getAgentTaskId()));
            builder.addContext(PromptContextKeys.SUBMISSION_STATUS, safe(submission.getStatus()));
            builder.addContext(PromptContextKeys.SUBMISSION_RAW_OUTPUT, safe(submission.getRawOutput()));
        }
        return builder.build();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
