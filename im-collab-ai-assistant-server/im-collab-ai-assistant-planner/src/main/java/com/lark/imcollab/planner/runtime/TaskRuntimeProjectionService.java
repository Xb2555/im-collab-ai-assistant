package com.lark.imcollab.planner.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskRuntimeProjectionService {

    private final PlannerStateStore stateStore;
    private final ObjectMapper objectMapper;

    public TaskRuntimeProjectionService(PlannerStateStore stateStore, ObjectMapper objectMapper) {
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    public void projectStage(PlanTaskSession session, TaskEventTypeEnum eventType, Object payload) {
        if (session == null || session.getTaskId() == null || session.getTaskId().isBlank()) {
            return;
        }
        Instant now = Instant.now();
        TaskRecord existing = stateStore.findTask(session.getTaskId()).orElse(null);
        List<TaskStepRecord> existingSteps = stateStore.findStepsByTaskId(session.getTaskId());
        TaskRecord task = TaskRecord.builder()
                .taskId(session.getTaskId())
                .conversationKey(session.getIntakeState() == null ? null : session.getIntakeState().getContinuationKey())
                .ownerOpenId(firstNonBlank(inputSenderOpenId(session), existing == null ? null : existing.getOwnerOpenId()))
                .source(firstNonBlank(inputSource(session), existing == null ? null : existing.getSource()))
                .chatId(firstNonBlank(inputChatId(session), existing == null ? null : existing.getChatId()))
                .threadId(firstNonBlank(inputThreadId(session), existing == null ? null : existing.getThreadId()))
                .title(firstNonBlank(session.getPlanBlueprintSummary(), session.getClarifiedInstruction(), session.getRawInstruction(), session.getTaskId()))
                .goal(firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction(), session.getPlanBlueprintSummary()))
                .status(mapTaskStatus(session.getPlanningPhase(), eventType))
                .currentStage(resolveCurrentStage(session.getPlanningPhase(), eventType))
                .progress(existing == null ? resolveProgress(existingSteps) : existing.getProgress())
                .artifactIds(existing == null ? resolveArtifactIds(session.getTaskId())
                        : existing.getArtifactIds() == null ? List.of() : existing.getArtifactIds())
                .riskFlags(existing == null || existing.getRiskFlags() == null ? List.of() : existing.getRiskFlags())
                .needUserAction(needsUserAction(session.getPlanningPhase()))
                .version(session.getVersion())
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();
        stateStore.saveTask(task);
        appendRuntimeEvent(session.getTaskId(), session.getVersion(), eventType, payload);
        appendPlannerStreamEvent(session, eventType.name());
    }

    public void projectPlanGraph(PlanTaskSession session, TaskPlanGraph graph, TaskEventTypeEnum eventType) {
        if (session == null || graph == null) {
            return;
        }
        Instant now = Instant.now();
        TaskRecord existing = stateStore.findTask(session.getTaskId()).orElse(null);
        TaskRecord task = TaskRecord.builder()
                .taskId(session.getTaskId())
                .conversationKey(session.getIntakeState() == null ? null : session.getIntakeState().getContinuationKey())
                .ownerOpenId(firstNonBlank(inputSenderOpenId(session), existing == null ? null : existing.getOwnerOpenId()))
                .source(firstNonBlank(inputSource(session), existing == null ? null : existing.getSource()))
                .chatId(firstNonBlank(inputChatId(session), existing == null ? null : existing.getChatId()))
                .threadId(firstNonBlank(inputThreadId(session), existing == null ? null : existing.getThreadId()))
                .title(resolvePlanTitle(session, graph, eventType, existing))
                .goal(firstNonBlank(graph.getGoal(), session.getClarifiedInstruction(), session.getRawInstruction()))
                .status(mapTaskStatus(session.getPlanningPhase()))
                .currentStage(session.getPlanningPhase() == null ? null : session.getPlanningPhase().name())
                .progress(resolveProgress(graph.getSteps()))
                .artifactIds(resolveArtifactIds(session.getTaskId()))
                .riskFlags(graph.getRisks() == null ? List.of() : graph.getRisks())
                .needUserAction(needsUserAction(session.getPlanningPhase()))
                .version(session.getVersion())
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();
        stateStore.saveTask(task);
        markMissingStepsSuperseded(session.getTaskId(), graph.getSteps());
        if (graph.getSteps() != null) {
            graph.getSteps().forEach(stateStore::saveStep);
        }
        appendRuntimeEvent(session.getTaskId(), session.getVersion(), eventType, graph);
        appendPlannerStreamEvent(session, eventType.name());
    }

    public TaskRuntimeSnapshot getSnapshot(String taskId) {
        TaskRecord task = reconcileTaskVersion(taskId);
        return TaskRuntimeSnapshot.builder()
                .task(task)
                .steps(activeSteps(stateStore.findStepsByTaskId(taskId)))
                .artifacts(visibleArtifacts(taskId))
                .events(stateStore.findRuntimeEventsByTaskId(taskId))
                .build();
    }

    private TaskRecord reconcileTaskVersion(String taskId) {
        TaskRecord task = stateStore.findTask(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        PlanTaskSession session = stateStore.findSession(taskId).orElse(null);
        if (session == null || task.getVersion() == session.getVersion()) {
            return task;
        }
        task.setVersion(session.getVersion());
        task.setUpdatedAt(Instant.now());
        stateStore.saveTask(task);
        return task;
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
        List<TaskStepRecord> activeSteps = activeSteps(steps);
        if (activeSteps.isEmpty()) {
            return 0;
        }
        long completed = activeSteps.stream()
                .filter(step -> step.getStatus() == StepStatusEnum.COMPLETED)
                .count();
        return (int) Math.round((completed * 100.0d) / activeSteps.size());
    }

    private List<TaskStepRecord> activeSteps(List<TaskStepRecord> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .toList();
    }

    private List<String> resolveArtifactIds(String taskId) {
        List<ArtifactRecord> artifacts = visibleArtifacts(taskId);
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream().map(ArtifactRecord::getArtifactId).toList();
    }

    private List<ArtifactRecord> visibleArtifacts(String taskId) {
        return visibleArtifacts(stateStore.findArtifactsByTaskId(taskId));
    }

    private List<ArtifactRecord> visibleArtifacts(List<ArtifactRecord> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> hasFinalByDisplayKey = new LinkedHashMap<>();
        for (ArtifactRecord artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            String key = artifactDisplayKey(artifact);
            hasFinalByDisplayKey.merge(key, hasText(artifact.getUrl()), Boolean::logicalOr);
        }
        return artifacts.stream()
                .filter(artifact -> artifact != null)
                .filter(artifact -> {
                    Boolean hasFinal = hasFinalByDisplayKey.get(artifactDisplayKey(artifact));
                    return !Boolean.TRUE.equals(hasFinal) || hasText(artifact.getUrl());
                })
                .toList();
    }

    private String artifactDisplayKey(ArtifactRecord artifact) {
        String title = artifact.getTitle();
        if (!hasText(title)) {
            return "artifact:" + artifact.getArtifactId();
        }
        String type = artifact.getType() == null ? "" : artifact.getType().name();
        return type + "|" + title.trim();
    }

    private String resolvePlanTitle(
            PlanTaskSession session,
            TaskPlanGraph graph,
            TaskEventTypeEnum eventType,
            TaskRecord existing
    ) {
        if (eventType == TaskEventTypeEnum.PLAN_ADJUSTED) {
            String planTitle = buildPlanTitle(graph.getSteps());
            if (planTitle != null) {
                return planTitle;
            }
        }
        return firstNonBlank(
                graph.getGoal(),
                session.getPlanBlueprintSummary(),
                existing == null ? null : existing.getTitle(),
                session.getTaskId()
        );
    }

    private String buildPlanTitle(List<TaskStepRecord> steps) {
        List<String> items = activeSteps(steps).stream()
                .map(this::stepTitleItem)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (items.isEmpty()) {
            return null;
        }
        int maxItems = Math.min(items.size(), 4);
        String joined = joinChinese(items.subList(0, maxItems));
        String suffix = items.size() > maxItems ? "等" + items.size() + "项任务" : "";
        return "生成" + joined + suffix;
    }

    private String stepTitleItem(TaskStepRecord step) {
        if (step == null) {
            return null;
        }
        String name = firstNonBlank(step.getName(), step.getType() == null ? null : step.getType().name());
        if (name == null) {
            return null;
        }
        String normalized = name.trim()
                .replaceFirst("^(先|再|然后|最后|并)?\\s*(生成|创建|撰写|输出|整理|补充|制作|准备)", "")
                .trim();
        return normalized.isBlank() ? name.trim() : normalized;
    }

    private String joinChinese(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + "和" + items.get(1);
        }
        return String.join("、", items.subList(0, items.size() - 1)) + "和" + items.get(items.size() - 1);
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

    private void appendPlannerStreamEvent(PlanTaskSession session, String status) {
        if (session == null || session.getTaskId() == null || session.getTaskId().isBlank()) {
            return;
        }
        stateStore.appendEvent(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(session.getTaskId())
                .status(status)
                .version(session.getVersion())
                .subtasks(normalizeSubtasks(session.getPlanCards()))
                .timestamp(Instant.now())
                .build());
    }

    private List<AgentTaskPlanCard> normalizeSubtasks(List<UserPlanCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        return cards.stream()
                .filter(card -> card != null)
                .flatMap(card -> subtasksFor(card).stream()
                        .map(subtask -> normalizeSubtask(card, subtask))
                        .filter(java.util.Objects::nonNull))
                .toList();
    }

    private List<AgentTaskPlanCard> subtasksFor(UserPlanCard card) {
        if (card.getAgentTaskPlanCards() != null && !card.getAgentTaskPlanCards().isEmpty()) {
            return card.getAgentTaskPlanCards();
        }
        return List.of(synthesizeSubtask(card));
    }

    private AgentTaskPlanCard synthesizeSubtask(UserPlanCard card) {
        AgentTaskTypeEnum taskType = null;
        if (card.getType() != null) {
            taskType = switch (card.getType()) {
                case PPT -> AgentTaskTypeEnum.WRITE_SLIDES;
                case SUMMARY -> AgentTaskTypeEnum.GENERATE_SUMMARY;
                case DOC -> AgentTaskTypeEnum.WRITE_DOC;
            };
        }
        return AgentTaskPlanCard.builder()
                .taskId(firstNonBlank(card.getCardId(), card.getTaskId()))
                .id(firstNonBlank(card.getCardId(), card.getTaskId()))
                .parentCardId(card.getCardId())
                .taskType(taskType)
                .type(taskType == null ? null : taskType.name())
                .title(firstNonBlank(card.getTitle(), card.getDescription(), card.getCardId()))
                .status(card.getStatus())
                .input(card.getDescription())
                .context(card.getDescription())
                .tools(List.of())
                .build();
    }

    private AgentTaskPlanCard normalizeSubtask(UserPlanCard card, AgentTaskPlanCard subtask) {
        if (subtask == null) {
            return null;
        }
        if (subtask.getId() == null || subtask.getId().isBlank()) {
            subtask.setId(firstNonBlank(subtask.getTaskId(), card.getCardId()));
        }
        if (subtask.getType() == null || subtask.getType().isBlank()) {
            subtask.setType(subtask.getTaskType() == null ? null : subtask.getTaskType().name());
        }
        if (subtask.getTitle() == null || subtask.getTitle().isBlank()) {
            subtask.setTitle(firstNonBlank(card.getTitle(), subtask.getContext(), subtask.getInput()));
        }
        return subtask;
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

    private TaskStatusEnum mapTaskStatus(PlanningPhaseEnum phase, TaskEventTypeEnum eventType) {
        if (eventType == TaskEventTypeEnum.PLAN_REVIEWING
                || eventType == TaskEventTypeEnum.PLAN_GATE_CHECKING) {
            return TaskStatusEnum.REVIEWING;
        }
        return mapTaskStatus(phase);
    }

    private String resolveCurrentStage(PlanningPhaseEnum phase, TaskEventTypeEnum eventType) {
        if (eventType == TaskEventTypeEnum.PLAN_REVIEWING
                || eventType == TaskEventTypeEnum.PLAN_GATE_CHECKING
                || eventType == TaskEventTypeEnum.CONTEXT_CHECKING
                || eventType == TaskEventTypeEnum.CONTEXT_COLLECTING
                || eventType == TaskEventTypeEnum.CONTEXT_COLLECTED
                || eventType == TaskEventTypeEnum.INTENT_ROUTING
                || eventType == TaskEventTypeEnum.PLANNING_STARTED
                || eventType == TaskEventTypeEnum.CLARIFICATION_REQUIRED
                || eventType == TaskEventTypeEnum.PLAN_FAILED) {
            return eventType.name();
        }
        return phase == null ? null : phase.name();
    }

    private boolean needsUserAction(PlanningPhaseEnum phase) {
        return phase == PlanningPhaseEnum.ASK_USER || phase == PlanningPhaseEnum.PLAN_READY;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
