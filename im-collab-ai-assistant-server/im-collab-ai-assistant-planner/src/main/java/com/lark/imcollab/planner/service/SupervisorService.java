package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.SupervisorLoopState;
import com.lark.imcollab.common.model.entity.TaskIntent;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import org.springframework.stereotype.Service;

@Service
public class SupervisorService extends AbstractSupervisorLoop {

    @Override
    protected SupervisorLoopState initSupervisorLoopState(TaskIntent taskIntent) {
        return null;
    }

    @Override
    protected void callModel(SupervisorLoopState state) {

    }

    @Override
    protected TaskIntent parseTaskIntent(String rawInstruction, InputSourceEnum inputSource) {
        return null;
    }

    @Override
    protected boolean needsFollowUp(SupervisorLoopState state) {
        return false;
    }

    @Override
    protected void dispatchSubAgents(SupervisorLoopState state) {

    }

    @Override
    protected void collectToolResultsAndUpdateState(SupervisorLoopState state) {

    }
}
