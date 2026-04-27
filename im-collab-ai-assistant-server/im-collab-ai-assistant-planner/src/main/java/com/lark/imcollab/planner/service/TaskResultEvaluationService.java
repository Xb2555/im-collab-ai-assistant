package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

        TaskResultEvaluation evaluation;
        try {
            Optional<OverAllState> result = resultEvaluationSequence.invoke(prompt, config);
            String output = result.flatMap(state -> state.<List<?>>value("messages"))
                    .map(msgs -> msgs.stream()
                            .filter(m -> m instanceof AssistantMessage)
                            .map(m -> ((AssistantMessage) m).getText())
                            .reduce("", (a, b) -> b))
                    .orElse("");

            evaluation = parseEvaluation(output, submission);
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

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
