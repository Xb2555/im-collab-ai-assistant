package com.lark.imcollab.harness.scene.c.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class SceneCExecutionSupport {

    public static final String OUTLINE_TASK_SUFFIX = "generate_outline";
    public static final String SECTIONS_TASK_SUFFIX = "generate_sections";
    public static final String REVIEW_TASK_SUFFIX = "review_doc";
    public static final String WRITE_TASK_SUFFIX = "write_doc_and_sync";
    private static final int MAX_RETRY = 2;

    private final PlannerRuntimeFacade plannerRuntimeFacade;
    private final ObjectMapper objectMapper;

    public SceneCExecutionSupport(PlannerRuntimeFacade plannerRuntimeFacade, ObjectMapper objectMapper) {
        this.plannerRuntimeFacade = plannerRuntimeFacade;
        this.objectMapper = objectMapper;
    }

    public PlanTaskSession loadSession(String taskId) {
        return plannerRuntimeFacade.getSession(taskId);
    }

    public PlanTaskSession saveSession(PlanTaskSession session) {
        return plannerRuntimeFacade.saveSession(session);
    }

    public UserPlanCard requireCard(PlanTaskSession session, String cardId) {
        return session.getPlanCards().stream()
                .filter(card -> Objects.equals(card.getCardId(), cardId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
    }

    public void ensureSceneCTasks(UserPlanCard card) {
        if (card.getAgentTaskPlanCards() == null) {
            card.setAgentTaskPlanCards(new ArrayList<>());
        }
        ensureTask(card, OUTLINE_TASK_SUFFIX);
        ensureTask(card, SECTIONS_TASK_SUFFIX);
        ensureTask(card, REVIEW_TASK_SUFFIX);
        ensureTask(card, WRITE_TASK_SUFFIX);
    }

    public void markCardProgress(PlanTaskSession session, String cardId, String status, int progress) {
        UserPlanCard card = requireCard(session, cardId);
        card.setStatus(status);
        card.setProgress(progress);
        plannerRuntimeFacade.saveSession(session);
    }

    public void setCardArtifacts(PlanTaskSession session, String cardId, List<String> artifactRefs) {
        UserPlanCard card = requireCard(session, cardId);
        card.setArtifactRefs(artifactRefs);
        plannerRuntimeFacade.saveSession(session);
    }

    public void updateSubtask(PlanTaskSession session, String cardId, String subtaskId, String status, String output) {
        UserPlanCard card = requireCard(session, cardId);
        ensureSceneCTasks(card);
        card.getAgentTaskPlanCards().stream()
                .filter(agentTaskPlanCard -> Objects.equals(agentTaskPlanCard.getTaskId(), subtaskId))
                .findFirst()
                .ifPresent(agentCard -> {
                    agentCard.setStatus(status);
                    agentCard.setOutput(output);
                });
        plannerRuntimeFacade.saveSession(session);
    }

    public <T> T executeAndEvaluate(
            String taskId,
            String cardId,
            String subtaskId,
            Supplier<T> executor,
            Function<T, List<String>> artifactRefExtractor) {
        TaskResultEvaluation evaluation = null;
        String rawOutput = "";
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            T payload = executor.get();
            rawOutput = writeJson(payload);
            List<String> artifactRefs = Optional.ofNullable(artifactRefExtractor.apply(payload)).orElse(List.of());
            TaskSubmissionResult submission = TaskSubmissionResult.builder()
                    .taskId(taskId)
                    .parentCardId(cardId)
                    .agentTaskId(subtaskId)
                    .artifactRefs(artifactRefs)
                    .rawOutput(rawOutput)
                    .status("COMPLETED")
                    .build();
            evaluation = plannerRuntimeFacade.evaluate(submission);
            if (evaluation.getVerdict() == ResultVerdictEnum.PASS) {
                return payload;
            }
            if (evaluation.getVerdict() == ResultVerdictEnum.HUMAN_REVIEW) {
                throw new HumanReviewRequiredException(evaluation);
            }
        }
        throw new RetryExhaustedException(evaluation, rawOutput);
    }

    public Optional<TaskSubmissionResult> findExistingSubmission(String taskId, String subtaskId) {
        return plannerRuntimeFacade.findSubmission(taskId, subtaskId);
    }

    public void publishEvent(String taskId, String status) {
        plannerRuntimeFacade.publishEvent(taskId, status);
    }

    public void publishHumanReview(String taskId, List<String> issues, List<String> suggestions) {
        RequireInput requireInput = RequireInput.builder()
                .type("TEXT")
                .prompt(buildPrompt(issues, suggestions))
                .build();
        plannerRuntimeFacade.publishEvent(taskId, "WAITING_HUMAN_REVIEW", requireInput);
    }

    public String subtaskId(String cardId, String suffix) {
        return cardId + ":sceneC:" + suffix;
    }

    private void ensureTask(UserPlanCard card, String suffix) {
        String subtaskId = subtaskId(card.getCardId(), suffix);
        boolean exists = card.getAgentTaskPlanCards().stream()
                .anyMatch(task -> Objects.equals(task.getTaskId(), subtaskId));
        if (exists) {
            return;
        }
        card.getAgentTaskPlanCards().add(AgentTaskPlanCard.builder()
                .taskId(subtaskId)
                .parentCardId(card.getCardId())
                .taskType(AgentTaskTypeEnum.WRITE_DOC)
                .status("PENDING")
                .context("scene-c:" + suffix)
                .input(card.getDescription())
                .tools(List.of("doc.write"))
                .build());
    }

    private String buildPrompt(List<String> issues, List<String> suggestions) {
        List<String> lines = new ArrayList<>();
        lines.add("文档执行需要人工确认后继续。");
        if (issues != null && !issues.isEmpty()) {
            lines.add("问题：" + String.join("；", issues));
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            lines.add("建议：" + String.join("；", suggestions));
        }
        return String.join("\n", lines);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return String.valueOf(payload);
        }
    }

    public static class HumanReviewRequiredException extends RuntimeException {
        private final TaskResultEvaluation evaluation;

        public HumanReviewRequiredException(TaskResultEvaluation evaluation) {
            super("Human review required");
            this.evaluation = evaluation;
        }

        public TaskResultEvaluation getEvaluation() {
            return evaluation;
        }
    }

    public static class RetryExhaustedException extends RuntimeException {
        private final TaskResultEvaluation evaluation;
        private final String rawOutput;

        public RetryExhaustedException(TaskResultEvaluation evaluation, String rawOutput) {
            super("Retry exhausted");
            this.evaluation = evaluation;
            this.rawOutput = rawOutput;
        }

        public TaskResultEvaluation getEvaluation() {
            return evaluation;
        }

        public String getRawOutput() {
            return rawOutput;
        }
    }
}
