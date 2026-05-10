package com.lark.imcollab.harness.orchestrator;

import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import com.lark.imcollab.harness.summary.service.SummaryExecutionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StepDispatcher {

    private final TaskRepository taskRepository;
    private final StepRepository stepRepository;
    private final TaskEventRepository eventRepository;
    private final DocumentExecutionService documentExecutionService;
    private final PresentationExecutionService presentationExecutionService;
    private final SummaryExecutionService summaryExecutionService;
    private final PlannerStateStore plannerStateStore;

    public void dispatch(Task task) {
        if (isSummaryOnly(task)) {
            summaryExecutionService.execute(task.getTaskId());
            return;
        }
        if (task.getType() == TaskType.WRITE_SLIDES) {
            if (!isPresentationStepCompleted(task.getTaskId())) {
                presentationExecutionService.execute(task.getTaskId());
            }
            if (!isPresentationStepCompleted(task.getTaskId())) {
                return;
            }
            dispatchSummaryIfReady(task);
            return;
        }
        if (task.getType() == TaskType.MIXED) {
            if (!isDocumentStepCompleted(task.getTaskId())) {
                documentExecutionService.execute(task.getTaskId());
            }
            if (!isDocumentStepCompleted(task.getTaskId())) {
                return;
            }
            if (!isPresentationStepCompleted(task.getTaskId())) {
                presentationExecutionService.execute(task.getTaskId());
            }
            if (!isPresentationStepCompleted(task.getTaskId())) {
                return;
            }
            dispatchSummaryIfReady(task);
            return;
        }
        if (!isDocumentStepCompleted(task.getTaskId())) {
            documentExecutionService.execute(task.getTaskId());
        }
        if (!isDocumentStepCompleted(task.getTaskId())) {
            return;
        }
        dispatchSummaryIfReady(task);
    }

    public void resumeAfterApproval(Task task, Approval approval) {
        if (task.getType() == TaskType.WRITE_SLIDES) {
            presentationExecutionService.resume(task.getTaskId(), approval);
            return;
        }
        documentExecutionService.resume(task.getTaskId(), approval);
    }

    private boolean isDocumentStepCompleted(String taskId) {
        var docSteps = plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(step -> step != null)
                .filter(step -> step.getType() == StepTypeEnum.DOC_CREATE
                        || step.getType() == StepTypeEnum.DOC_DRAFT
                        || step.getType() == StepTypeEnum.DOC_EDIT)
                .toList();
        return docSteps.isEmpty() || docSteps.stream().anyMatch(step -> step.getStatus() == StepStatusEnum.COMPLETED);
    }

    private boolean isPresentationStepCompleted(String taskId) {
        var pptSteps = plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(step -> step != null)
                .filter(step -> step.getType() == StepTypeEnum.PPT_CREATE
                        || step.getType() == StepTypeEnum.PPT_OUTLINE)
                .toList();
        return pptSteps.isEmpty() || pptSteps.stream().anyMatch(step -> step.getStatus() == StepStatusEnum.COMPLETED);
    }

    private void dispatchSummaryIfReady(Task task) {
        if (task == null || !hasSummaryStep(task.getTaskId()) || isSummaryStepCompleted(task.getTaskId())) {
            return;
        }
        summaryExecutionService.execute(task.getTaskId());
    }

    private boolean isSummaryOnly(Task task) {
        if (task == null || task.getExecutionContract() == null
                || task.getExecutionContract().getAllowedArtifacts() == null
                || task.getExecutionContract().getAllowedArtifacts().isEmpty()) {
            return false;
        }
        return task.getExecutionContract().getAllowedArtifacts().stream()
                .allMatch(value -> "SUMMARY".equalsIgnoreCase(value));
    }

    private boolean hasSummaryStep(String taskId) {
        return plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(step -> step != null)
                .anyMatch(step -> step.getType() == StepTypeEnum.SUMMARY
                        && step.getStatus() != StepStatusEnum.SUPERSEDED
                        && step.getStatus() != StepStatusEnum.SKIPPED);
    }

    private boolean isSummaryStepCompleted(String taskId) {
        return plannerStateStore.findStepsByTaskId(taskId).stream()
                .filter(step -> step != null)
                .filter(step -> step.getType() == StepTypeEnum.SUMMARY)
                .filter(step -> step.getStatus() != StepStatusEnum.SUPERSEDED
                        && step.getStatus() != StepStatusEnum.SKIPPED)
                .allMatch(step -> step.getStatus() == StepStatusEnum.COMPLETED);
    }
}
