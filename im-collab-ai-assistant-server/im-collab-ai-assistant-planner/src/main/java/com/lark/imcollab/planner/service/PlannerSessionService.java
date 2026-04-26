package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.repository.PlannerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerSessionService {

    private final PlannerStateRepository stateRepository;

    public PlanTaskSession getOrCreate(String taskId) {
        return stateRepository.findSession(taskId).orElseGet(() -> {
            PlanTaskSession session = PlanTaskSession.builder()
                    .taskId(taskId)
                    .planningPhase(PlanningPhaseEnum.ASK_USER)
                    .planScore(0)
                    .aborted(false)
                    .turnCount(0)
                    .build();
            stateRepository.saveSession(session);
            return session;
        });
    }

    public PlanTaskSession save(PlanTaskSession session) {
        stateRepository.saveSession(session);
        return session;
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
        publishEvent(taskId, "ABORTED", reason);
    }

    public void publishEvent(String taskId, String eventType, String payload) {
        TaskEvent event = TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .eventType(eventType)
                .payload(payload)
                .timestamp(Instant.now())
                .build();
        stateRepository.appendEvent(event);
        log.info("[{}] Event: {} - {}", taskId, eventType, payload);
    }

    public List<String> getEventJsonList(String taskId) {
        return stateRepository.getEventJsonList(taskId);
    }
}
