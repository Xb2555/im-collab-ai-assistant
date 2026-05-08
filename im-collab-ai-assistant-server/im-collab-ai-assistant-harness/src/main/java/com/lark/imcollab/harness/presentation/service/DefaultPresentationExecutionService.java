package com.lark.imcollab.harness.presentation.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionGuard;
import com.lark.imcollab.harness.presentation.support.PresentationExecutionSupport;
import com.lark.imcollab.harness.presentation.workflow.PresentationStateKeys;
import com.lark.imcollab.harness.support.ExecutionInterruptedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultPresentationExecutionService implements PresentationExecutionService {

    private final CompiledGraph presentationWorkflow;
    private final TaskRepository taskRepository;
    private final PresentationExecutionGuard executionGuard;
    private final PresentationExecutionSupport executionSupport;

    public DefaultPresentationExecutionService(
            @Qualifier("presentationWorkflow") CompiledGraph presentationWorkflow,
            TaskRepository taskRepository,
            PresentationExecutionGuard executionGuard,
            PresentationExecutionSupport executionSupport) {
        this.presentationWorkflow = presentationWorkflow;
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
        Optional<Task> maybeTask = taskRepository.findById(taskId);
        RunnableConfig config = RunnableConfig.builder().threadId(presentationThreadId(taskId, maybeTask)).build();
        Optional<StateSnapshot> snapshot = presentationWorkflow.stateOf(config);
        Map<String, Object> state = new HashMap<>();
        snapshot.ifPresent(s -> state.putAll(s.state().data()));
        state.put(PresentationStateKeys.TASK_ID, taskId);
        state.put(PresentationStateKeys.EXECUTION_ATTEMPT_ID, blankToDefault(ExecutionAttemptContext.currentExecutionAttemptId(), ""));
        state.put(PresentationStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        maybeTask.ifPresent(task -> {
            if (task.getRawInstruction() != null && !task.getRawInstruction().isBlank()) {
                state.put(PresentationStateKeys.RAW_INSTRUCTION, task.getRawInstruction());
            }
            if (task.getClarifiedInstruction() != null && !task.getClarifiedInstruction().isBlank()) {
                state.put(PresentationStateKeys.CLARIFIED_INSTRUCTION, task.getClarifiedInstruction());
            }
            if (task.getTaskBrief() != null && !task.getTaskBrief().isBlank()) {
                state.put(PresentationStateKeys.TASK_BRIEF, task.getTaskBrief());
            }
            if (task.getExecutionContract() != null) {
                state.put(PresentationStateKeys.EXECUTION_CONTRACT, task.getExecutionContract());
                state.put(PresentationStateKeys.SOURCE_SCOPE, task.getExecutionContract().getSourceScope());
                state.put(PresentationStateKeys.CONSTRAINTS, task.getExecutionContract().getConstraints());
                state.put(PresentationStateKeys.ALLOWED_ARTIFACTS, task.getExecutionContract().getAllowedArtifacts());
            }
        });
        presentationWorkflow.invoke(new OverAllState(state), config);
    }

    private String presentationThreadId(String taskId, Optional<Task> maybeTask) {
        return maybeTask
                .map(Task::getExecutionContract)
                .map(contract -> contract.getFrozenAt())
                .map(frozenAt -> taskId + ":presentation:" + frozenAt.toEpochMilli())
                .orElse(taskId + ":presentation");
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
