package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultDocumentExecutionService implements DocumentExecutionService {

    private final CompiledGraph documentWorkflow;
    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final DocumentExecutionGuard executionGuard;

    public DefaultDocumentExecutionService(
            @Qualifier("documentWorkflow") CompiledGraph documentWorkflow,
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            DocumentExecutionGuard executionGuard) {
        this.documentWorkflow = documentWorkflow;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.executionGuard = executionGuard;
    }

    @Override
    public void execute(String taskId) {
        executionGuard.execute(taskId, () -> runWorkflow(taskId, null));
        publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
    }

    @Override
    public void resume(String taskId, Approval approval) {
        executionGuard.execute(taskId, () -> runWorkflow(taskId, approval.getUserFeedback()));
        publishEvent(taskId, approval.getStepId(), TaskEventType.STEP_COMPLETED);
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
        });
        documentWorkflow.invoke(new OverAllState(state), config);
    }

    private void publishEvent(String taskId, String stepId, TaskEventType type) {
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .occurredAt(Instant.now())
                .build());
    }
}
