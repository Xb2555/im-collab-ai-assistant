package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class ConversationTaskStateService {

    private final PlannerStateStore stateStore;

    public ConversationTaskStateService(PlannerStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public Optional<ConversationTaskState> find(String conversationKey) {
        if (!hasText(conversationKey)) {
            return Optional.empty();
        }
        return stateStore.findConversationTaskState(conversationKey);
    }

    public void save(ConversationTaskState state) {
        if (state == null || !hasText(state.getConversationKey())) {
            return;
        }
        state.setUpdatedAt(state.getUpdatedAt() == null ? Instant.now() : state.getUpdatedAt());
        stateStore.saveConversationTaskState(state);
    }

    public void clearExecuting(String conversationKey, String taskId) {
        if (!hasText(conversationKey) || !hasText(taskId)) {
            return;
        }
        stateStore.clearConversationExecutingTask(conversationKey, taskId);
    }

    public void syncFromSession(PlanTaskSession session) {
        if (session == null) {
            return;
        }
        String conversationKey = resolveConversationKey(session);
        if (!hasText(conversationKey) || !hasText(session.getTaskId())) {
            return;
        }
        ConversationTaskState state = find(conversationKey)
                .orElseGet(() -> ConversationTaskState.builder().conversationKey(conversationKey).build());
        state.setConversationKey(conversationKey);
        state.setUpdatedAt(Instant.now());

        PlanningPhaseEnum phase = session.getPlanningPhase();
        if (phase == PlanningPhaseEnum.EXECUTING) {
            state.setActiveTaskId(session.getTaskId());
            state.setExecutingTaskId(session.getTaskId());
        } else if (phase == PlanningPhaseEnum.COMPLETED) {
            state.setActiveTaskId(session.getTaskId());
            state.setLastCompletedTaskId(session.getTaskId());
            state.setExecutingTaskId(null);
        } else if (phase == PlanningPhaseEnum.FAILED || phase == PlanningPhaseEnum.ABORTED) {
            state.setActiveTaskId(session.getTaskId());
            if (session.getTaskId().equals(state.getExecutingTaskId())) {
                state.setExecutingTaskId(null);
            }
        } else if (phase == PlanningPhaseEnum.PLAN_READY) {
            state.setActiveTaskId(session.getTaskId());
            if (session.getTaskId().equals(state.getExecutingTaskId())) {
                state.setExecutingTaskId(null);
            }
        } else if (shouldTrackAsActive(session)) {
            state.setActiveTaskId(session.getTaskId());
        }
        save(state);
    }

    private boolean shouldTrackAsActive(PlanTaskSession session) {
        if (session.getIntakeState() == null) {
            return false;
        }
        TaskIntakeTypeEnum intakeType = session.getIntakeState().getIntakeType();
        if (intakeType == null) {
            return false;
        }
        return intakeType != TaskIntakeTypeEnum.STATUS_QUERY
                && intakeType != TaskIntakeTypeEnum.CANCEL_TASK
                && intakeType != TaskIntakeTypeEnum.CONFIRM_ACTION;
    }

    private String resolveConversationKey(PlanTaskSession session) {
        if (session.getIntakeState() != null && hasText(session.getIntakeState().getContinuationKey())) {
            return session.getIntakeState().getContinuationKey();
        }
        return stateStore.findTask(session.getTaskId())
                .map(TaskRecord::getConversationKey)
                .filter(this::hasText)
                .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
