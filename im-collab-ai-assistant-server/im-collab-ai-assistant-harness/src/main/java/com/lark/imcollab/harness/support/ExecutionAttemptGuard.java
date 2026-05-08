package com.lark.imcollab.harness.support;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Service;

@Service
public class ExecutionAttemptGuard {

    private final PlannerStateStore stateStore;

    public ExecutionAttemptGuard(PlannerStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public boolean canCommit(String taskId) {
        if (!hasText(taskId)) {
            return false;
        }
        String currentAttempt = ExecutionAttemptContext.currentExecutionAttemptId();
        if (!hasText(currentAttempt)) {
            return true;
        }
        if (hasText(ExecutionAttemptContext.currentTaskId())
                && !taskId.equals(ExecutionAttemptContext.currentTaskId())) {
            return false;
        }
        PlanTaskSession session = stateStore.findSession(taskId).orElse(null);
        if (session == null) {
            return true;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.INTERRUPTING
                || session.getPlanningPhase() == PlanningPhaseEnum.REPLANNING
                || session.getPlanningPhase() == PlanningPhaseEnum.ABORTED) {
            return false;
        }
        if (!hasText(session.getActiveExecutionAttemptId())) {
            return false;
        }
        return currentAttempt.equals(session.getActiveExecutionAttemptId());
    }

    public String currentAttemptId() {
        return ExecutionAttemptContext.currentExecutionAttemptId();
    }

    public int currentPlanVersion(String taskId) {
        return stateStore.findSession(taskId).map(PlanTaskSession::getPlanVersion).orElse(0);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
