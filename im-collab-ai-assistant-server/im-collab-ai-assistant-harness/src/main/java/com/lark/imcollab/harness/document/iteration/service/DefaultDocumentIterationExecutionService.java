package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.PendingDocumentIteration;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.BusinessCode;
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
import java.util.Locale;

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
            WorkspaceContext context = request.getWorkspaceContext();
            String operator = context == null ? null : context.getSenderOpenId();
            Artifact ownedArtifact = ownershipGuard.assertEditable(request.getDocUrl(), operator, request.getTaskId());
            DocumentIterationIntentType intentType = intentService.resolve(request.getInstruction());
            DocumentTargetSelector selector = targetLocator.locate(ownedArtifact, intentType, request.getInstruction());
            DocumentEditPlan editPlan = editPlanBuilder.build(runtime.getTaskId(), intentType, selector, request.getInstruction());
            if (editPlan.isRequiresApproval()) {
                runtimeSupport.waitForApproval(runtime, request, editPlan, ownedArtifact, operator);
                String summary = "已生成受控编辑计划，等待进一步确认";
                runtimeSupport.saveSummaryArtifact(
                        runtime,
                        "文档迭代待审批计划",
                        summary + "\n\n" + editPlan.getGeneratedContent(),
                        resolveDocId(ownedArtifact),
                        resolveDocUrl(ownedArtifact, request.getDocUrl()),
                        operator
                );
                return waitingResponse(runtime.getTaskId(), ownedArtifact, request.getDocUrl(), editPlan, summary);
            }
            return applyAndComplete(runtime, ownedArtifact, resolveDocRef(ownedArtifact, request.getDocUrl()), editPlan, operator);
        } catch (RuntimeException exception) {
            runtimeSupport.fail(runtime, exception.getMessage());
            throw exception;
        }
    }

    @Override
    public DocumentIterationVO decide(String taskId, DocumentIterationApprovalRequest request, String operatorOpenId) {
        PendingDocumentIteration pending = runtimeSupport.findPending(taskId)
                .orElseThrow(() -> new AiAssistantException(BusinessCode.NOT_FOUND_ERROR, "未找到待审批的文档迭代任务"));
        DocumentIterationRuntimeSupport.RuntimeContext runtime = new DocumentIterationRuntimeSupport.RuntimeContext(taskId, pending.getStepId());
        try {
            Artifact ownedArtifact = ownershipGuard.assertEditable(pending.getDocUrl(), operatorOpenId, pending.getArtifactTaskId());
            ApprovalStatus status = parseStatus(request == null ? null : request.getAction());
            runtimeSupport.recordApprovalDecision(pending.getStepId(), status, request == null ? null : request.getFeedback());
            if (status == ApprovalStatus.REJECTED) {
                runtimeSupport.reject(runtime, request == null ? "User rejected" : defaultIfBlank(request.getFeedback(), "User rejected"));
                return DocumentIterationVO.builder()
                        .taskId(taskId)
                        .planningPhase("CANCELLED")
                        .recognizedIntent(pending.getIntentType())
                        .requireInput(false)
                        .preview("审批已拒绝")
                        .docUrl(resolveDocUrl(ownedArtifact, pending.getDocUrl()))
                        .modifiedBlocks(List.of())
                        .summary(defaultIfBlank(request == null ? null : request.getFeedback(), "审批已拒绝"))
                        .editPlan(pending.getEditPlan())
                        .build();
            }

            DocumentEditPlan plan = pending.getEditPlan();
            if (status == ApprovalStatus.MODIFIED) {
                String revisedInstruction = defaultIfBlank(request == null ? null : request.getFeedback(), pending.getOriginalRequest().getInstruction());
                DocumentTargetSelector selector = targetLocator.locate(ownedArtifact, pending.getIntentType(), revisedInstruction);
                plan = editPlanBuilder.build(taskId, pending.getIntentType(), selector, revisedInstruction);
                if (plan.isRequiresApproval()) {
                    DocumentIterationRequest revisedRequest = copyRequest(pending.getOriginalRequest(), revisedInstruction);
                    runtimeSupport.waitForApproval(runtime, revisedRequest, plan, ownedArtifact, operatorOpenId);
                    String summary = "已根据反馈重建受控编辑计划，等待再次确认";
                    runtimeSupport.saveSummaryArtifact(
                            runtime,
                            "文档迭代待审批计划",
                            summary + "\n\n" + plan.getGeneratedContent(),
                            resolveDocId(ownedArtifact),
                            resolveDocUrl(ownedArtifact, pending.getDocUrl()),
                            operatorOpenId
                    );
                    return waitingResponse(taskId, ownedArtifact, pending.getDocUrl(), plan, summary);
                }
            }
            runtimeSupport.resumeWaiting(taskId, pending.getStepId());
            return applyAndComplete(runtime, ownedArtifact, resolveDocRef(ownedArtifact, pending.getDocUrl()), plan, operatorOpenId);
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

    private DocumentIterationVO applyAndComplete(
            DocumentIterationRuntimeSupport.RuntimeContext runtime,
            Artifact ownedArtifact,
            String docRef,
            DocumentEditPlan editPlan,
            String operator
    ) {
        DocumentPatchExecutor.PatchExecutionResult patchResult = editPlan.getIntentType() == DocumentIterationIntentType.EXPLAIN
                ? new DocumentPatchExecutor.PatchExecutionResult(List.of(), -1L, -1L)
                : patchExecutor.execute(docRef, editPlan);
        String summary = buildSummary(editPlan.getIntentType(), patchResult.getModifiedBlocks(), editPlan)
                + appendRevisionSummary(patchResult);
        runtimeSupport.saveSummaryArtifact(
                runtime,
                "文档迭代结果",
                summary + "\n\n" + editPlan.getGeneratedContent(),
                resolveDocId(ownedArtifact),
                resolveDocUrl(ownedArtifact, docRef),
                operator
        );
        if (editPlan.getIntentType() != DocumentIterationIntentType.EXPLAIN) {
            runtimeSupport.touchOwnedDocument(ownedArtifact, operator);
        }
        runtimeSupport.complete(runtime, summary);
        return DocumentIterationVO.builder()
                .taskId(runtime.getTaskId())
                .planningPhase("COMPLETED")
                .recognizedIntent(editPlan.getIntentType())
                .requireInput(false)
                .preview(editPlan.getReasoningSummary())
                .docUrl(resolveDocUrl(ownedArtifact, docRef))
                .modifiedBlocks(patchResult.getModifiedBlocks())
                .summary(summary)
                .editPlan(editPlan)
                .build();
    }

    private String appendRevisionSummary(DocumentPatchExecutor.PatchExecutionResult patchResult) {
        if (patchResult.getBeforeRevision() < 0 || patchResult.getAfterRevision() < 0) {
            return "";
        }
        return "，revision: " + patchResult.getBeforeRevision() + " -> " + patchResult.getAfterRevision();
    }

    private DocumentIterationVO waitingResponse(
            String taskId,
            Artifact ownedArtifact,
            String requestDocUrl,
            DocumentEditPlan editPlan,
            String summary
    ) {
        return DocumentIterationVO.builder()
                .taskId(taskId)
                .planningPhase("WAITING_APPROVAL")
                .recognizedIntent(editPlan.getIntentType())
                .requireInput(true)
                .preview(editPlan.getReasoningSummary())
                .docUrl(resolveDocUrl(ownedArtifact, requestDocUrl))
                .modifiedBlocks(List.of())
                .summary(summary)
                .editPlan(editPlan)
                .build();
    }

    private ApprovalStatus parseStatus(String action) {
        if (action == null || action.isBlank()) {
            return ApprovalStatus.APPROVED;
        }
        return switch (action.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE", "APPROVED" -> ApprovalStatus.APPROVED;
            case "REJECT", "REJECTED" -> ApprovalStatus.REJECTED;
            case "MODIFY", "MODIFIED" -> ApprovalStatus.MODIFIED;
            default -> throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "Unsupported approval action: " + action);
        };
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private DocumentIterationRequest copyRequest(DocumentIterationRequest original, String instruction) {
        DocumentIterationRequest request = new DocumentIterationRequest();
        if (original != null) {
            request.setTaskId(original.getTaskId());
            request.setDocUrl(original.getDocUrl());
            request.setWorkspaceContext(original.getWorkspaceContext());
        }
        request.setInstruction(instruction);
        return request;
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
