package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentPatchExecutor {

    private final LarkDocTool larkDocTool;

    public DocumentPatchExecutor(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    public PatchExecutionResult execute(String docRef, DocumentEditPlan plan) {
        List<String> modifiedBlocks = new ArrayList<>();
        if (plan == null || plan.getPatchOperations() == null || plan.getPatchOperations().isEmpty()) {
            return new PatchExecutionResult(modifiedBlocks, -1L, -1L);
        }
        long beforeRevision = -1L;
        long afterRevision = -1L;
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            LarkDocFetchResult beforeMarkdown = larkDocTool.fetchDocFullMarkdown(docRef);
            LarkDocFetchResult beforeXml = larkDocTool.fetchDocFull(docRef, "with-ids");
            if (beforeRevision < 0) {
                beforeRevision = beforeMarkdown.getRevisionId();
            }
            LarkDocUpdateResult result = switch (operation.getOperationType()) {
                case STR_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "str_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        null,
                        operation.getOldText(),
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_INSERT_AFTER -> larkDocTool.updateByCommand(
                        docRef,
                        "block_insert_after",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_DELETE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_delete",
                        null,
                        null,
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                    );
                case APPEND -> larkDocTool.updateByCommand(
                        docRef,
                        "append",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        null,
                        null,
                        beforeMarkdown.getRevisionId()
                );
                default -> throw new IllegalStateException("Unsupported patch operation: " + operation.getOperationType());
            };
            LarkDocFetchResult afterMarkdownFetch = larkDocTool.fetchDocFullMarkdown(docRef);
            LarkDocFetchResult afterXmlFetch = larkDocTool.fetchDocFull(docRef, "with-ids");
            verifyOperation(operation, result, beforeMarkdown, beforeXml, afterMarkdownFetch, afterXmlFetch);
            afterRevision = afterMarkdownFetch.getRevisionId();
            collectModifiedBlocks(modifiedBlocks, operation, result);
        }
        return new PatchExecutionResult(modifiedBlocks, beforeRevision, afterRevision);
    }

    private void verifyOperation(
            DocumentPatchOperation operation,
            LarkDocUpdateResult result,
            LarkDocFetchResult beforeMarkdown,
            LarkDocFetchResult beforeXml,
            LarkDocFetchResult afterMarkdown,
            LarkDocFetchResult afterXml
    ) {
        if (afterMarkdown.getRevisionId() <= beforeMarkdown.getRevisionId()) {
            throw new IllegalStateException("文档 revision 未推进，无法确认 patch 已成功写入");
        }
        switch (operation.getOperationType()) {
            case STR_REPLACE -> verifyStringReplace(operation, beforeMarkdown.getContent(), afterMarkdown.getContent());
            case BLOCK_REPLACE -> verifyBlockReplace(operation, afterMarkdown.getContent(), afterXml.getContent());
            case BLOCK_INSERT_AFTER -> verifyBlockInsert(result, operation, afterMarkdown.getContent(), afterXml.getContent());
            case BLOCK_DELETE -> verifyBlockDelete(operation, beforeXml.getContent(), afterXml.getContent(), afterMarkdown.getContent());
            case APPEND -> verifyAppend(operation, afterMarkdown.getContent());
            default -> {
            }
        }
    }

    private void verifyStringReplace(DocumentPatchOperation operation, String before, String after) {
        String oldText = normalize(operation.getOldText());
        String newText = normalize(operation.getNewContent());
        if (oldText != null && !oldText.isBlank() && countOccurrences(before, oldText) != 1) {
            throw new IllegalStateException("str_replace 执行前命中数不为 1，拒绝继续");
        }
        if (oldText != null && !oldText.isBlank() && after.contains(operation.getOldText())) {
            throw new IllegalStateException("str_replace 后原文仍存在，校验失败");
        }
        if (newText != null && !newText.isBlank() && !normalize(after).contains(newText)) {
            throw new IllegalStateException("str_replace 后未找到新内容，校验失败");
        }
    }

    private void verifyBlockReplace(DocumentPatchOperation operation, String afterMarkdown, String afterXml) {
        if (!afterXml.contains("id=\"" + operation.getBlockId() + "\"")) {
            throw new IllegalStateException("block_replace 后目标 block 丢失，校验失败");
        }
        if (operation.getNewContent() == null || operation.getNewContent().isBlank()) {
            return;
        }
        String normalizedAfter = normalize(afterMarkdown);
        String normalizedNew = normalize(operation.getNewContent());
        if (normalizedAfter.contains(normalizedNew)) {
            return;
        }
        String normalizedOld = normalize(operation.getOldText());
        if (normalizedOld != null && !normalizedOld.isBlank() && normalizedNew.contains(normalizedOld)) {
            int splitIndex = normalizedNew.indexOf(normalizedOld);
            String insertedPrefix = normalizedNew.substring(0, splitIndex);
            if (!insertedPrefix.isBlank() && normalizedAfter.contains(insertedPrefix) && normalizedAfter.contains(normalizedOld)) {
                return;
            }
        }
        throw new IllegalStateException("block_replace 后未找到新内容，校验失败");
    }

    private void verifyBlockInsert(LarkDocUpdateResult result, DocumentPatchOperation operation, String afterMarkdown, String afterXml) {
        if (operation.getNewContent() != null && !operation.getNewContent().isBlank()
                && !normalize(afterMarkdown).contains(normalize(operation.getNewContent()))) {
            throw new IllegalStateException("block_insert_after 后未找到插入内容，校验失败");
        }
        if (result.getNewBlocks() == null || result.getNewBlocks().isEmpty()) {
            return;
        }
        boolean allPresent = result.getNewBlocks().stream().map(LarkDocBlockRef::getBlockId)
                .allMatch(blockId -> blockId != null && afterXml.contains("id=\"" + blockId + "\""));
        if (!allPresent) {
            throw new IllegalStateException("block_insert_after 新增 block 未全部落盘，校验失败");
        }
    }

    private void verifyBlockDelete(DocumentPatchOperation operation, String beforeXml, String afterXml, String afterMarkdown) {
        String[] ids = operation.getBlockId().split(",");
        for (String rawId : ids) {
            String blockId = rawId.trim();
            if (blockId.isBlank()) {
                continue;
            }
            if (!beforeXml.contains("id=\"" + blockId + "\"")) {
                throw new IllegalStateException("block_delete 执行前未找到目标 block=" + blockId);
            }
            if (afterXml.contains("id=\"" + blockId + "\"")) {
                throw new IllegalStateException("block_delete 后目标 block 仍存在，校验失败");
            }
        }
        if (operation.getOldText() != null && !operation.getOldText().isBlank()
                && normalize(afterMarkdown).contains(normalize(operation.getOldText()))) {
            throw new IllegalStateException("block_delete 后旧内容仍存在，校验失败");
        }
    }

    private void verifyAppend(DocumentPatchOperation operation, String afterMarkdown) {
        if (operation.getNewContent() != null && !operation.getNewContent().isBlank()
                && !normalize(afterMarkdown).contains(normalize(operation.getNewContent()))) {
            throw new IllegalStateException("append 后未找到新增内容，校验失败");
        }
    }

    private void collectModifiedBlocks(
            List<String> modifiedBlocks,
            DocumentPatchOperation operation,
            LarkDocUpdateResult result
    ) {
        if ((operation.getOperationType() == DocumentPatchOperationType.BLOCK_INSERT_AFTER
                || operation.getOperationType() == DocumentPatchOperationType.APPEND)
                && result.getNewBlocks() != null
                && !result.getNewBlocks().isEmpty()) {
            for (LarkDocBlockRef block : result.getNewBlocks()) {
                if (block.getBlockId() != null && !block.getBlockId().isBlank()) {
                    modifiedBlocks.add(block.getBlockId());
                }
            }
            return;
        }
        if (operation.getOperationType() == DocumentPatchOperationType.BLOCK_INSERT_AFTER) {
            modifiedBlocks.add("insert-after:" + operation.getBlockId());
        } else if (operation.getOperationType() == DocumentPatchOperationType.APPEND) {
            modifiedBlocks.add("append");
        } else if (operation.getBlockId() != null && !operation.getBlockId().isBlank()) {
            modifiedBlocks.add(operation.getBlockId());
        } else if (operation.getOldText() != null && !operation.getOldText().isBlank()) {
            modifiedBlocks.add("text-match");
        }
    }

    private int countOccurrences(String text, String pattern) {
        if (text == null || pattern == null || pattern.isBlank()) {
            return 0;
        }
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int index = text.indexOf(pattern, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + pattern.length();
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").trim();
    }

    @Getter
    @AllArgsConstructor
    public static class PatchExecutionResult {
        private final List<String> modifiedBlocks;
        private final long beforeRevision;
        private final long afterRevision;
    }
}
