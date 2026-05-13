package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.ExecutionPlan;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.PendingDocumentIteration;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import com.lark.imcollab.common.model.enums.MediaAssetSourceType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import com.lark.imcollab.harness.document.iteration.support.DocumentAnchorResolver;
import com.lark.imcollab.harness.document.iteration.support.DocumentEditIntentResolver;
import com.lark.imcollab.harness.document.iteration.support.DocumentEditStrategyPlanner;
import com.lark.imcollab.harness.document.iteration.support.DocumentImageSearchQueryService;
import com.lark.imcollab.harness.document.iteration.support.DocumentIterationRuntimeSupport;
import com.lark.imcollab.harness.document.iteration.support.DocumentOwnershipGuard;
import com.lark.imcollab.harness.document.iteration.support.DocumentPatchCompiler;
import com.lark.imcollab.harness.document.iteration.support.DocumentPatchExecutor;
import com.lark.imcollab.harness.document.iteration.support.DocumentStructureSnapshotBuilder;
import com.lark.imcollab.harness.document.iteration.support.DocumentTargetStateVerifier;
import com.lark.imcollab.harness.document.iteration.support.AssetResolutionFacade;
import com.lark.imcollab.harness.document.iteration.support.RichContentExecutionEngine;
import com.lark.imcollab.harness.document.iteration.support.RichContentExecutionPlanner;
import com.lark.imcollab.harness.document.iteration.support.RichContentTargetStateVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDocumentIterationExecutionServiceTest {

    @Mock private DocumentOwnershipGuard ownershipGuard;
    @Mock private DocumentEditIntentResolver intentResolver;
    @Mock private DocumentStructureSnapshotBuilder snapshotBuilder;
    @Mock private DocumentAnchorResolver anchorResolver;
    @Mock private DocumentEditStrategyPlanner strategyPlanner;
    @Mock private DocumentPatchCompiler patchCompiler;
    @Mock private DocumentPatchExecutor patchExecutor;
    @Mock private DocumentTargetStateVerifier targetStateVerifier;
    @Mock private DocumentIterationRuntimeSupport runtimeSupport;
    @Mock private AssetResolutionFacade assetResolutionFacade;
    @Mock private RichContentExecutionPlanner richContentExecutionPlanner;
    @Mock private RichContentExecutionEngine richContentExecutionEngine;
    @Mock private RichContentTargetStateVerifier richContentTargetStateVerifier;
    @Mock private DocumentImageSearchQueryService documentImageSearchQueryService;

    private DefaultDocumentIterationExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDocumentIterationExecutionService(
                ownershipGuard, intentResolver, snapshotBuilder, anchorResolver,
                strategyPlanner, patchCompiler, patchExecutor, targetStateVerifier,
                runtimeSupport, assetResolutionFacade, richContentExecutionPlanner,
                richContentExecutionEngine, richContentTargetStateVerifier,
                documentImageSearchQueryService
        );
    }

    @Test
    void explainDoesNotTouchOwnedDocument() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.EXPLAIN, DocumentSemanticActionType.EXPLAIN_ONLY);
        DocumentStructureSnapshot snapshot = snapshot();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.EXPLAIN_ONLY, DocumentExpectedStateType.EXPECT_NO_CHANGE);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.EXPLAIN)
                .semanticAction(DocumentSemanticActionType.EXPLAIN_ONLY)
                .generatedContent("这是解释")
                .reasoningSummary("readonly")
                .riskLevel(DocumentRiskLevel.LOW)
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(plan);

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getRecognizedIntent()).isEqualTo(DocumentIterationIntentType.EXPLAIN);
        assertThat(response.getEditPlan().getSemanticAction()).isEqualTo(DocumentSemanticActionType.EXPLAIN_ONLY);
        verify(runtimeSupport, never()).touchOwnedDocument(any(), any());
        verify(runtimeSupport).complete(any(), anyString());
    }

    @Test
    void updateTouchesOwnedDocumentAfterPatchAndRunsTargetStateVerify() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.UPDATE_CONTENT, DocumentSemanticActionType.REWRITE_INLINE_TEXT);
        DocumentStructureSnapshot beforeSnapshot = snapshot();
        DocumentStructureSnapshot afterSnapshot = DocumentStructureSnapshot.builder()
                .docId("doc123")
                .revisionId(2L)
                .rawFullMarkdown("新内容")
                .build();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.TEXT_REPLACE, DocumentExpectedStateType.EXPECT_TEXT_REPLACED);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .semanticAction(DocumentSemanticActionType.REWRITE_INLINE_TEXT)
                .resolvedAnchor(anchor)
                .structureSnapshot(beforeSnapshot)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType())
                .generatedContent("新内容")
                .reasoningSummary("rewrite")
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
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(beforeSnapshot, afterSnapshot);
        when(anchorResolver.resolve(any(), eq(beforeSnapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(beforeSnapshot), eq(anchor), eq(strategy))).thenReturn(plan);
        when(patchExecutor.execute(anyString(), any())).thenReturn(new DocumentPatchExecutor.PatchExecutionResult(List.of("text-match"), 1L, 2L));

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getModifiedBlocks()).containsExactly("text-match");
        assertThat(response.getEditPlan().getStrategyType()).isEqualTo(DocumentStrategyType.TEXT_REPLACE);
        verify(targetStateVerifier).verify(eq(plan), eq(beforeSnapshot), any());
        verify(runtimeSupport).touchOwnedDocument(any(), any());
    }

    @Test
    void blockInsertAfterFetchesAnchorSectionDetailBeforeTargetStateVerify() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.UPDATE_CONTENT, DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        DocumentStructureSnapshot beforeSnapshot = snapshot();
        DocumentStructureSnapshot afterSnapshot = DocumentStructureSnapshot.builder()
                .docId("doc123")
                .revisionId(2L)
                .build();
        ResolvedDocumentAnchor anchor = ResolvedDocumentAnchor.builder()
                .anchorType(DocumentAnchorType.SECTION)
                .sectionAnchor(DocumentSectionAnchor.builder()
                        .headingBlockId("heading-2-2")
                        .headingText("2.2 验证结论")
                        .build())
                .preview("2.2 验证结论")
                .build();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.BLOCK_INSERT_AFTER, DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .semanticAction(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR)
                .resolvedAnchor(anchor)
                .structureSnapshot(beforeSnapshot)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType())
                .generatedContent("已通过 GUI 完成态文档修改链路实测验证。")
                .reasoningSummary("insert after section")
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId("body-2-2")
                        .newContent("已通过 GUI 完成态文档修改链路实测验证。")
                        .docFormat("markdown")
                        .build()))
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(beforeSnapshot, afterSnapshot);
        when(anchorResolver.resolve(any(), eq(beforeSnapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(beforeSnapshot), eq(anchor), eq(strategy))).thenReturn(plan);
        when(patchExecutor.execute(anyString(), any())).thenReturn(new DocumentPatchExecutor.PatchExecutionResult(List.of("body-2-2"), 2L, 3L));

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getRecognizedIntent()).isEqualTo(DocumentIterationIntentType.UPDATE_CONTENT);
        verify(snapshotBuilder).fetchSectionDetail(afterSnapshot, "heading-2-2", "https://example.feishu.cn/docx/doc123");
        verify(targetStateVerifier).verify(eq(plan), eq(beforeSnapshot), eq(afterSnapshot));
    }

    @Test
    void imageInsertWithoutAttachmentUsesSearchPromptFromInstruction() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.INSERT_MEDIA, DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR);
        intent.setAssetSpec(com.lark.imcollab.common.model.entity.MediaAssetSpec.builder()
                .assetType(MediaAssetType.IMAGE)
                .build());
        DocumentStructureSnapshot snapshot = snapshot();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.MEDIA_INSERT_AFTER, DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .resolvedAnchor(anchor)
                .structureSnapshot(snapshot)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType())
                .generatedContent("插入图片")
                .reasoningSummary("image insert")
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(plan);
        when(documentImageSearchQueryService.deriveQuery(anyString())).thenReturn("Great Wall of China");
        when(assetResolutionFacade.resolve(any())).thenReturn(com.lark.imcollab.common.model.entity.ResolvedAsset.builder()
                .assetType(MediaAssetType.IMAGE)
                .assetRef("https://images.pexels.com/test.jpg")
                .requiresUpload(false)
                .build());

        service.execute(DocumentIterationRequest.builder()
                .docUrl("https://example.feishu.cn/docx/doc123")
                .instruction("只传文档url和2.1后插入一张长城的图片")
                .workspaceContext(new WorkspaceContext())
                .build());

        verify(assetResolutionFacade).resolve(argThat(spec -> spec != null
                && spec.getAssetType() == MediaAssetType.IMAGE
                && spec.getSourceType() == MediaAssetSourceType.SEARCH
                && "Great Wall of China".equals(spec.getGenerationPrompt())));
    }

    @Test
    void requiresApprovalWaitsInsteadOfCompleting() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.DELETE, DocumentSemanticActionType.DELETE_WHOLE_SECTION);
        DocumentStructureSnapshot snapshot = snapshot();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.BLOCK_DELETE, DocumentExpectedStateType.EXPECT_SECTION_REMOVED);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.DELETE)
                .semanticAction(DocumentSemanticActionType.DELETE_WHOLE_SECTION)
                .generatedContent("")
                .reasoningSummary("delete whole section")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId("blk1,blk2")
                        .build()))
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(plan);

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getPlanningPhase()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.isRequireInput()).isTrue();
        verify(runtimeSupport).waitForApproval(any(), any(), any(), any(), anyString());
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

        DocumentIterationVO response = service.decide("doc-iter-1", new DocumentIterationApprovalRequest("REJECT", "不要删了"), "ou-user");

        assertThat(response.getPlanningPhase()).isEqualTo("CANCELLED");
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
    void insertImageWithoutAssetSourceFailsFast() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = DocumentEditIntent.builder()
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .userInstruction("在一、发展概况中插入一张图片")
                .assetSpec(com.lark.imcollab.common.model.entity.MediaAssetSpec.builder()
                        .assetType(com.lark.imcollab.common.model.enums.MediaAssetType.IMAGE)
                        .build())
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);

        assertThatThrownBy(() -> service.execute(request()))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("图片附件");
        verify(snapshotBuilder, never()).build(any());
    }

    @Test
    void richMediaExecutionUsesExecutionEngineInsteadOfPatchExecutor() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.INSERT_MEDIA, DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR);
        DocumentStructureSnapshot snapshot = snapshot();
        DocumentStructureSnapshot afterSnapshot = DocumentStructureSnapshot.builder()
                .docId("doc123")
                .revisionId(2L)
                .blockIndex(java.util.Map.of(
                        "img-block-1", com.lark.imcollab.common.model.entity.DocumentStructureNode.builder()
                                .blockId("img-block-1")
                                .blockType("image")
                                .build()))
                .build();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.MEDIA_INSERT_AFTER, DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .resolvedAnchor(anchor)
                .structureSnapshot(snapshot)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType())
                .generatedContent("")
                .reasoningSummary("rich media")
                .requiresApproval(true)
                .resolvedAssetSpec(com.lark.imcollab.common.model.entity.MediaAssetSpec.builder()
                        .assetType(com.lark.imcollab.common.model.enums.MediaAssetType.IMAGE)
                        .sourceRef("https://kkimgs.yisou.com/ims?kt=url")
                        .build())
                .build();
        ExecutionPlan richPlan = ExecutionPlan.builder()
                .steps(List.of())
                .requiresApproval(false)
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot, afterSnapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(plan);
        when(assetResolutionFacade.resolve(any())).thenReturn(com.lark.imcollab.common.model.entity.ResolvedAsset.builder()
                .assetType(com.lark.imcollab.common.model.enums.MediaAssetType.IMAGE)
                .assetRef("https://kkimgs.yisou.com/ims?kt=url")
                .requiresUpload(false)
                .build());
        when(richContentExecutionPlanner.plan(eq(intent), eq(anchor), eq(strategy), any())).thenReturn(richPlan);
        when(richContentExecutionEngine.execute(anyString(), any())).thenReturn(com.lark.imcollab.common.model.entity.RichContentExecutionResult.builder()
                .createdBlockIds(List.of("img-block-1"))
                .beforeRevision(1L)
                .afterRevision(2L)
                .build());

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getRecognizedIntent()).isEqualTo(DocumentIterationIntentType.INSERT_MEDIA);
        assertThat(response.getPlanningPhase()).isEqualTo("COMPLETED");
        assertThat(response.isRequireInput()).isFalse();
        assertThat(response.getEditPlan().isRequiresApproval()).isFalse();
        verify(patchExecutor, never()).execute(anyString(), any());
        verify(richContentExecutionEngine).execute(anyString(), any());
        verify(richContentTargetStateVerifier).verify(eq(plan), any(), eq(snapshot), any());
    }

    @Test
    void whiteboardInsertExecutesThroughRichContentEngine() {
        Artifact artifact = ownedArtifact();
        DocumentEditIntent intent = intent(DocumentIterationIntentType.INSERT_MEDIA, DocumentSemanticActionType.INSERT_WHITEBOARD_AFTER_ANCHOR);
        intent.setAssetSpec(com.lark.imcollab.common.model.entity.MediaAssetSpec.builder()
                .assetType(MediaAssetType.WHITEBOARD)
                .generationPrompt("画一个系统架构图")
                .build());
        DocumentStructureSnapshot snapshot = snapshot();
        DocumentStructureSnapshot afterSnapshot = DocumentStructureSnapshot.builder()
                .docId("doc123")
                .revisionId(2L)
                .build();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.WHITEBOARD_INSERT_AFTER, DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .semanticAction(DocumentSemanticActionType.INSERT_WHITEBOARD_AFTER_ANCHOR)
                .resolvedAnchor(anchor)
                .structureSnapshot(snapshot)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType())
                .generatedContent("")
                .reasoningSummary("rich media")
                .requiresApproval(false)
                .build();
        ExecutionPlan richPlan = ExecutionPlan.builder()
                .steps(List.of())
                .requiresApproval(false)
                .build();

        when(runtimeSupport.start(any())).thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(ownershipGuard.assertEditable(anyString(), anyString(), isNull())).thenReturn(artifact);
        when(intentResolver.resolve(anyString(), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot, afterSnapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(anyString(), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(plan);
        when(assetResolutionFacade.resolve(any())).thenReturn(com.lark.imcollab.common.model.entity.ResolvedAsset.builder()
                .assetType(MediaAssetType.WHITEBOARD)
                .assetRef("flowchart TD;A-->B")
                .build());
        when(richContentExecutionPlanner.plan(eq(intent), eq(anchor), eq(strategy), any())).thenReturn(richPlan);
        when(richContentExecutionEngine.execute(anyString(), any())).thenReturn(com.lark.imcollab.common.model.entity.RichContentExecutionResult.builder()
                .createdBlockIds(List.of("wb-block-1"))
                .beforeRevision(1L)
                .afterRevision(2L)
                .build());

        DocumentIterationVO response = service.execute(request());

        assertThat(response.getPlanningPhase()).isEqualTo("COMPLETED");
        assertThat(response.isRequireInput()).isFalse();
        verify(richContentExecutionEngine).execute(anyString(), any());
        verify(richContentTargetStateVerifier).verify(eq(plan), any(), eq(snapshot), any());
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
        DocumentEditIntent intent = intent(DocumentIterationIntentType.UPDATE_CONTENT, DocumentSemanticActionType.REWRITE_SECTION_BODY);
        DocumentStructureSnapshot snapshot = snapshot();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.BLOCK_REPLACE, DocumentExpectedStateType.EXPECT_TEXT_REPLACED);
        DocumentEditPlan replanned = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                .generatedContent("新方案")
                .reasoningSummary("still risky")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .build();

        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), eq("task-1"))).thenReturn(ownedArtifact());
        when(intentResolver.resolve(eq("请改成整章重写"), any())).thenReturn(intent);
        when(snapshotBuilder.build(any())).thenReturn(snapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(intent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(intent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(eq("doc-iter-1"), eq(intent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(replanned);

        DocumentIterationVO response = service.decide("doc-iter-1", new DocumentIterationApprovalRequest("MODIFY", "请改成整章重写"), "ou-user");

        assertThat(response.getPlanningPhase()).isEqualTo("WAITING_APPROVAL");
        verify(runtimeSupport).waitForApproval(any(), any(), eq(replanned), any(), eq("ou-user"));
        verify(patchExecutor, never()).execute(anyString(), any());
    }

    @Test
    void decideModifiedImageInstructionDerivesSearchPromptBeforeWaitingAgain() {
        PendingDocumentIteration pending = PendingDocumentIteration.builder()
                .taskId("doc-iter-1")
                .stepId("step-1")
                .docUrl("https://example.feishu.cn/docx/doc123")
                .artifactTaskId("task-1")
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .editPlan(DocumentEditPlan.builder().intentType(DocumentIterationIntentType.INSERT_MEDIA).build())
                .originalRequest(request())
                .build();
        DocumentEditIntent revisedIntent = intent(DocumentIterationIntentType.INSERT_MEDIA, DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR);
        revisedIntent.setAssetSpec(com.lark.imcollab.common.model.entity.MediaAssetSpec.builder()
                .assetType(MediaAssetType.IMAGE)
                .build());
        DocumentStructureSnapshot snapshot = snapshot();
        ResolvedDocumentAnchor anchor = anchor();
        DocumentEditStrategy strategy = strategy(DocumentStrategyType.MEDIA_INSERT_AFTER, DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT);
        DocumentEditPlan replanned = DocumentEditPlan.builder()
                .taskId("doc-iter-1")
                .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .generatedContent("插入图片")
                .reasoningSummary("still waiting")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .build();

        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), eq("task-1"))).thenReturn(ownedArtifact());
        when(intentResolver.resolve(eq("请在 2.1 后加一张故宫图片"), any())).thenReturn(revisedIntent);
        when(documentImageSearchQueryService.deriveQuery("请在 2.1 后加一张故宫图片")).thenReturn("Forbidden City");
        when(snapshotBuilder.build(any())).thenReturn(snapshot);
        when(anchorResolver.resolve(any(), eq(snapshot), eq(revisedIntent))).thenReturn(anchor);
        when(strategyPlanner.plan(eq(revisedIntent), eq(anchor))).thenReturn(strategy);
        when(patchCompiler.compile(eq("doc-iter-1"), eq(revisedIntent), eq(snapshot), eq(anchor), eq(strategy))).thenReturn(replanned);
        when(assetResolutionFacade.resolve(any())).thenReturn(com.lark.imcollab.common.model.entity.ResolvedAsset.builder()
                .assetType(MediaAssetType.IMAGE)
                .assetRef("https://images.pexels.com/forbidden-city.jpg")
                .build());

        DocumentIterationVO response = service.decide("doc-iter-1", new DocumentIterationApprovalRequest("MODIFY", "请在 2.1 后加一张故宫图片"), "ou-user");

        assertThat(response.getPlanningPhase()).isEqualTo("WAITING_APPROVAL");
        verify(assetResolutionFacade).resolve(argThat(spec -> spec != null
                && spec.getAssetType() == MediaAssetType.IMAGE
                && spec.getSourceType() == MediaAssetSourceType.SEARCH
                && "Forbidden City".equals(spec.getGenerationPrompt())));
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
                        .generatedContent("新内容")
                        .reasoningSummary("approved")
                        .build())
                .originalRequest(request())
                .build();
        when(runtimeSupport.findPending("doc-iter-1")).thenReturn(java.util.Optional.of(pending));
        when(ownershipGuard.assertEditable(anyString(), anyString(), eq("task-1"))).thenReturn(ownedArtifact());
        when(runtimeSupport.resumeWaiting("doc-iter-1", "step-1"))
                .thenReturn(new DocumentIterationRuntimeSupport.RuntimeContext("doc-iter-1", "step-1"));
        when(patchExecutor.execute(anyString(), any())).thenThrow(new IllegalStateException("verify failed"));

        assertThatThrownBy(() -> service.decide("doc-iter-1", new DocumentIterationApprovalRequest("APPROVE", "执行"), "ou-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify failed");

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

    private DocumentEditIntent intent(DocumentIterationIntentType intentType, DocumentSemanticActionType semanticActionType) {
        return DocumentEditIntent.builder()
                .intentType(intentType)
                .semanticAction(semanticActionType)
                .userInstruction("test")
                .build();
    }

    private DocumentStructureSnapshot snapshot() {
        return DocumentStructureSnapshot.builder()
                .docId("doc123")
                .revisionId(1L)
                .rawFullMarkdown("旧内容")
                .build();
    }

    private ResolvedDocumentAnchor anchor() {
        return ResolvedDocumentAnchor.builder()
                .anchorType(DocumentAnchorType.SECTION)
                .preview("旧内容")
                .build();
    }

    private DocumentEditStrategy strategy(DocumentStrategyType strategyType, DocumentExpectedStateType expectedStateType) {
        return DocumentEditStrategy.builder()
                .strategyType(strategyType)
                .anchorType(DocumentAnchorType.SECTION)
                .expectedState(ExpectedDocumentState.builder().stateType(expectedStateType).build())
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .build();
    }
}
