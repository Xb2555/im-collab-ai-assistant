package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ResultAdviceOutput;
import com.lark.imcollab.common.model.entity.ResultJudgeOutput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.planner.prompt.AgentPromptContext;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class TaskResultEvaluationService {

    private final SequentialAgent resultEvaluationSequence;
    private final PlannerStateStore repository;
    private final PlannerSessionService sessionService;
    private final ObjectMapper objectMapper;

    public TaskResultEvaluationService(
            @Qualifier("resultEvaluationSequence") SequentialAgent resultEvaluationSequence,
            PlannerStateStore repository,
            PlannerSessionService sessionService,
            ObjectMapper objectMapper) {
        this.resultEvaluationSequence = resultEvaluationSequence;
        this.repository = repository;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
        String threadId = submission.getTaskId() + "-eval-" + submission.getAgentTaskId();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        String prompt = buildEvalPrompt(submission);
        PlanTaskSession session = sessionService.get(submission.getTaskId());
        RunnableConfig evalConfig = AgentPromptContext.withSubmissionPromptContext(config, session, submission);

        TaskResultEvaluation evaluation;
        try {
            Optional<OverAllState> result = resultEvaluationSequence.invoke(prompt, evalConfig);
            evaluation = extractEvaluationFromState(result, submission)
                    .orElseGet(() -> {
                        String output = extractLastAssistantText(result);
                        return parseEvaluation(output, submission);
                    });
        } catch (GraphRunnerException e) {
            log.error("Evaluation failed for task {}: {}", submission.getTaskId(), e.getMessage(), e);
            evaluation = TaskResultEvaluation.builder()
                    .taskId(submission.getTaskId())
                    .agentTaskId(submission.getAgentTaskId())
                    .resultScore(0)
                    .verdict(ResultVerdictEnum.HUMAN_REVIEW)
                    .issues(List.of("Evaluation error: " + e.getMessage()))
                    .suggestions(List.of())
                    .build();
        }

        repository.saveEvaluation(evaluation);
        writeBackToCard(submission, evaluation);
        sessionService.publishEvent(submission.getTaskId(), evaluation.getVerdict().name());
        return evaluation;
    }

    private void writeBackToCard(TaskSubmissionResult submission, TaskResultEvaluation evaluation) {
        try {
            PlanTaskSession session = sessionService.get(submission.getTaskId());
            if (session.getPlanCards() == null) return;

            boolean updated = false;
            for (UserPlanCard card : session.getPlanCards()) {
                if (card.getAgentTaskPlanCards() == null) continue;
                for (AgentTaskPlanCard agentCard : card.getAgentTaskPlanCards()) {
                    if (!agentCard.getTaskId().equals(submission.getAgentTaskId())) continue;
                    agentCard.setLastResultScore(evaluation.getResultScore());
                    agentCard.setLastVerdict(evaluation.getVerdict().name());
                    agentCard.setRetryCount(agentCard.getRetryCount() + 1);
                    agentCard.setStatus(resolveCardStatus(evaluation.getVerdict()));
                    updated = true;
                }
            }
            if (updated) {
                sessionService.save(session);
            }
        } catch (Exception e) {
            log.warn("Failed to write back evaluation to card for task {}: {}", submission.getTaskId(), e.getMessage());
        }
    }

    private String resolveCardStatus(ResultVerdictEnum verdict) {
        return switch (verdict) {
            case PASS -> "COMPLETED";
            case RETRY -> "PENDING";
            case HUMAN_REVIEW -> "BLOCKED";
        };
    }

    private String buildEvalPrompt(TaskSubmissionResult submission) {
        return "任务ID：" + submission.getTaskId() +
                "\n子任务ID：" + submission.getAgentTaskId() +
                "\n执行状态：" + submission.getStatus() +
                "\n原始输出：\n" + submission.getRawOutput();
    }

    private TaskResultEvaluation parseEvaluation(String output, TaskSubmissionResult submission) {
        int score = 0;
        ResultVerdictEnum verdict = ResultVerdictEnum.HUMAN_REVIEW;
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        try {
            String jsonPart = extractJson(output);
            JsonNode root = objectMapper.readTree(jsonPart);

            if (root.has("resultScore")) score = root.get("resultScore").asInt(0);
            if (root.has("verdict")) {
                try {
                    verdict = ResultVerdictEnum.valueOf(root.get("verdict").asText());
                } catch (IllegalArgumentException ignored) {}
            }
            if (root.has("issues") && root.get("issues").isArray()) {
                root.get("issues").forEach(n -> issues.add(n.asText()));
            }
            if (root.has("suggestions") && root.get("suggestions").isArray()) {
                root.get("suggestions").forEach(n -> suggestions.add(n.asText()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse evaluation JSON: {}", e.getMessage());
            if (score >= 80) verdict = ResultVerdictEnum.PASS;
            else if (score >= 60) verdict = ResultVerdictEnum.RETRY;
        }

        return TaskResultEvaluation.builder()
                .taskId(submission.getTaskId())
                .agentTaskId(submission.getAgentTaskId())
                .resultScore(score)
                .verdict(verdict)
                .issues(issues)
                .suggestions(suggestions)
                .build();
    }

    private Optional<TaskResultEvaluation> extractEvaluationFromState(
            Optional<OverAllState> state,
            TaskSubmissionResult submission) {
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = state.get().data();
        Object single = data.get("message");
        if (single instanceof ResultAdviceOutput adviceOnly) {
            return Optional.of(buildEvaluationFromTyped(null, adviceOnly, submission));
        }
        if (single instanceof ResultJudgeOutput judgeOnly) {
            return Optional.of(buildEvaluationFromTyped(judgeOnly, null, submission));
        }
        Object raw = data.get("messages");
        if (!(raw instanceof List<?> list)) {
            if (raw instanceof ResultAdviceOutput advice) {
                return Optional.of(buildEvaluationFromTyped(null, advice, submission));
            }
            if (raw instanceof ResultJudgeOutput judge) {
                return Optional.of(buildEvaluationFromTyped(judge, null, submission));
            }
            return Optional.empty();
        }

        ResultJudgeOutput judge = null;
        ResultAdviceOutput advice = null;
        for (Object item : list) {
            if (item instanceof ResultJudgeOutput output) {
                judge = output;
            } else if (item instanceof ResultAdviceOutput output) {
                advice = output;
            }
        }
        if (judge == null && advice == null) {
            return Optional.empty();
        }

        return Optional.of(buildEvaluationFromTyped(judge, advice, submission));
    }

    private TaskResultEvaluation buildEvaluationFromTyped(
            ResultJudgeOutput judge,
            ResultAdviceOutput advice,
            TaskSubmissionResult submission) {
        int score = judge != null && judge.getResultScore() != null ? judge.getResultScore() : 0;
        List<String> issues = judge != null && judge.getIssues() != null ? judge.getIssues() : List.of();
        ResultVerdictEnum verdict = ResultVerdictEnum.HUMAN_REVIEW;
        List<String> suggestions = List.of();
        if (advice != null) {
            if (advice.getVerdict() != null) {
                try {
                    verdict = ResultVerdictEnum.valueOf(advice.getVerdict().toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }
            if (advice.getSuggestions() != null) {
                suggestions = advice.getSuggestions();
            }
        }

        if (advice == null) {
            if (score >= 80) {
                verdict = ResultVerdictEnum.PASS;
            } else if (score >= 60) {
                verdict = ResultVerdictEnum.RETRY;
            }
        }

        return TaskResultEvaluation.builder()
                .taskId(submission.getTaskId())
                .agentTaskId(submission.getAgentTaskId())
                .resultScore(score)
                .verdict(verdict)
                .issues(issues)
                .suggestions(suggestions)
                .build();
    }

    private String extractLastAssistantText(Optional<OverAllState> result) {
        return result.flatMap(state -> state.<List<?>>value("messages"))
                .map(msgs -> msgs.stream()
                        .filter(m -> m instanceof AssistantMessage)
                        .map(m -> ((AssistantMessage) m).getText())
                        .reduce("", (a, b) -> b))
                .orElse("");
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
