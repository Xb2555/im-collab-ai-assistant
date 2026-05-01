package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPatchExecutorTest {

    @Test
    void verifyFailureThrowsInsteadOfPretendingSuccess() {
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.fetchDocFullMarkdown("doc123"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("旧内容").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("旧内容").build());
        when(tool.fetchDocFull("doc123", "with-ids"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("<p id=\"blk1\">旧内容</p>").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("<p id=\"blk1\">旧内容</p>").build());
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), anyLong()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).updatedBlocksCount(1).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId("blk1")
                        .newContent("新内容")
                        .docFormat("markdown")
                        .build()))
                .build();

        assertThatThrownBy(() -> executor.execute("doc123", plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void blockInsertWithoutNewBlocksStillPassesWhenContentIsPresent() {
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.fetchDocFullMarkdown("doc123"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("旧内容").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("旧内容\n\n### 新增小节\n\n管理层版本").build());
        when(tool.fetchDocFull("doc123", "with-ids"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("<doc><p id=\"heading-block\">旧内容</p></doc>").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("<doc><p id=\"heading-block\">旧内容</p><p id=\"body-1\">新增</p></doc>").build());
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), anyLong()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).updatedBlocksCount(1).newBlocks(List.of()).build());

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
        assertThat(result.getModifiedBlocks()).containsExactly("insert-after:heading-block");
    }

    @Test
    void blockReplacePrependSimulationPassesWhenInsertedPrefixAndAnchorRemain() {
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.fetchDocFullMarkdown("doc123"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("## 一、项目背景").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("## 前言\n\n新增内容\n\n## 一、项目背景").build());
        when(tool.fetchDocFull("doc123", "with-ids"))
                .thenReturn(LarkDocFetchResult.builder().revisionId(1L).content("<doc><h2 id=\"blk1\">一、项目背景</h2></doc>").build())
                .thenReturn(LarkDocFetchResult.builder().revisionId(2L).content("<doc><h2 id=\"blk1\">一、项目背景</h2><p id=\"blk2\">新增内容</p></doc>").build());
        when(tool.updateByCommand(anyString(), anyString(), any(), any(), any(), any(), anyLong()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).revisionId(2L).updatedBlocksCount(2).build());

        DocumentPatchExecutor executor = new DocumentPatchExecutor(tool);
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId("blk1")
                        .oldText("## 一、项目背景")
                        .newContent("## 前言\n\n新增内容\n\n## 一、项目背景")
                        .docFormat("markdown")
                        .build()))
                .build();

        DocumentPatchExecutor.PatchExecutionResult result = executor.execute("doc123", plan);

        assertThat(result.getAfterRevision()).isEqualTo(2L);
        assertThat(result.getModifiedBlocks()).containsExactly("blk1");
    }
}
