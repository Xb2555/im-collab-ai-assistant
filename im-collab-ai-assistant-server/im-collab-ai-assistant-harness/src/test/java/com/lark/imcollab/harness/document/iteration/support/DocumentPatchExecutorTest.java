package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPatchExecutorTest {

    @Test
    void failedResultThrowsIllegalState() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(false).message("权限不足").revisionId(1L).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.STR_REPLACE)
                        .oldText("旧内容")
                        .newContent("新内容")
                        .docFormat("markdown")
                        .build()))
                .build();

        assertThatThrownBy(() -> executor.execute("doc123", plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patch 执行失败");
    }

    @Test
    void blockInsertCollectsNewBlockIds() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(2L)
                        .newBlocks(List.of(LarkDocBlockRef.builder().blockId("blk-new").build()))
                        .build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId("heading-block")
                        .newContent("### 新增小节\n\n管理层版本")
                        .docFormat("markdown")
                        .build()))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getAfterRevision()).isEqualTo(2L);
        assertThat(result.getModifiedBlocks()).containsExactly("blk-new");
    }

    @Test
    void blockInsertFallsBackToInsertAfterLabelWhenNoNewBlocks() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).newBlocks(List.of()).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId("heading-block")
                        .newContent("新内容")
                        .docFormat("markdown")
                        .build()))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getModifiedBlocks()).containsExactly("insert-after:heading-block");
    }

    @Test
    void blockReplaceCollectsNewBlockIds() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(3L)
                        .newBlocks(List.of(
                                LarkDocBlockRef.builder().blockId("blk2").build(),
                                LarkDocBlockRef.builder().blockId("blk3").build()
                        ))
                        .build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId("blk1")
                        .newContent("新内容")
                        .docFormat("markdown")
                        .build()))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getAfterRevision()).isEqualTo(3L);
        assertThat(result.getModifiedBlocks()).containsExactly("blk2", "blk3");
    }

    @Test
    void blockGroupMoveUsesExplicitRuntimeGroupKey() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(2L)
                        .newBlocks(List.of(
                                LarkDocBlockRef.builder().blockId("blk-created-1").build(),
                                LarkDocBlockRef.builder().blockId("blk-created-2").build()
                        ))
                        .build())
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(3L).newBlocks(List.of()).build())
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(4L).newBlocks(List.of()).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.APPEND)
                                .runtimeGroupKey("group-new-section")
                                .newContent("第一步")
                                .docFormat("markdown")
                                .build(),
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER)
                                .runtimeGroupKey("group-new-section")
                                .targetBlockId("prev-section-tail")
                                .build()
                ))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getBeforeRevision()).isEqualTo(2L);
        assertThat(result.getAfterRevision()).isEqualTo(4L);
        assertThat(result.getModifiedBlocks()).contains("blk-created-1", "blk-created-2");
    }

    @Test
    void blockGroupMoveFailsFastWhenRuntimeGroupIsMissing() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER)
                        .runtimeGroupKey("missing-group")
                        .targetBlockId("prev-section-tail")
                        .build()))
                .build();

        assertThatThrownBy(() -> executor.execute("doc123", plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未找到可移动的新建 block group");
    }

    @Test
    void appendWithoutNewBlocksRecoversRuntimeGroupFromDocumentDiff() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(readGateway.fetchDocFull("doc123", "with-ids"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<doc><h2 id=\"heading-1\">1.1</h2><h2 id=\"heading-3\">1.3</h2></doc>")
                        .build())
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<doc><h2 id=\"heading-1\">1.1</h2><h2 id=\"heading-3\">1.3</h2><h2 id=\"heading-2\">1.2</h2><p id=\"body-2\">正文</p></doc>")
                        .build());
        when(writeGateway.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).newBlocks(List.of()).build())
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(3L).newBlocks(List.of()).build())
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(4L).newBlocks(List.of()).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(readGateway, writeGateway, new DocumentStructureParser());
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.APPEND)
                                .runtimeGroupKey("group-new-section")
                                .newContent("### 1.2 游客偏好分析\n\n正文")
                                .docFormat("markdown")
                                .build(),
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER)
                                .runtimeGroupKey("group-new-section")
                                .targetBlockId("heading-1")
                                .build()
                ))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getModifiedBlocks()).contains("append", "heading-2", "body-2");
        assertThat(result.getAfterRevision()).isEqualTo(4L);
    }
}
