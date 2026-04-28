package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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
        return stateRepository.findSession(taskId).orElseGet(() -> {
            PlanTaskSession session = PlanTaskSession.builder()
                    .taskId(taskId)
                    .planningPhase(PlanningPhaseEnum.ASK_USER)
                    .planScore(0)
                    .aborted(false)
                    .turnCount(0)
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
        session.setVersion(session.getVersion() + 1);
        stateRepository.saveSession(session);
        return session;
    }

    public void checkVersion(PlanTaskSession session, int clientVersion) {
        if (session.getVersion() != clientVersion) {
            throw new com.lark.imcollab.planner.exception.VersionConflictException(
                    "Version conflict: expected " + session.getVersion() + ", got " + clientVersion);
        }
    }

    public PlanTaskSession get(String taskId) {
        return stateRepository.findSession(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + taskId));
    }

    public void markAborted(String taskId, String reason) {
        PlanTaskSession session = get(taskId);
        session.setAborted(true);
        session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        session.setTransitionReason(reason);
        stateRepository.saveSession(session);
        publishEvent(taskId, "ABORTED");
    }

    public void publishEvent(String taskId, String status, RequireInput requireInput) {
        PlanTaskSession session = stateRepository.findSession(taskId).orElse(null);
        int version = session != null ? session.getVersion() : 0;
        java.util.List<com.lark.imcollab.common.model.entity.AgentTaskPlanCard> subtasks =
                session != null && session.getPlanCards() != null
                        ? session.getPlanCards().stream()
                            .flatMap(c -> c.getAgentTaskPlanCards() != null ? c.getAgentTaskPlanCards().stream() : java.util.stream.Stream.empty())
                            .toList()
                        : java.util.List.of();

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

    public void publishEvent(String taskId, String status) {
        publishEvent(taskId, status, null);
    }

    public List<String> getEventJsonList(String taskId) {
        return stateRepository.getEventJsonList(taskId);
    }
}
