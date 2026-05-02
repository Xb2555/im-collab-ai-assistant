package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.DocumentSectionDraft;

import java.util.List;
import java.util.Map;

public record ResolvedTemplateSections(
        Map<DocumentSemanticSection, String> slotContent,
        List<DocumentSectionDraft> remainingSections
) {

    public String contentOf(DocumentSemanticSection section) {
        return slotContent.getOrDefault(section, "");
    }
}
