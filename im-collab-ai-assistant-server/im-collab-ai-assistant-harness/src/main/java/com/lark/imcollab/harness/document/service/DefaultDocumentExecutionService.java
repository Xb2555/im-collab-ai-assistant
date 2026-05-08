package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.harness.support.ExecutionInterruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultDocumentExecutionService implements DocumentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentExecutionService.class);

    private static final List<String> GENERATED_STATE_KEYS = List.of(
            DocumentStateKeys.OUTLINE,
            DocumentStateKeys.DOCUMENT_PLAN,
            DocumentStateKeys.SECTION_DRAFTS,
            DocumentStateKeys.REVIEW_RESULT,
            DocumentStateKeys.COMPOSED_DRAFT,
            DocumentStateKeys.SECTION_PROGRESS,
            DocumentStateKeys.COMPLETED_SECTION_KEYS,
            DocumentStateKeys.CURRENT_SECTION_KEY,
            DocumentStateKeys.DOC_MARKDOWN,
            DocumentStateKeys.ARTIFACT_REFS,
            DocumentStateKeys.DOC_ID,
            DocumentStateKeys.DOC_URL,
            DocumentStateKeys.WAITING_HUMAN_REVIEW,
            DocumentStateKeys.HALTED_STAGE,
            DocumentStateKeys.DONE_OUTLINE,
            DocumentStateKeys.DONE_SECTIONS,
            DocumentStateKeys.DONE_REVIEW,
            DocumentStateKeys.DONE_WRITE,
            DocumentStateKeys.DIAGRAM_PLAN,
            DocumentStateKeys.MERMAID_DIAGRAM
    );

    private final CompiledGraph documentWorkflow;
    private final TaskRepository taskRepository;
    private final DocumentExecutionGuard executionGuard;
    private final DocumentExecutionSupport executionSupport;

    public DefaultDocumentExecutionService(
            @Qualifier("documentWorkflow") CompiledGraph documentWorkflow,
            TaskRepository taskRepository,
            DocumentExecutionGuard executionGuard,
            DocumentExecutionSupport executionSupport) {
        this.documentWorkflow = documentWorkflow;
        this.taskRepository = taskRepository;
        this.executionGuard = executionGuard;
        this.executionSupport = executionSupport;
    }

    @Override
    public void execute(String taskId) {
        executionGuard.execute(taskId, () -> runSafely(taskId, null, null));
    }

    @Override
    public void resume(String taskId, Approval approval) {
        executionGuard.execute(taskId, () -> runSafely(taskId, approval.getUserFeedback(), approval.getStepId()));
    }

    private void runWorkflow(String taskId, String userFeedback) {
        String executionAttemptId = blankToDefault(ExecutionAttemptContext.currentExecutionAttemptId(), "");
        RunnableConfig config = RunnableConfig.builder().threadId(documentThreadId(taskId, executionAttemptId)).build();
        Optional<StateSnapshot> snapshot = documentWorkflow.stateOf(config);
        Map<String, Object> state = new HashMap<>();
        snapshot.ifPresent(s -> state.putAll(s.state().data()));
        state.put(DocumentStateKeys.TASK_ID, taskId);
        state.put(DocumentStateKeys.EXECUTION_ATTEMPT_ID, executionAttemptId);
        state.put(DocumentStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        taskRepository.findById(taskId).ifPresent(task -> {
            log.info("DOC_EXEC task snapshot before workflow: taskId={}, attempt={}, raw='{}', clarified='{}', brief='{}', contractRaw='{}', contractClarified='{}', contractBrief='{}'",
                    taskId,
                    executionAttemptId,
                    abbreviate(task.getRawInstruction()),
                    abbreviate(task.getClarifiedInstruction()),
                    abbreviate(task.getTaskBrief()),
                    abbreviate(task.getExecutionContract() == null ? null : task.getExecutionContract().getRawInstruction()),
                    abbreviate(task.getExecutionContract() == null ? null : task.getExecutionContract().getClarifiedInstruction()),
                    abbreviate(task.getExecutionContract() == null ? null : task.getExecutionContract().getTaskBrief()));
            if (shouldRegenerateFromCurrentContext(snapshot.isPresent(), state, task, userFeedback)) {
                clearGeneratedState(state);
            }
            if (task.getRawInstruction() != null && !task.getRawInstruction().isBlank()) {
                state.put(DocumentStateKeys.RAW_INSTRUCTION, task.getRawInstruction());
            }
            if (task.getClarifiedInstruction() != null && !task.getClarifiedInstruction().isBlank()) {
                state.put(DocumentStateKeys.CLARIFIED_INSTRUCTION, task.getClarifiedInstruction());
            }
            if (task.getExecutionContract() != null) {
                state.put(DocumentStateKeys.TEMPLATE_STRATEGY, task.getExecutionContract().getTemplateStrategy());
                state.put(DocumentStateKeys.DIAGRAM_REQUIREMENT, task.getExecutionContract().getDiagramRequirement());
                state.put(DocumentStateKeys.EXECUTION_CONSTRAINTS, task.getExecutionContract().getConstraints());
                state.put(DocumentStateKeys.SOURCE_SCOPE, task.getExecutionContract().getSourceScope());
                state.put(DocumentStateKeys.ALLOWED_ARTIFACTS, task.getExecutionContract().getAllowedArtifacts());
            }
        });
        log.info("DOC_EXEC state before invoke: taskId={}, attempt={}, stateRaw='{}', stateClarified='{}', userFeedback='{}', hasCheckpoint={}",
                taskId,
                executionAttemptId,
                abbreviate(stringValue(state.get(DocumentStateKeys.RAW_INSTRUCTION))),
                abbreviate(stringValue(state.get(DocumentStateKeys.CLARIFIED_INSTRUCTION))),
                abbreviate(userFeedback),
                snapshot.isPresent());
        documentWorkflow.invoke(new OverAllState(state), config);
    }

    private String documentThreadId(String taskId, String executionAttemptId) {
        if (hasText(executionAttemptId)) {
            return taskId + ":document:" + executionAttemptId;
        }
        return taskId;
    }

    private boolean shouldRegenerateFromCurrentContext(
            boolean hasCheckpoint,
            Map<String, Object> checkpointState,
            Task task,
            String userFeedback
    ) {
        if (!hasCheckpoint) {
            return false;
        }
        if (hasText(userFeedback)) {
            return true;
        }
        String previousInstruction = firstNonBlank(
                stringValue(checkpointState.get(DocumentStateKeys.CLARIFIED_INSTRUCTION)),
                stringValue(checkpointState.get(DocumentStateKeys.RAW_INSTRUCTION))
        );
        String currentInstruction = firstNonBlank(task.getClarifiedInstruction(), task.getRawInstruction());
        return hasText(previousInstruction)
                && hasText(currentInstruction)
                && !Objects.equals(previousInstruction.trim(), currentInstruction.trim());
    }

    private void clearGeneratedState(Map<String, Object> state) {
        GENERATED_STATE_KEYS.forEach(state::remove);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

    private void runSafely(String taskId, String userFeedback, String stepId) {
        try {
            runWorkflow(taskId, userFeedback);
        } catch (ExecutionInterruptedException exception) {
            Thread.interrupted();
        } catch (Exception exception) {
            if (!executionSupport.isCurrentExecution(taskId)) {
                return;
            }
            executionSupport.publishEvent(taskId, stepId, TaskEventType.TASK_FAILED, exception.getMessage());
            throw exception;
        }
    }
}
