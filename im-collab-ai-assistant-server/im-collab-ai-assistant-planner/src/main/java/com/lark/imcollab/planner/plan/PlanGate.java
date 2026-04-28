package com.lark.imcollab.planner.plan;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import org.springframework.stereotype.Component;

@Component
public class PlanGate {

    public boolean pass(Task task) {
        return task != null
                && task.getStatus() == TaskStatus.PLAN_READY
                && task.getRawInstruction() != null
                && !task.getRawInstruction().isBlank();
    }
}
