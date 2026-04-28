package com.lark.imcollab.harness.orchestrator;

import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StepDispatcher {

    private final TaskRepository taskRepository;
    private final StepRepository stepRepository;
    private final TaskEventRepository eventRepository;
    private final DocumentExecutionService documentExecutionService;
    private final PresentationExecutionService presentationExecutionService;

    public void dispatch(Task task) {
        switch (task.getType()) {
            case WRITE_DOC -> documentExecutionService.execute(task.getTaskId());
            case WRITE_SLIDES -> presentationExecutionService.execute(task.getTaskId());
            default -> documentExecutionService.execute(task.getTaskId());
        }
    }

    public void resumeAfterApproval(Task task, Approval approval) {
        switch (task.getType()) {
            case WRITE_DOC -> documentExecutionService.resume(task.getTaskId(), approval);
            case WRITE_SLIDES -> presentationExecutionService.resume(task.getTaskId(), approval);
            default -> documentExecutionService.resume(task.getTaskId(), approval);
        }
    }
}
