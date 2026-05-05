package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RichContentExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(RichContentExecutionEngine.class);

    private final ExecutionStepHandlerRegistry registry;
    private final LarkDocReadGateway readGateway;

    public RichContentExecutionEngine(ExecutionStepHandlerRegistry registry, LarkDocReadGateway readGateway) {
        this.registry = registry;
        this.readGateway = readGateway;
    }

    public RichContentExecutionResult execute(String docRef, DocumentEditPlan plan) {
        if (plan.getExecutionPlan() == null || plan.getExecutionPlan().getSteps() == null) {
            throw new IllegalStateException("RichContentExecutionEngine: executionPlan has no steps");
        }
        long beforeRevision = fetchRevision(docRef);
        List<ExecutionStep> steps = plan.getExecutionPlan().getSteps();
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        seedContext(ctx, plan);
        for (ExecutionStep step : steps) {
            registry.dispatch(step, docRef, ctx);
        }
        long afterRevision = fetchRevision(docRef);
        RichContentExecutionResult result = RichContentExecutionResult.builder()
                .createdBlockIds(ctx.getCreatedBlockIds())
                .createdAssetRefs(ctx.getCreatedAssetRefs())
                .beforeRevision(beforeRevision)
                .afterRevision(afterRevision)
                .build();
        log.info("DOC_ITER_RICH_EXEC docRef={} action={} steps={} createdBlockIds={} createdAssetRefs={} beforeRevision={} afterRevision={}",
                docRef,
                plan.getSemanticAction(),
                steps.stream().map(ExecutionStep::getStepType).toList(),
                result.getCreatedBlockIds(),
                result.getCreatedAssetRefs(),
                beforeRevision,
                afterRevision);
        return result;
    }

    private long fetchRevision(String docRef) {
        try {
            var outline = readGateway.fetchDocOutline(docRef);
            Long rev = outline.getRevisionId();
            return rev != null ? rev : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private void seedContext(RichContentExecutionContext ctx, DocumentEditPlan plan) {
        if (plan.getResolvedAssetSpec() == null) return;
        if (plan.getResolvedAssetSpec().getGenerationPrompt() != null) {
            ctx.put("generationPrompt", plan.getResolvedAssetSpec().getGenerationPrompt());
        }
        if (plan.getResolvedAssetSpec().getCaption() != null) {
            ctx.put("imageCaption", plan.getResolvedAssetSpec().getCaption());
        }
        if (plan.getResolvedAssetSpec().getAltText() != null) {
            ctx.put("imageAltText", plan.getResolvedAssetSpec().getAltText());
        }
        if (plan.getResolvedAssetSpec().getTableSchema() != null) {
            ctx.put("tableModel", plan.getResolvedAssetSpec().getTableSchema());
        }
        if (plan.getResolvedAssetSpec().getWhiteboardDsl() != null) {
            ctx.put("whiteboardDsl", plan.getResolvedAssetSpec().getWhiteboardDsl());
        }
    }
}
