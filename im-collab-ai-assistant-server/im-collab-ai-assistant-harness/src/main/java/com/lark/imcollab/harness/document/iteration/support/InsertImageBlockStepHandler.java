package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class InsertImageBlockStepHandler implements ExecutionStepHandler {

    private static final Logger log = LoggerFactory.getLogger(InsertImageBlockStepHandler.class);

    private final LarkDocWriteGateway writeGateway;

    public InsertImageBlockStepHandler(LarkDocWriteGateway writeGateway) {
        this.writeGateway = writeGateway;
    }

    @Override
    public String stepType() {
        return "INSERT_IMAGE_BLOCK";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String anchorBlockId = step.getInput() == null ? null : String.valueOf(step.getInput());
        String fileToken = ctx.getString("uploadedFileToken");
        if (fileToken == null || fileToken.isBlank()) {
            fileToken = anchorBlockId;
        }
        String altText = firstNonBlank(ctx.getString("imageAltText"), ctx.getString("imageCaption"), "image");
        LarkDocUpdateResult result = writeGateway.updateByCommand(
                docRef, "block_insert_after", toMarkdownImage(altText, fileToken), "markdown", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("INSERT_IMAGE_BLOCK: insert failed");
        }
        String newBlockId = resolveImageBlockId(result.getNewBlocks());
        if ((newBlockId == null || newBlockId.isBlank())
                && result.getBoardTokens() != null && !result.getBoardTokens().isEmpty()) {
            newBlockId = result.getBoardTokens().get(0);
        }
        log.info("DOC_ITER_IMAGE_INSERT docRef={} anchorBlockId={} revisionId={} newBlocks={} boardTokens={} selectedBlockId={}",
                docRef,
                anchorBlockId,
                result.getRevisionId(),
                summarizeNewBlocks(result.getNewBlocks()),
                result.getBoardTokens(),
                newBlockId);
        if (newBlockId != null && !newBlockId.isBlank()) {
            ctx.addCreatedBlockId(newBlockId);
            ctx.put("imageBlockId", newBlockId);
        }
    }

    private List<String> summarizeNewBlocks(List<LarkDocBlockRef> newBlocks) {
        if (newBlocks == null || newBlocks.isEmpty()) {
            return List.of();
        }
        return newBlocks.stream()
                .map(block -> (block == null ? "null"
                        : firstNonBlank(block.getBlockId(), "null") + ":" + firstNonBlank(block.getBlockType(), "null")))
                .toList();
    }

    private String resolveImageBlockId(List<LarkDocBlockRef> newBlocks) {
        if (newBlocks == null || newBlocks.isEmpty()) {
            return null;
        }
        return newBlocks.stream()
                .filter(block -> hasType(block, "image"))
                .map(LarkDocBlockRef::getBlockId)
                .filter(this::hasText)
                .findFirst()
                .orElseGet(() -> newBlocks.stream()
                        .map(LarkDocBlockRef::getBlockId)
                        .filter(this::hasText)
                        .findFirst()
                        .orElse(null));
    }

    private boolean hasType(LarkDocBlockRef block, String expectedType) {
        if (block == null || block.getBlockType() == null || expectedType == null) {
            return false;
        }
        return block.getBlockType().toLowerCase(Locale.ROOT).contains(expectedType.toLowerCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toMarkdownImage(String altText, String fileToken) {
        return "![" + sanitizeAltText(altText) + "](" + fileToken + ")";
    }

    private String sanitizeAltText(String value) {
        if (value == null || value.isBlank()) {
            return "image";
        }
        return value.replace("]", "\\]");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
