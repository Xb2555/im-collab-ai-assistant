package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
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
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(false).message("权限不足").revisionId(1L).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
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
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(2L)
                        .newBlocks(List.of(LarkDocBlockRef.builder().blockId("blk-new").build()))
                        .build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
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
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).newBlocks(List.of()).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
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
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(3L)
                        .newBlocks(List.of(
                                LarkDocBlockRef.builder().blockId("blk2").build(),
                                LarkDocBlockRef.builder().blockId("blk3").build()
                        ))
                        .build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
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
    void newBlockPlaceholderResolvedFromPreviousOperation() {
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true).revisionId(2L)
                        .newBlocks(List.of(LarkDocBlockRef.builder().blockId("blk-created").build()))
                        .build())
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(3L).newBlocks(List.of()).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                                .blockId("anchor")
                                .newContent("第一步")
                                .docFormat("markdown")
                                .build(),
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                                .blockId("__new__")
                                .newContent("第二步")
                                .docFormat("markdown")
                                .build()
                ))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getBeforeRevision()).isEqualTo(2L);
        assertThat(result.getAfterRevision()).isEqualTo(3L);
    }
}
