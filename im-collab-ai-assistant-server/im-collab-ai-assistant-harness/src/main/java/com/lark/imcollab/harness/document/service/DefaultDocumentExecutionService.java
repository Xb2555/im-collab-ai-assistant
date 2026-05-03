package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultDocumentExecutionService implements DocumentExecutionService {

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
        RunnableConfig config = RunnableConfig.builder().threadId(taskId).build();
        Optional<StateSnapshot> snapshot = documentWorkflow.stateOf(config);
        Map<String, Object> state = new HashMap<>();
        snapshot.ifPresent(s -> state.putAll(s.state().data()));
        state.put(DocumentStateKeys.TASK_ID, taskId);
        state.put(DocumentStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        taskRepository.findById(taskId).ifPresent(task -> {
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
        documentWorkflow.invoke(new OverAllState(state), config);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void runSafely(String taskId, String userFeedback, String stepId) {
        try {
            runWorkflow(taskId, userFeedback);
        } catch (Exception exception) {
            executionSupport.publishEvent(taskId, stepId, TaskEventType.TASK_FAILED, exception.getMessage());
            throw exception;
        }
    }
}
