package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.vo.DocumentArtifactApprovalPayload;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
import com.lark.imcollab.common.model.vo.DocumentIterationPlanVO;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import org.springframework.stereotype.Service;

@Service
public class DocumentArtifactIterationFacadeImpl implements DocumentArtifactIterationFacade {

    private final DocumentIterationExecutionService documentIterationExecutionService;

    public DocumentArtifactIterationFacadeImpl(DocumentIterationExecutionService documentIterationExecutionService) {
        this.documentIterationExecutionService = documentIterationExecutionService;
    }

    @Override
    public DocumentArtifactIterationResult edit(DocumentArtifactIterationRequest request) {
        WorkspaceContext workspaceContext = ensureOperatorInWorkspaceContext(request);
        DocumentIterationVO result = documentIterationExecutionService.execute(DocumentIterationRequest.builder()
                .taskId(request.getTaskId())
                .docUrl(request.getDocUrl())
                .instruction(request.getInstruction())
                .workspaceContext(workspaceContext)
                .build());
        return mapResult(result, request.getArtifactId(), request.getDocUrl());
    }

    @Override
    public DocumentArtifactIterationResult decide(
            String iterationTaskId,
            String artifactId,
            String docUrl,
            DocumentIterationApprovalRequest request,
            String operatorOpenId
    ) {
        DocumentIterationVO result = documentIterationExecutionService.decide(iterationTaskId, request, operatorOpenId);
        return mapResult(result, artifactId, docUrl);
    }

    private DocumentArtifactIterationResult mapResult(DocumentIterationVO result, String artifactId, String docUrl) {
        DocumentIterationPlanVO plan = result == null ? null : result.getEditPlan();
        String effectiveDocUrl = result != null && result.getDocUrl() != null && !result.getDocUrl().isBlank()
                ? result.getDocUrl()
                : docUrl;
        return DocumentArtifactIterationResult.builder()
                .taskId(result == null ? null : result.getTaskId())
                .artifactId(artifactId)
                .docUrl(effectiveDocUrl)
                .status(resolveStatus(result))
                .requireInput(result != null && result.isRequireInput())
                .summary(result == null ? null : result.getSummary())
                .preview(result == null ? null : result.getPreview())
                .modifiedBlocks(result == null ? null : result.getModifiedBlocks())
                .approvalPayload(plan == null ? null : DocumentArtifactApprovalPayload.builder()
                        .riskLevel(plan.getRiskLevel())
                        .targetPreview(plan.getTargetPreview())
                        .generatedContent(plan.getGeneratedContent())
                        .build())
                .build();
    }

    private WorkspaceContext ensureOperatorInWorkspaceContext(DocumentArtifactIterationRequest request) {
        if (request == null) {
            return null;
        }
        WorkspaceContext context = request.getWorkspaceContext();
        if (context == null) {
            if (request.getOperatorOpenId() == null || request.getOperatorOpenId().isBlank()) {
                return null;
            }
            return WorkspaceContext.builder()
                    .senderOpenId(request.getOperatorOpenId())
                    .build();
        }
        if (context.getSenderOpenId() != null && !context.getSenderOpenId().isBlank()) {
            return context;
        }
        return WorkspaceContext.builder()
                .selectionType(context.getSelectionType())
                .timeRange(context.getTimeRange())
                .selectedMessages(context.getSelectedMessages())
                .selectedMessageIds(context.getSelectedMessageIds())
                .attachmentRefs(context.getAttachmentRefs())
                .docRefs(context.getDocRefs())
                .chatId(context.getChatId())
                .threadId(context.getThreadId())
                .messageId(context.getMessageId())
                .senderOpenId(request.getOperatorOpenId())
                .chatType(context.getChatType())
                .inputSource(context.getInputSource())
                .continuationMode(context.getContinuationMode())
                .profession(context.getProfession())
                .industry(context.getIndustry())
                .audience(context.getAudience())
                .tone(context.getTone())
                .language(context.getLanguage())
                .promptProfile(context.getPromptProfile())
                .promptVersion(context.getPromptVersion())
                .build();
    }

    private DocumentArtifactIterationStatus resolveStatus(DocumentIterationVO result) {
        if (result == null) {
            return DocumentArtifactIterationStatus.FAILED;
        }
        String phase = result.getPlanningPhase();
        if ("COMPLETED".equalsIgnoreCase(phase)) {
            return DocumentArtifactIterationStatus.COMPLETED;
        }
        if ("WAITING_APPROVAL".equalsIgnoreCase(phase)) {
            return DocumentArtifactIterationStatus.WAITING_APPROVAL;
        }
        return DocumentArtifactIterationStatus.FAILED;
    }
}
