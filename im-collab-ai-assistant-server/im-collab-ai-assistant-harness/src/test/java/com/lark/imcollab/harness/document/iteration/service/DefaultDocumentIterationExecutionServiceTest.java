package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.PendingDocumentIteration;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import com.lark.imcollab.harness.document.iteration.support.DocumentEditPlanBuilder;
import com.lark.imcollab.harness.document.iteration.support.DocumentIterationIntentService;
import com.lark.imcollab.harness.document.iteration.support.DocumentIterationRuntimeSupport;
import com.lark.imcollab.harness.document.iteration.support.DocumentOwnershipGuard;
import com.lark.imcollab.harness.document.iteration.support.DocumentPatchExecutor;
import com.lark.imcollab.harness.document.iteration.support.DocumentTargetLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDocumentIterationExecutionServiceTest {

    @Mock private DocumentOwnershipGuard ownershipGuard;
    @Mock private DocumentIterationIntentService intentService;
    @Mock private DocumentTargetLocator targetLocator;
    @Mock private DocumentEditPlanBuilder editPlanBuilder;
    @Mock private DocumentPatchExecutor patchExecutor;
    @Mock private DocumentIterationRuntimeSupport runtimeSupport;

    private DefaultDocumentIterationExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDocumentIterationExecutionService(
                ownershipGuard,
                intentService,
                targetLocator,
                editPlanBuilder,
                patchExecutor,
                runtimeSupport
        );
    }

    @Test
    void explainDoesNotTouchOwnedDocument() {
        DocumentIterationRequest request = request();
        Artifact artifact = ownedArtifact();
        DocumentTargetSelector selector = selector();
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.EXPLAIN)
                .selector(selector)
                .reasoningSummary("readonly")
                .generatedContent("这是解释")
                .riskLevel(DocumentRiskLevel.LOW)
                .patchOperations(List.of())
                .build();
        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentService.resolve(anyString())).thenReturn(DocumentIterationIntentType.EXPLAIN);
        when(targetLocator.locate(any(), anyString())).thenReturn(selector);
        when(editPlanBuilder.build(anyString(), any(), any(), anyString())).thenReturn(plan);

        DocumentIterationVO response = service.execute(request);

        assertThat(response.getRecognizedIntent()).isEqualTo(DocumentIterationIntentType.EXPLAIN);
        verify(runtimeSupport, never()).touchOwnedDocument(any(), any());
        verify(runtimeSupport).complete(any(), anyString());
    }

    @Test
    void updateTouchesOwnedDocumentAfterPatch() {
        DocumentIterationRequest request = request();
        Artifact artifact = ownedArtifact();
        DocumentTargetSelector selector = selector();
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .selector(selector)
                .reasoningSummary("rewrite")
                .generatedContent("新内容")
                .toolCommandType(DocumentPatchOperationType.STR_REPLACE)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.STR_REPLACE)
                        .oldText("旧内容")
                        .newContent("新内容")
                        .docFormat("markdown")
                        .build()))
                .build();
        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentService.resolve(anyString())).thenReturn(DocumentIterationIntentType.UPDATE_CONTENT);
        when(targetLocator.locate(any(), anyString())).thenReturn(selector);
        when(editPlanBuilder.build(anyString(), any(), any(), anyString())).thenReturn(plan);
        when(patchExecutor.execute(anyString(), any()))
                .thenReturn(new DocumentPatchExecutor.PatchExecutionResult(List.of("text-match"), 1L, 2L));

        DocumentIterationVO response = service.execute(request);

        assertThat(response.getModifiedBlocks()).containsExactly("text-match");
        verify(runtimeSupport).touchOwnedDocument(any(), any());
        verify(runtimeSupport).complete(any(), anyString());
    }

    @Test
    void requiresApprovalWaitsInsteadOfCompleting() {
        DocumentIterationRequest request = request();
        Artifact artifact = ownedArtifact();
        DocumentTargetSelector selector = selector();
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.DELETE)
                .selector(selector)
                .reasoningSummary("delete whole section")
                .generatedContent("")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId("blk1,blk2")
                        .build()))
                .build();
        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentService.resolve(anyString())).thenReturn(DocumentIterationIntentType.DELETE);
        when(targetLocator.locate(any(), anyString())).thenReturn(selector);
        when(editPlanBuilder.build(anyString(), any(), any(), anyString())).thenReturn(plan);

        DocumentIterationVO response = service.execute(request);

        assertThat(response.getPlanningPhase()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.isRequireInput()).isTrue();
        verify(runtimeSupport).waitForApproval(any(), any(), any(), any(), anyString());
        verify(runtimeSupport, never()).complete(any(), anyString());
        verify(patchExecutor, never()).execute(anyString(), any());
    }

    @Test
    void decideRejectCancelsPendingTask() {
        PendingDocumentIteration pending = PendingDocumentIteration.builder()
                .taskId("doc-iter-1")
                .stepId("step-1")
                .docUrl("https://example.feishu.cn/docx/doc123")
                .artifactTaskId("task-1")
                .intentType(DocumentIterationIntentType.DELETE)
                .editPlan(DocumentEditPlan.builder().intentType(DocumentIterationIntentType.DELETE).build())
                .originalRequest(request())
                .build();
        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), anyString())).thenReturn(ownedArtifact());

        DocumentIterationVO response = service.decide("doc-iter-1",
                new DocumentIterationApprovalRequest("REJECT", "不要删了"),
                "ou-user");

        assertThat(response.getPlanningPhase()).isEqualTo("CANCELLED");
        verify(runtimeSupport).recordApprovalDecision("step-1", com.lark.imcollab.common.domain.ApprovalStatus.REJECTED, "不要删了");
        verify(runtimeSupport).reject(any(), anyString());
    }

    @Test
    void ownershipFailureIsSurfaced() {
        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull()))
                .thenThrow(new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "当前用户无权编辑该系统文档"));

        assertThatThrownBy(() -> service.execute(request()))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("无权编辑");
    }

    @Test
    void decideModifiedThatStillRequiresApprovalStaysWaiting() {
        PendingDocumentIteration pending = PendingDocumentIteration.builder()
                .taskId("doc-iter-1")
                .stepId("step-1")
                .docUrl("https://example.feishu.cn/docx/doc123")
                .artifactTaskId("task-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .editPlan(DocumentEditPlan.builder().intentType(DocumentIterationIntentType.UPDATE_CONTENT).build())
                .originalRequest(request())
                .build();
        DocumentTargetSelector selector = selector();
        DocumentEditPlan replanned = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .selector(selector)
                .reasoningSummary("still risky")
                .generatedContent("新方案")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .build();
        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), eq("task-1"))).thenReturn(ownedArtifact());
        when(targetLocator.locate(any(), eq("请改成整章重写"))).thenReturn(selector);
        when(editPlanBuilder.build(eq("doc-iter-1"), eq(DocumentIterationIntentType.UPDATE_CONTENT), eq(selector), eq("请改成整章重写")))
                .thenReturn(replanned);

        DocumentIterationVO response = service.decide("doc-iter-1",
                new DocumentIterationApprovalRequest("MODIFY", "请改成整章重写"),
                "ou-user");

        assertThat(response.getPlanningPhase()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.isRequireInput()).isTrue();
        verify(runtimeSupport).recordApprovalDecision("step-1", com.lark.imcollab.common.domain.ApprovalStatus.MODIFIED, "请改成整章重写");
        verify(runtimeSupport).waitForApproval(any(), any(), eq(replanned), any(), eq("ou-user"));
        verify(runtimeSupport, never()).resumeWaiting(anyString(), anyString());
        verify(patchExecutor, never()).execute(anyString(), any());
    }

    @Test
    void decidePatchFailureMarksTaskFailed() {
        PendingDocumentIteration pending = PendingDocumentIteration.builder()
                .taskId("doc-iter-1")
                .stepId("step-1")
                .docUrl("https://example.feishu.cn/docx/doc123")
                .artifactTaskId("task-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .editPlan(DocumentEditPlan.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .reasoningSummary("approved")
                        .generatedContent("新内容")
                        .build())
                .originalRequest(request())
                .build();
        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), eq("task-1"))).thenReturn(ownedArtifact());
        when(runtimeSupport.resumeWaiting("doc-iter-1", "step-1"))
                .thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(patchExecutor.execute(anyString(), any())).thenThrow(new IllegalStateException("verify failed"));

        assertThatThrownBy(() -> service.decide("doc-iter-1",
                new DocumentIterationApprovalRequest("APPROVE", "执行"),
                "ou-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify failed");

        verify(runtimeSupport).resumeWaiting("doc-iter-1", "step-1");
        verify(runtimeSupport).fail(any(), eq("verify failed"));
    }

    private DocumentIterationRequest request() {
        WorkspaceContext context = new WorkspaceContext();
        context.setSenderOpenId("ou-user");
        DocumentIterationRequest request = new DocumentIterationRequest();
        request.setDocUrl("https://example.feishu.cn/docx/doc123");
        request.setInstruction("把项目背景改一下");
        request.setWorkspaceContext(context);
        return request;
    }

    private Artifact ownedArtifact() {
        return Artifact.builder()
                .artifactId("artifact-1")
                .taskId("task-1")
                .documentId("doc123")
                .externalUrl("https://example.feishu.cn/docx/doc123")
                .createdBySystem(true)
                .build();
    }

    private DocumentTargetSelector selector() {
        return DocumentTargetSelector.builder()
                .docId("doc123")
                .docUrl("https://example.feishu.cn/docx/doc123")
                .targetType(DocumentTargetType.SECTION)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .locatorValue("项目背景")
                .matchedExcerpt("旧内容")
                .matchedBlockIds(List.of("blk1"))
                .build();
    }
}
