package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerSessionService {

    private final PlannerStateStore stateRepository;
    private final PlannerProperties plannerProperties;
    private final ConcurrentHashMap<String, Integer> eventIndexMap = new ConcurrentHashMap<>();

    public int getLastEventIndex(String taskId) {
        return eventIndexMap.getOrDefault(taskId, 0);
    }

    public void setLastEventIndex(String taskId, int index) {
        eventIndexMap.put(taskId, index);
    }

    public PlanTaskSession getOrCreate(String taskId) {
        return stateRepository.findSession(taskId).map(this::normalizeSession).orElseGet(() -> {
            PlanTaskSession session = PlanTaskSession.builder()
                    .taskId(taskId)
                    .planningPhase(PlanningPhaseEnum.INTAKE)
                    .planScore(0)
                    .aborted(false)
                    .turnCount(0)
                    .scenarioPath(List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING))
                    .profession(plannerProperties.getPrompt().getDefaultProfession())
                    .industry(plannerProperties.getPrompt().getDefaultIndustry())
                    .audience(plannerProperties.getPrompt().getDefaultAudience())
                    .tone(plannerProperties.getPrompt().getDefaultTone())
                    .language(plannerProperties.getPrompt().getDefaultLanguage())
                    .promptProfile(plannerProperties.getPrompt().getProfile())
                    .promptVersion(plannerProperties.getPrompt().getVersion())
                    .build();
            stateRepository.saveSession(session);
            return session;
        });
    }

    public PlanTaskSession save(PlanTaskSession session) {
        normalizeSession(session);
        long expectedStateRevision = session.getStateRevision();
        int originalVersion = session.getVersion();
        session.setVersion(session.getVersion() + 1);
        saveWithStateRevisionCheck(session, expectedStateRevision, originalVersion, true);
        return session;
    }

    public PlanTaskSession saveWithoutVersionChange(PlanTaskSession session) {
        normalizeSession(session);
        long expectedStateRevision = session.getStateRevision();
        saveWithStateRevisionCheck(session, expectedStateRevision, session.getVersion(), false);
        return session;
    }

    public void checkVersion(PlanTaskSession session, int clientVersion) {
        if (session.getVersion() != clientVersion) {
            throw new VersionConflictException(
                    "Version conflict: expected " + session.getVersion() + ", got " + clientVersion);
        }
    }

    private void saveWithStateRevisionCheck(
            PlanTaskSession session,
            long expectedStateRevision,
            int originalVersion,
            boolean userVisibleVersionAdvanced
    ) {
        long nextStateRevision = expectedStateRevision + 1;
        session.setStateRevision(nextStateRevision);
        if (stateRepository.saveSessionIfStateRevision(session, expectedStateRevision)) {
            return;
        }
        if (userVisibleVersionAdvanced) {
            session.setVersion(originalVersion);
        }
        session.setStateRevision(expectedStateRevision);
        PlanTaskSession latest = stateRepository.findSession(session.getTaskId()).orElse(null);
        throw new VersionConflictException("Session state conflict: taskId=" + session.getTaskId()
                + ", expectedStateRevision=" + expectedStateRevision
                + ", actualStateRevision=" + (latest == null ? "missing" : latest.getStateRevision()));
    }

    public PlanTaskSession get(String taskId) {
        return stateRepository.findSession(taskId)
                .map(this::normalizeSession)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + taskId));
    }

    public void markAborted(String taskId, String reason) {
        PlanTaskSession session = get(taskId);
        session.setAborted(true);
        session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        session.setTransitionReason(reason);
        saveWithoutVersionChange(session);
        publishEvent(taskId, "ABORTED");
    }

    private PlanTaskSession normalizeSession(PlanTaskSession session) {
        if (session == null) {
            return null;
        }
        session.setScenarioPath(normalizeScenarioPath(session.getScenarioPath()));
        if (session.getIntentSnapshot() != null) {
            session.getIntentSnapshot().setScenarioPath(normalizeScenarioPath(session.getIntentSnapshot().getScenarioPath()));
        }
        if (session.getPlanBlueprint() != null) {
            session.getPlanBlueprint().setScenarioPath(normalizeScenarioPath(session.getPlanBlueprint().getScenarioPath()));
        }
        return session;
    }

    private List<ScenarioCodeEnum> normalizeScenarioPath(List<?> rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ScenarioCodeEnum> normalized = new LinkedHashSet<>();
        for (Object item : rawPath) {
            ScenarioCodeEnum code = toScenarioCode(item);
            if (code != null) {
                normalized.add(code);
            }
        }
        return new ArrayList<>(normalized);
    }

    private ScenarioCodeEnum toScenarioCode(Object item) {
        if (item instanceof ScenarioCodeEnum code) {
            return code;
        }
        if (item instanceof String text) {
            String normalized = text.trim();
            if (normalized.isBlank()) {
                return null;
            }
            try {
                return ScenarioCodeEnum.valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.debug("Ignoring unsupported scenario path item from session storage: {}", text);
            }
        }
        return null;
    }

    public void publishEvent(String taskId, String status, RequireInput requireInput) {
        PlanTaskSession session = stateRepository.findSession(taskId).orElse(null);
        int version = session != null ? session.getVersion() : 0;
        if (alreadyPublished(taskId, status, version)) {
            log.debug("[{}] Event already published: {} v{}", taskId, status, version);
            return;
        }
        java.util.List<AgentTaskPlanCard> subtasks = applyRuntimeStepStatus(
                taskId,
                session != null && session.getPlanCards() != null
                        ? session.getPlanCards().stream()
                        .flatMap(card -> normalizeSubtasks(card).stream())
                        .toList()
                        : java.util.List.of()
        );

        TaskEvent event = TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .status(status)
                .version(version)
                .subtasks(subtasks)
                .requireInput(requireInput)
                .timestamp(Instant.now())
                .build();
        stateRepository.appendEvent(event);
        log.info("[{}] Event: {} v{}", taskId, status, version);
    }

    private List<AgentTaskPlanCard> applyRuntimeStepStatus(String taskId, List<AgentTaskPlanCard> subtasks) {
        if (taskId == null || taskId.isBlank() || subtasks == null || subtasks.isEmpty()) {
            return subtasks == null ? List.of() : subtasks;
        }
        List<TaskStepRecord> runtimeSteps = stateRepository.findStepsByTaskId(taskId);
        if (runtimeSteps == null || runtimeSteps.isEmpty()) {
            return subtasks;
        }
        Map<String, TaskStepRecord> stepsById = runtimeSteps.stream()
                .filter(step -> step != null && step.getStepId() != null && !step.getStepId().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        TaskStepRecord::getStepId,
                        step -> step,
                        (left, ignored) -> left,
                        java.util.LinkedHashMap::new
                ));
        for (AgentTaskPlanCard subtask : subtasks) {
            if (subtask == null) {
                continue;
            }
            TaskStepRecord step = firstMatchingStep(subtask, stepsById);
            if (step == null) {
                continue;
            }
            subtask.setStatus(toStreamStatus(step.getStatus()));
            subtask.setOutput(step.getOutputSummary());
            subtask.setRetryCount(step.getRetryCount());
        }
        return subtasks;
    }

    private TaskStepRecord firstMatchingStep(AgentTaskPlanCard subtask, Map<String, TaskStepRecord> stepsById) {
        for (String candidate : new String[] {subtask.getParentCardId(), subtask.getId(), subtask.getTaskId()}) {
            if (candidate != null && !candidate.isBlank() && stepsById.containsKey(candidate)) {
                return stepsById.get(candidate);
            }
        }
        return null;
    }

    private String toStreamStatus(StepStatusEnum status) {
        if (status == null) {
            return "pending";
        }
        return switch (status) {
            case PENDING, READY -> "pending";
            case RUNNING -> "running";
            case WAITING_APPROVAL -> "waiting_approval";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case SKIPPED, SUPERSEDED -> status.name().toLowerCase(Locale.ROOT);
        };
    }

    private boolean alreadyPublished(String taskId, String status, int version) {
        if (taskId == null || taskId.isBlank() || status == null || status.isBlank()) {
            return false;
        }
        String statusMarker = "\"status\":\"" + status.replace("\"", "\\\"") + "\"";
        String versionMarker = "\"version\":" + version;
        return stateRepository.getEventJsonList(taskId).stream()
                .anyMatch(eventJson -> eventJson != null
                        && eventJson.contains(statusMarker)
                        && eventJson.contains(versionMarker));
    }

    public void publishEvent(String taskId, String status) {
        publishEvent(taskId, status, null);
    }

    public List<String> getEventJsonList(String taskId) {
        return stateRepository.getEventJsonList(taskId);
    }

    private List<AgentTaskPlanCard> normalizeSubtasks(UserPlanCard card) {
        if (card == null) {
            return List.of();
        }
        if (card.getAgentTaskPlanCards() == null || card.getAgentTaskPlanCards().isEmpty()) {
            return List.of(synthesizeSubtask(card));
        }
        return card.getAgentTaskPlanCards().stream()
                .map(subtask -> normalizeSubtask(card, subtask))
                .filter(java.util.Objects::nonNull)
                .toList();
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
}
