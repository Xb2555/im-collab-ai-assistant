package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskRuntimeService {

    private final PlannerStateStore stateStore;
    private final PlanGraphBuilder planGraphBuilder;
    private final ObjectMapper objectMapper;
    private final TaskRepository taskRepository;
    private final ExecutionContractFactory executionContractFactory;
    private final TaskRuntimeProjectionService projectionService;

    public void projectPlanReady(PlanTaskSession session, TaskEventTypeEnum eventType) {
        if (session == null || session.getPlanBlueprint() == null) {
            return;
        }
        TaskPlanGraph graph = planGraphBuilder.build(session.getTaskId(), session.getPlanBlueprint());
        projectionService.projectPlanGraph(session, graph, eventType);
        syncDomainTask(session, graph);
    }

    public void projectPhaseTransition(String taskId, PlanningPhaseEnum phase, TaskEventTypeEnum eventType) {
        int version = stateStore.findSession(taskId)
                .map(PlanTaskSession::getVersion)
                .orElse(0);
        syncTaskState(taskId, phase, version);
        appendRuntimeEvent(taskId, version, eventType, null);
    }

    public void syncTaskState(String taskId, PlanningPhaseEnum phase) {
        int version = stateStore.findSession(taskId)
                .map(PlanTaskSession::getVersion)
                .orElse(0);
        syncTaskState(taskId, phase, version);
    }

    public TaskRuntimeSnapshot getSnapshot(String taskId) {
        return projectionService.getSnapshot(taskId);
    }

    private TaskRecord buildTaskRecord(PlanTaskSession session, TaskPlanGraph graph) {
        Instant now = Instant.now();
        TaskRecord existing = stateStore.findTask(session.getTaskId()).orElse(null);
        return TaskRecord.builder()
                .taskId(session.getTaskId())
                .conversationKey(session.getIntakeState() == null ? null : session.getIntakeState().getContinuationKey())
                .ownerOpenId(firstNonBlank(inputSenderOpenId(session), existing == null ? null : existing.getOwnerOpenId()))
                .source(firstNonBlank(inputSource(session), existing == null ? null : existing.getSource()))
                .chatId(firstNonBlank(inputChatId(session), existing == null ? null : existing.getChatId()))
                .threadId(firstNonBlank(inputThreadId(session), existing == null ? null : existing.getThreadId()))
                .title(firstNonBlank(graph.getGoal(), session.getPlanBlueprintSummary(), session.getTaskId()))
                .goal(firstNonBlank(graph.getGoal(), session.getPlanBlueprintSummary()))
                .status(mapTaskStatus(session.getPlanningPhase()))
                .currentStage(session.getPlanningPhase() == null ? null : session.getPlanningPhase().name())
                .progress(resolveProgress(graph.getSteps()))
                .artifactIds(resolveArtifactIds(session.getTaskId()))
                .riskFlags(graph.getRisks() == null ? List.of() : graph.getRisks())
                .needUserAction(session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER)
                .version(session.getVersion())
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();
    }

    private void markMissingStepsSuperseded(String taskId, List<TaskStepRecord> nextSteps) {
        List<TaskStepRecord> existingSteps = stateStore.findStepsByTaskId(taskId);
        if (existingSteps == null || existingSteps.isEmpty()) {
            return;
        }
        Set<String> nextStepIds = nextSteps == null
                ? Set.of()
                : nextSteps.stream().map(TaskStepRecord::getStepId).collect(Collectors.toSet());
        for (TaskStepRecord existingStep : existingSteps) {
            if (existingStep == null || nextStepIds.contains(existingStep.getStepId())) {
                continue;
            }
            existingStep.setStatus(StepStatusEnum.SUPERSEDED);
            existingStep.setVersion(existingStep.getVersion() + 1);
            stateStore.saveStep(existingStep);
        }
    }

    private int resolveProgress(List<TaskStepRecord> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        long completed = steps.stream()
                .filter(step -> step.getStatus() != null && step.getStatus().name().equals("COMPLETED"))
                .count();
        return (int) Math.round((completed * 100.0d) / steps.size());
    }

    private List<String> resolveArtifactIds(String taskId) {
        List<ArtifactRecord> artifacts = stateStore.findArtifactsByTaskId(taskId);
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream().map(ArtifactRecord::getArtifactId).toList();
    }

    private TaskStatusEnum mapTaskStatus(PlanningPhaseEnum phase) {
        if (phase == PlanningPhaseEnum.ASK_USER) {
            return TaskStatusEnum.CLARIFYING;
        }
        if (phase == PlanningPhaseEnum.PLAN_READY) {
            return TaskStatusEnum.WAITING_APPROVAL;
        }
        if (phase == PlanningPhaseEnum.EXECUTING) {
            return TaskStatusEnum.EXECUTING;
        }
        if (phase == PlanningPhaseEnum.COMPLETED) {
            return TaskStatusEnum.COMPLETED;
        }
        if (phase == PlanningPhaseEnum.FAILED) {
            return TaskStatusEnum.FAILED;
        }
        if (phase == PlanningPhaseEnum.ABORTED) {
            return TaskStatusEnum.CANCELLED;
        }
        return TaskStatusEnum.PLANNING;
    }

    private void appendRuntimeEvent(String taskId, int version, TaskEventTypeEnum eventType, Object payload) {
        stateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .type(eventType)
                .payloadJson(toJson(payload))
                .version(version)
                .createdAt(Instant.now())
                .build());
    }

    private void syncTaskState(String taskId, PlanningPhaseEnum phase, int version) {
        stateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(mapTaskStatus(phase));
            task.setCurrentStage(phase.name());
            task.setVersion(version);
            task.setUpdatedAt(Instant.now());
            stateStore.saveTask(task);
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void syncDomainTask(PlanTaskSession session, TaskPlanGraph graph) {
        ExecutionContract contract = executionContractFactory.build(session);
        TaskType type = resolveTaskType(contract);
        Task existing = taskRepository.findById(session.getTaskId()).orElse(null);
        Task task = Task.builder()
                .taskId(session.getTaskId())
                .rawInstruction(contract.getRawInstruction())
                .clarifiedInstruction(contract.getClarifiedInstruction())
                .taskBrief(contract.getTaskBrief())
                .executionContract(contract)
                .type(type)
                .status(TaskStatus.PLAN_READY)
                .steps(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .createdAt(existing != null ? existing.getCreatedAt() : Instant.now())
                .updatedAt(Instant.now())
                .build();
        taskRepository.save(task);
    }

    private TaskType resolveTaskType(ExecutionContract contract) {
        if (contract == null || contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
            return TaskType.WRITE_DOC;
        }
        boolean hasDoc = contract.getAllowedArtifacts().stream()
                .anyMatch(value -> "DOC".equalsIgnoreCase(value));
        boolean hasPpt = contract.getAllowedArtifacts().stream()
                .anyMatch(value -> "PPT".equalsIgnoreCase(value));
        if (hasDoc && hasPpt) return TaskType.MIXED;
        if (hasPpt) return TaskType.WRITE_SLIDES;
        return TaskType.WRITE_DOC;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String inputSenderOpenId(PlanTaskSession session) {
        return session == null || session.getInputContext() == null ? null : session.getInputContext().getSenderOpenId();
    }

    private String inputSource(PlanTaskSession session) {
        return session == null || session.getInputContext() == null ? null : session.getInputContext().getInputSource();
    }

    private String inputChatId(PlanTaskSession session) {
        return session == null || session.getInputContext() == null ? null : session.getInputContext().getChatId();
    }

    private String inputThreadId(PlanTaskSession session) {
        return session == null || session.getInputContext() == null ? null : session.getInputContext().getThreadId();
    }
}
