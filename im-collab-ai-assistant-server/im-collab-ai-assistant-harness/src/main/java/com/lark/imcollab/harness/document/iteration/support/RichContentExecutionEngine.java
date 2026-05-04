package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RichContentExecutionEngine {

    private final ExecutionStepHandlerRegistry registry;

    public RichContentExecutionEngine(ExecutionStepHandlerRegistry registry) {
        this.registry = registry;
    }

    public RichContentExecutionResult execute(String docRef, DocumentEditPlan plan) {
        if (plan.getExecutionPlan() == null || plan.getExecutionPlan().getSteps() == null) {
            throw new IllegalStateException("RichContentExecutionEngine: executionPlan has no steps");
        }
        List<ExecutionStep> steps = plan.getExecutionPlan().getSteps();
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        seedContext(ctx, plan);
        for (ExecutionStep step : steps) {
            registry.dispatch(step, docRef, ctx);
        }
        return RichContentExecutionResult.builder()
                .createdBlockIds(ctx.getCreatedBlockIds())
                .createdAssetRefs(ctx.getCreatedAssetRefs())
                .beforeRevision(-1L)
                .afterRevision(-1L)
                .build();
    }

    private void seedContext(RichContentExecutionContext ctx, DocumentEditPlan plan) {
        if (plan.getResolvedAssetSpec() == null) return;
        if (plan.getResolvedAssetSpec().getGenerationPrompt() != null) {
            ctx.put("generationPrompt", plan.getResolvedAssetSpec().getGenerationPrompt());
        }
        if (plan.getResolvedAssetSpec().getTableSchema() != null) {
            ctx.put("tableModel", plan.getResolvedAssetSpec().getTableSchema());
        }
        if (plan.getResolvedAssetSpec().getWhiteboardDsl() != null) {
            ctx.put("whiteboardDsl", plan.getResolvedAssetSpec().getWhiteboardDsl());
        }
    }
}
