package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentTargetLocator {

    private final LarkDocTool larkDocTool;
    private final DocumentStructureParser structureParser;

    public DocumentTargetLocator(LarkDocTool larkDocTool, DocumentStructureParser structureParser) {
        this.larkDocTool = larkDocTool;
        this.structureParser = structureParser;
    }

    public DocumentTargetSelector locate(Artifact artifact, String instruction) {
        String docRef = resolveDocRef(artifact);
        String exactText = structureParser.extractQuotedText(instruction);
        if (hasText(exactText)) {
            String markdown = larkDocTool.fetchDocFullMarkdown(docRef).getContent();
            if (!hasText(markdown) || !markdown.contains(exactText)) {
                throw new AiAssistantException(BusinessCode.NOT_FOUND_ERROR, "未在文档中找到指定原文片段");
            }
            return DocumentTargetSelector.builder()
                    .docId(resolveDocId(artifact))
                    .docUrl(artifact.getExternalUrl())
                    .targetType(DocumentTargetType.PARAGRAPH)
                    .locatorStrategy(DocumentLocatorStrategy.BY_EXACT_TEXT)
                    .locatorValue(exactText)
                    .matchedExcerpt(exactText)
                    .matchedBlockIds(List.of())
                    .build();
        }

        LarkDocFetchResult outline = larkDocTool.fetchDocOutline(docRef);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());
        List<DocumentStructureParser.HeadingBlock> matches = structureParser.matchHeadings(instruction, headings);
        if (matches.isEmpty()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "未能定位目标章节，请明确指出标题或引用需要修改的原文");
        }
        if (matches.size() > 1) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR,
                    "命中多个章节：" + matches.stream().map(DocumentStructureParser.HeadingBlock::getText).toList());
        }

        DocumentStructureParser.HeadingBlock heading = matches.get(0);
        LarkDocFetchResult sectionMarkdown = larkDocTool.fetchDocSectionMarkdown(docRef, heading.getBlockId());
        LarkDocFetchResult sectionXml = larkDocTool.fetchDocSection(docRef, heading.getBlockId(), "with-ids");
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.SECTION)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .locatorValue(heading.getText())
                .matchedExcerpt(sectionMarkdown.getContent())
                .matchedBlockIds(structureParser.parseBlockIds(sectionXml.getContent()))
                .build();
    }

    private String resolveDocRef(Artifact artifact) {
        if (hasText(artifact.getExternalUrl())) {
            return artifact.getExternalUrl();
        }
        if (hasText(artifact.getDocumentId())) {
            return artifact.getDocumentId();
        }
        throw new AiAssistantException(BusinessCode.NOT_FOUND_ERROR, "文档归属记录缺少 docId/docUrl");
    }

    private String resolveDocId(Artifact artifact) {
        return hasText(artifact.getDocumentId()) ? artifact.getDocumentId() : larkDocTool.extractDocumentId(resolveDocRef(artifact));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
