package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;

public interface TaskUserNotificationFacade {

    void notifyExecutionReviewed(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            TaskResultEvaluation evaluation
    );

    default void notifyExecutionFailed(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            String reason
    ) {
    }
}
