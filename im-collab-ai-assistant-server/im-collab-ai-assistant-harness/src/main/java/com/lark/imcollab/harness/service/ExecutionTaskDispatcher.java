package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import org.springframework.stereotype.Service;

@Service
public class ExecutionTaskDispatcher {

    private final DocumentExecutionService documentExecutionService;
    private final PresentationExecutionService presentationExecutionService;

    public ExecutionTaskDispatcher(
            DocumentExecutionService documentExecutionService,
            PresentationExecutionService presentationExecutionService) {
        this.documentExecutionService = documentExecutionService;
        this.presentationExecutionService = presentationExecutionService;
    }

    public void dispatch(Task task) {
        switch (task.getType()) {
            case WRITE_SLIDES -> presentationExecutionService.execute(task.getTaskId());
            case MIXED -> {
                documentExecutionService.execute(task.getTaskId());
                presentationExecutionService.execute(task.getTaskId());
            }
            default -> documentExecutionService.execute(task.getTaskId());
        }
    }

    public void resume(String taskId, Approval approval) {
        documentExecutionService.resume(taskId, approval);
    }
}

