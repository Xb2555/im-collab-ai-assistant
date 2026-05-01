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
        if (task.getType() == TaskType.WRITE_SLIDES) {
            presentationExecutionService.execute(task.getTaskId());
            return;
        }
        if (task.getType() == TaskType.MIXED) {
            documentExecutionService.execute(task.getTaskId());
            presentationExecutionService.execute(task.getTaskId());
            return;
        }
        documentExecutionService.execute(task.getTaskId());
    }

    public void resumeAfterApproval(Task task, Approval approval) {
        if (task.getType() == TaskType.WRITE_SLIDES) {
            presentationExecutionService.resume(task.getTaskId(), approval);
            return;
        }
        documentExecutionService.resume(task.getTaskId(), approval);
    }
}
