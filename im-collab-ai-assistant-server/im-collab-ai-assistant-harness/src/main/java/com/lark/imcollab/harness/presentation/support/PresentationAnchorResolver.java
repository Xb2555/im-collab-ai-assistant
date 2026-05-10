package com.lark.imcollab.harness.presentation.support;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class PresentationAnchorResolver {

    public PresentationSnapshot resolve(List<PresentationSnapshot> snapshots, PresentationEditOperation operation) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<PresentationSnapshot> typedCandidates = snapshots.stream()
                .filter(snapshot -> matchesTargetType(snapshot, operation == null ? null : operation.getTargetElementType()))
                .toList();
        if (typedCandidates.isEmpty()) {
            return null;
        }
        PresentationAnchorMode anchorMode = operation == null ? null : operation.getAnchorMode();
        if (anchorMode == PresentationAnchorMode.BY_QUOTED_TEXT && hasText(operation.getQuotedText())) {
            String quoted = normalizeText(operation.getQuotedText());
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> snapshot.getNormalizedText() != null && snapshot.getNormalizedText().contains(quoted))
                    .sorted(Comparator
                            .comparingInt((PresentationSnapshot snapshot) -> normalizedMatchDistance(snapshot.getNormalizedText(), quoted))
                            .thenComparingInt(snapshot -> snapshot.getParagraphIndex() == null ? Integer.MAX_VALUE : snapshot.getParagraphIndex())
                            .thenComparingInt(snapshot -> snapshot.getBoundingBox() == null ? Integer.MAX_VALUE : snapshot.getBoundingBox().getTopLeftY()))
                    .toList(), operation, "引用文本");
        }
        if (anchorMode == PresentationAnchorMode.BY_ELEMENT_ROLE && hasText(operation.getElementRole())) {
            String expectedRole = normalizeText(operation.getElementRole()).toLowerCase();
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> roleMatches(snapshot.getSemanticRole(), expectedRole))
                    .sorted(Comparator
                            .comparingInt((PresentationSnapshot snapshot) -> roleSpecificityScore(snapshot.getSemanticRole(), expectedRole))
                            .thenComparingInt(snapshot -> snapshot.getBoundingBox() == null ? Integer.MAX_VALUE : snapshot.getBoundingBox().getTopLeftY()))
                    .toList(), operation, "元素角色");
        }
        if (anchorMode == PresentationAnchorMode.BY_BLOCK_ID && hasText(operation.getTargetBlockId())) {
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> Objects.equals(snapshot.getBlockId(), operation.getTargetBlockId()))
                    .toList(), operation, "blockId");
        }
        if (operation != null && operation.getTargetParagraphIndex() != null) {
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> Objects.equals(snapshot.getParagraphIndex(), operation.getTargetParagraphIndex()))
                    .toList(), operation, "段落序号");
        }
        if (operation != null && operation.getTargetListItemIndex() != null) {
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> Objects.equals(snapshot.getListItemIndex(), operation.getTargetListItemIndex()))
                    .toList(), operation, "列表项序号");
        }
        return resolveSingleCandidate(typedCandidates, operation, "页内元素类型");
    }

    private boolean matchesTargetType(PresentationSnapshot snapshot, PresentationTargetElementType targetElementType) {
        if (targetElementType == null || snapshot == null) {
            return true;
        }
        return switch (targetElementType) {
            case TITLE -> snapshot.getElementKind() == PresentationElementKind.TITLE;
            case BODY, CAPTION -> snapshot.getElementKind() == PresentationElementKind.BODY;
            case IMAGE -> snapshot.getElementKind() == PresentationElementKind.IMAGE;
            default -> true;
        };
    }

    private PresentationSnapshot resolveSingleCandidate(
            List<PresentationSnapshot> candidates,
            PresentationEditOperation operation,
            String anchorLabel
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int expected = operation == null || operation.getExpectedMatchCount() == null ? 1 : operation.getExpectedMatchCount();
        if (candidates.size() > expected) {
            throw new IllegalArgumentException("页内锚点命中不唯一，请补充更具体的位置");
        }
        return candidates.get(0);
    }

    private int normalizedMatchDistance(String source, String quoted) {
        if (!hasText(source) || !hasText(quoted)) {
            return Integer.MAX_VALUE;
        }
        int index = source.indexOf(quoted);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private boolean roleMatches(String actualRole, String expectedRole) {
        if (!hasText(actualRole) || !hasText(expectedRole)) {
            return false;
        }
        String[] aliases = actualRole.toLowerCase().split("\\|");
        for (String alias : aliases) {
            if (expectedRole.equals(alias.trim())) {
                return true;
            }
        }
        return false;
    }

    private int roleSpecificityScore(String actualRole, String expectedRole) {
        if (!hasText(actualRole) || !hasText(expectedRole)) {
            return Integer.MAX_VALUE;
        }
        String[] aliases = actualRole.toLowerCase().split("\\|");
        for (int i = 0; i < aliases.length; i++) {
            if (expectedRole.equals(aliases[i].trim())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
