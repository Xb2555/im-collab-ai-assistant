package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultDocumentExecutionService implements DocumentExecutionService {

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

    private void runSafely(String taskId, String userFeedback, String stepId) {
        try {
            runWorkflow(taskId, userFeedback);
        } catch (Exception exception) {
            executionSupport.publishEvent(taskId, stepId, TaskEventType.TASK_FAILED, exception.getMessage());
            throw exception;
        }
    }
}
