package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;

public interface ExecutionStepHandler {
    String stepType();
    void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx);
}
