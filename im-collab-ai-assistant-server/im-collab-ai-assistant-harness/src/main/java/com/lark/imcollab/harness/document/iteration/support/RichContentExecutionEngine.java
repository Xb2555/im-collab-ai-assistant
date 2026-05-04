package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import com.lark.imcollab.common.model.entity.ResolvedAsset;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RichContentExecutionEngine {

    private final ExecutionStepHandlerRegistry registry;
    private final LarkDocTool larkDocTool;

    public RichContentExecutionEngine(ExecutionStepHandlerRegistry registry, LarkDocTool larkDocTool) {
        this.registry = registry;
        this.larkDocTool = larkDocTool;
    }

    public RichContentExecutionResult execute(String docRef, DocumentEditPlan plan) {
        if (plan.getExecutionPlan() == null || plan.getExecutionPlan().getSteps() == null) {
            throw new IllegalStateException("RichContentExecutionEngine: executionPlan has no steps");
        }
        List<ExecutionStep> steps = plan.getExecutionPlan().getSteps();
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        seedContext(ctx, plan);
        long beforeRevision = fetchRevision(docRef);
        ctx.setBeforeRevision(beforeRevision);

        for (ExecutionStep step : steps) {
            registry.dispatch(step, docRef, ctx);
        }
        long afterRevision = fetchRevision(docRef);
        ctx.setAfterRevision(afterRevision);

        return RichContentExecutionResult.builder()
                .createdBlockIds(ctx.getCreatedBlockIds())
                .createdAssetRefs(ctx.getCreatedAssetRefs())
                .beforeRevision(beforeRevision)
                .afterRevision(afterRevision)
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

    private long fetchRevision(String docRef) {
        try {
            var result = larkDocTool.fetchDoc(docRef, null, "markdown", "simple", null, null, null);
            return result == null ? -1L : result.getRevisionId();
        } catch (Exception ignored) {
            return -1L;
        }
    }
}
