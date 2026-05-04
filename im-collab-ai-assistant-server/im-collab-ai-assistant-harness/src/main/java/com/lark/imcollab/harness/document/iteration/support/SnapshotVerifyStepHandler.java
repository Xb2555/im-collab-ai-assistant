package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import org.springframework.stereotype.Component;

@Component
public class SnapshotVerifyStepHandler implements ExecutionStepHandler {

    @Override
    public String stepType() {
        return "VERIFY_IMAGE_NODE";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        // 验证由 RichContentTargetStateVerifier 在 engine 完成后统一执行
    }
}
