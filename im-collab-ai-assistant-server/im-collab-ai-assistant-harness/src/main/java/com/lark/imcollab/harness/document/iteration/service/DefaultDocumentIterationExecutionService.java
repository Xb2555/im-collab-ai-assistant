package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import com.lark.imcollab.harness.document.iteration.support.DocumentEditPlanBuilder;
import com.lark.imcollab.harness.document.iteration.support.DocumentIterationIntentService;
import com.lark.imcollab.harness.document.iteration.support.DocumentIterationRuntimeSupport;
import com.lark.imcollab.harness.document.iteration.support.DocumentOwnershipGuard;
import com.lark.imcollab.harness.document.iteration.support.DocumentPatchExecutor;
import com.lark.imcollab.harness.document.iteration.support.DocumentTargetLocator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultDocumentIterationExecutionService implements DocumentIterationExecutionService {

    private final DocumentOwnershipGuard ownershipGuard;
    private final DocumentIterationIntentService intentService;
    private final DocumentTargetLocator targetLocator;
    private final DocumentEditPlanBuilder editPlanBuilder;
    private final DocumentPatchExecutor patchExecutor;
    private final DocumentIterationRuntimeSupport runtimeSupport;

    public DefaultDocumentIterationExecutionService(
            DocumentOwnershipGuard ownershipGuard,
            DocumentIterationIntentService intentService,
            DocumentTargetLocator targetLocator,
            DocumentEditPlanBuilder editPlanBuilder,
            DocumentPatchExecutor patchExecutor,
            DocumentIterationRuntimeSupport runtimeSupport
    ) {
        this.ownershipGuard = ownershipGuard;
        this.intentService = intentService;
        this.targetLocator = targetLocator;
        this.editPlanBuilder = editPlanBuilder;
        this.patchExecutor = patchExecutor;
        this.runtimeSupport = runtimeSupport;
    }

    @Override
    public DocumentIterationVO execute(DocumentIterationRequest request) {
        DocumentIterationRuntimeSupport.RuntimeContext runtime = runtimeSupport.start(request);
        try {
            Artifact ownedArtifact = ownershipGuard.assertEditable(request.getDocUrl());
            DocumentIterationIntentType intentType = intentService.resolve(request.getInstruction());
            DocumentTargetSelector selector = targetLocator.locate(ownedArtifact, request.getInstruction());
            DocumentEditPlan editPlan = editPlanBuilder.build(runtime.getTaskId(), intentType, selector, request.getInstruction());
            List<String> modifiedBlocks = editPlan.isRequiresApproval()
                    ? List.of()
                    : patchExecutor.execute(resolveDocRef(ownedArtifact, request.getDocUrl()), editPlan);

            WorkspaceContext context = request.getWorkspaceContext();
            String operator = context == null ? null : context.getSenderOpenId();
            String summary = editPlan.isRequiresApproval()
                    ? "已生成受控编辑计划，等待进一步确认"
                    : buildSummary(intentType, modifiedBlocks, editPlan);
            runtimeSupport.saveSummaryArtifact(
                    runtime,
                    "文档迭代结果",
                    summary + "\n\n" + editPlan.getGeneratedContent(),
                    resolveDocId(ownedArtifact),
                    resolveDocUrl(ownedArtifact, request.getDocUrl()),
                    operator
            );
            if (!editPlan.isRequiresApproval() && intentType != DocumentIterationIntentType.EXPLAIN) {
                runtimeSupport.touchOwnedDocument(ownedArtifact, operator);
            }
            runtimeSupport.complete(runtime, summary);

            return DocumentIterationVO.builder()
                    .taskId(runtime.getTaskId())
                    .planningPhase(editPlan.isRequiresApproval() ? "WAITING_APPROVAL" : "COMPLETED")
                    .recognizedIntent(intentType)
                    .requireInput(editPlan.isRequiresApproval())
                    .preview(editPlan.getReasoningSummary())
                    .docUrl(resolveDocUrl(ownedArtifact, request.getDocUrl()))
                    .modifiedBlocks(modifiedBlocks)
                    .summary(summary)
                    .editPlan(editPlan)
                    .build();
        } catch (RuntimeException exception) {
            runtimeSupport.fail(runtime, exception.getMessage());
            throw exception;
        }
    }

    private String buildSummary(
            DocumentIterationIntentType intentType,
            List<String> modifiedBlocks,
            DocumentEditPlan plan
    ) {
        return switch (intentType) {
            case EXPLAIN -> plan.getGeneratedContent();
            case DELETE -> "已删除目标片段，影响位置数：" + modifiedBlocks.size();
            case INSERT -> "已插入新增内容，新增块数：" + modifiedBlocks.size();
            case UPDATE_CONTENT, UPDATE_STYLE -> "已完成文档改写，影响位置数：" + modifiedBlocks.size();
            case INSERT_MEDIA, ADJUST_LAYOUT -> "已生成待确认计划";
        };
    }

    private String resolveDocRef(Artifact artifact, String requestDocUrl) {
        if (artifact.getExternalUrl() != null && !artifact.getExternalUrl().isBlank()) {
            return artifact.getExternalUrl();
        }
        if (artifact.getDocumentId() != null && !artifact.getDocumentId().isBlank()) {
            return artifact.getDocumentId();
        }
        return requestDocUrl;
    }

    private String resolveDocId(Artifact artifact) {
        return artifact.getDocumentId();
    }

    private String resolveDocUrl(Artifact artifact, String requestDocUrl) {
        return artifact.getExternalUrl() != null && !artifact.getExternalUrl().isBlank()
                ? artifact.getExternalUrl()
                : requestDocUrl;
    }
}
