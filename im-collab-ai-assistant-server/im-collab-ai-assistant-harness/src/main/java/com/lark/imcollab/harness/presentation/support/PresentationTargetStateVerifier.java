package com.lark.imcollab.harness.presentation.support;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PresentationTargetStateVerifier {

    public void verify(
            PresentationSnapshot targetSnapshot,
            PresentationEditOperation operation,
            List<PresentationSnapshot> afterSnapshots
    ) {
        if (operation == null) {
            return;
        }
        List<String> expectedTexts = expectedTexts(operation);
        if (expectedTexts.isEmpty()) {
            return;
        }
        if (targetSnapshot == null) {
            throw new IllegalStateException("PPT update verification failed: target snapshot missing");
        }
        if (operation.getActionType() == PresentationEditActionType.DELETE_ELEMENT) {
            boolean stillExists = afterSnapshots != null && afterSnapshots.stream().anyMatch(snapshot -> sameTarget(targetSnapshot, snapshot));
            if (stillExists) {
                throw new IllegalStateException("PPT update verification failed: target node still exists after delete");
            }
            return;
        }
        PresentationSnapshot matched = afterSnapshots == null ? null : afterSnapshots.stream()
                .filter(snapshot -> sameTarget(targetSnapshot, snapshot))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            throw new IllegalStateException("PPT update verification failed: target node not found after edit");
        }
        String actual = matched.getTextContent() == null ? "" : matched.getTextContent();
        for (String expected : expectedTexts) {
            if (expected != null && !expected.isBlank() && !actual.contains(expected)) {
                throw new IllegalStateException("PPT update verification failed: target text not applied to resolved node");
            }
        }
    }

    private boolean sameTarget(PresentationSnapshot before, PresentationSnapshot after) {
        if (before == null || after == null) {
            return false;
        }
        if (before.getNodePath() != null && before.getNodePath().equals(after.getNodePath())) {
            return true;
        }
        if (before.getBlockId() != null && before.getBlockId().equals(after.getBlockId())) {
            if (before.getParagraphIndex() == null || before.getParagraphIndex().equals(after.getParagraphIndex())) {
                if (before.getListItemIndex() == null || before.getListItemIndex().equals(after.getListItemIndex())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> expectedTexts(PresentationEditOperation operation) {
        if (operation.getReplacementText() != null && !operation.getReplacementText().isBlank()) {
            return List.of(operation.getReplacementText().trim());
        }
        if (operation.getSlideTitle() != null && !operation.getSlideTitle().isBlank()) {
            return List.of(operation.getSlideTitle().trim());
        }
        if (operation.getSlideBody() != null && !operation.getSlideBody().isBlank()) {
            return List.of(operation.getSlideBody().trim());
        }
        return List.of();
    }
}
