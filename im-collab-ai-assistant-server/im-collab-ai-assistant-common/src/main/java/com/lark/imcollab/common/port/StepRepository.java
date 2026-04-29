package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Step;

import java.util.List;

public interface StepRepository {
    void save(Step step);
    List<Step> findByTaskId(String taskId);
    void updateStatus(String stepId, com.lark.imcollab.common.domain.StepStatus status);
}
