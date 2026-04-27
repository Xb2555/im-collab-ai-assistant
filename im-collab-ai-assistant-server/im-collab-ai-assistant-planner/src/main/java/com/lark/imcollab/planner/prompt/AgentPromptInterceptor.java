package com.lark.imcollab.planner.prompt;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentPromptInterceptor extends ModelInterceptor {

    private static final String AGENT_CONTEXT_KEY = "_AGENT_";

    private final PlannerPromptFacade promptFacade;
    private final PromptSessionContext sessionContext;

    public AgentPromptInterceptor(PlannerPromptFacade promptFacade, PromptSessionContext sessionContext) {
        this.promptFacade = promptFacade;
        this.sessionContext = sessionContext;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> context = request.getContext() == null ? Map.of() : request.getContext();
        String agentName = asString(context.getOrDefault(AGENT_CONTEXT_KEY, ""));
        if (agentName.isBlank()) {
            return handler.call(request);
        }
        String taskId = asString(context.getOrDefault(PromptContextKeys.TASK_ID, ""));
        PlanTaskSession session = sessionContext.get(taskId).orElse(null);

        String systemPrompt = resolveSystemPrompt(agentName, session);
        String instruction = resolveInstruction(agentName, session, context);
        if (systemPrompt == null && instruction == null) {
            return handler.call(request);
        }

        ModelRequest.Builder builder = ModelRequest.builder(request);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemMessage(new SystemMessage(systemPrompt));
        }
        if (instruction != null && !instruction.isBlank()) {
            builder.messages(prependInstruction(request.getMessages(), instruction));
        }
        return handler.call(builder.build());
    }

    private String resolveSystemPrompt(String agentName, PlanTaskSession session) {
        return switch (agentName) {
            case "planning-agent" -> promptFacade.planningPrompt(session);
            case "result-judge-agent" -> promptFacade.resultJudgePrompt(session);
            case "result-advice-agent" -> promptFacade.resultAdvicePrompt(session);
            case "supervisor-agent" -> promptFacade.supervisorPrompt(session);
            case "clarification-agent" -> promptFacade.clarificationPrompt(session);
            default -> null;
        };
    }

    private String resolveInstruction(String agentName, PlanTaskSession session, Map<String, Object> context) {
        return switch (agentName) {
            case "supervisor-agent" -> promptFacade.supervisorInstruction(session);
            case "clarification-agent" -> promptFacade.clarificationInstruction(session);
            case "planning-agent" -> promptFacade.planningInstruction(
                    session,
                    asString(context.get(PromptContextKeys.RAW_INSTRUCTION)),
                    asString(context.get(PromptContextKeys.CONTEXT)),
                    asString(context.get(PromptContextKeys.CLARIFICATION_ANSWERS)));
            case "result-judge-agent" -> promptFacade.resultJudgeInstruction(
                    session,
                    asSubmissionVariables(context));
            case "result-advice-agent" -> promptFacade.resultAdviceInstruction(
                    session,
                    asSubmissionVariables(context));
            default -> null;
        };
    }

    private Map<String, String> asSubmissionVariables(Map<String, Object> context) {
        Map<String, String> variables = new HashMap<>();
        variables.put("taskId", asString(context.get(PromptContextKeys.SUBMISSION_TASK_ID)));
        variables.put("agentTaskId", asString(context.get(PromptContextKeys.SUBMISSION_AGENT_TASK_ID)));
        variables.put("submissionStatus", asString(context.get(PromptContextKeys.SUBMISSION_STATUS)));
        variables.put("rawOutput", asString(context.get(PromptContextKeys.SUBMISSION_RAW_OUTPUT)));
        return variables;
    }

    private java.util.List<org.springframework.ai.chat.messages.Message> prependInstruction(
            List<org.springframework.ai.chat.messages.Message> original,
            String instruction) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new UserMessage(instruction));
        if (original != null) {
            messages.addAll(original);
        }
        return messages;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public String getName() {
        return "AgentPromptInterceptor";
    }
}
