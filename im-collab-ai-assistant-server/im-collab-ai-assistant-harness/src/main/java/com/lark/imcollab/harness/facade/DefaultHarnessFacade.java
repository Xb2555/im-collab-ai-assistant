package com.lark.imcollab.harness.facade;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.harness.orchestrator.ExecutionOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultHarnessFacade implements HarnessFacade {

    private final ExecutionOrchestrator orchestrator;

    @Override
    public Task startExecution(String taskId) {
        return orchestrator.start(taskId);
    }

    @Override
    public Task resumeExecution(String taskId, Approval approval) {
        return orchestrator.resume(taskId, approval);
    }

    @Override
    public Task abortExecution(String taskId) {
        return orchestrator.abort(taskId);
    }
}
