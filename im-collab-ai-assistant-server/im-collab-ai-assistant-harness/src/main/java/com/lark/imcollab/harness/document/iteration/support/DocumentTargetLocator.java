package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Deprecated
public class DocumentTargetLocator {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");

    private enum HeadingGranularity {
        TOP_LEVEL,
        SUBSECTION,
        ANY
    }

    private final DocumentAnchorIntentService anchorIntentService;
    private final LarkDocReadGateway readGateway;
    private final DocumentStructureParser structureParser;
    private final ChatModel chatModel;

    public DocumentTargetLocator(
            DocumentAnchorIntentService anchorIntentService,
            LarkDocReadGateway readGateway,
            DocumentStructureParser structureParser,
            ChatModel chatModel
    ) {
        this.anchorIntentService = anchorIntentService;
        this.readGateway = readGateway;
        this.structureParser = structureParser;
        this.chatModel = chatModel;
    }

    public DocumentTargetSelector locate(Artifact artifact, DocumentIterationIntentType intentType, String instruction) {
        String docRef = resolveDocRef(artifact);
        DocumentAnchorIntentService.AnchorDecision decision = anchorIntentService.decide(intentType, instruction);
        return switch (decision.locatorStrategy()) {
            case DOC_START -> resolveDocStart(artifact, docRef, decision.relativePosition());
            case DOC_END -> resolveDocEnd(artifact, docRef, decision.relativePosition());
            case BY_EXACT_TEXT -> resolveByExactText(artifact, docRef, decision, instruction);
            case BY_HEADING -> resolveByHeading(artifact, docRef, intentType, decision, instruction);
            case BY_KEYWORD -> resolveByKeyword(artifact, docRef, decision, instruction);
            default -> throw new AiAssistantException(BusinessCode.PARAMS_ERROR,
                    "当前不支持的定位策略: " + decision.locatorStrategy());
        };
    }

    private DocumentTargetSelector resolveDocStart(Artifact artifact, String docRef, DocumentRelativePosition relativePosition) {
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(readGateway.fetchDocOutline(docRef).getContent());
        if (!headings.isEmpty()) {
            DocumentStructureParser.HeadingBlock firstHeading = structureParser.findFirstTopLevelHeading(headings);
            String headingMarkdown = structureParser.unwrapMarkdownFragment(
                    readGateway.fetchDocRangeMarkdown(docRef, firstHeading.getBlockId(), firstHeading.getBlockId()).getContent()
            );
            return DocumentTargetSelector.builder()
                    .docId(resolveDocId(artifact))
                    .docUrl(artifact.getExternalUrl())
                    .targetType(DocumentTargetType.TITLE)
                    .locatorStrategy(DocumentLocatorStrategy.DOC_START)
                    .relativePosition(relativePosition)
                    .locatorValue(firstHeading.getText())
                    .matchedExcerpt(headingMarkdown)
                    .matchedBlockIds(List.of(firstHeading.getBlockId()))
                    .build();
        }
        LarkDocFetchResult fullXml = readGateway.fetchDocFull(docRef, "with-ids");
        List<String> blockIds = structureParser.parseBlockIds(fullXml.getContent());
        if (blockIds.isEmpty()) {
            throw new AiAssistantException(BusinessCode.NOT_FOUND_ERROR, "文档为空，无法定位文档开头");
        }
        String firstBlockId = blockIds.get(0);
        String firstBlockMarkdown = structureParser.unwrapMarkdownFragment(
                readGateway.fetchDocRangeMarkdown(docRef, firstBlockId, firstBlockId).getContent()
        );
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.BLOCK)
                .locatorStrategy(DocumentLocatorStrategy.DOC_START)
                .relativePosition(relativePosition)
                .locatorValue("DOC_START")
                .matchedExcerpt(firstBlockMarkdown)
                .matchedBlockIds(List.of(firstBlockId))
                .build();
    }

    private DocumentTargetSelector resolveDocEnd(Artifact artifact, String docRef, DocumentRelativePosition relativePosition) {
        LarkDocFetchResult fullXml = readGateway.fetchDocFull(docRef, "with-ids");
        List<String> blockIds = structureParser.parseBlockIds(fullXml.getContent());
        String lastBlockId = blockIds.isEmpty() ? "-1" : blockIds.get(blockIds.size() - 1);
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.BLOCK)
                .locatorStrategy(DocumentLocatorStrategy.DOC_END)
                .relativePosition(relativePosition)
                .locatorValue("DOC_END")
                .matchedExcerpt("")
                .matchedBlockIds(List.of(lastBlockId))
                .build();
    }

    private DocumentTargetSelector resolveByExactText(
            Artifact artifact,
            String docRef,
            DocumentAnchorIntentService.AnchorDecision decision,
            String instruction
    ) {
        String exactText = hasText(decision.locatorValue()) ? decision.locatorValue() : structureParser.extractQuotedText(instruction);
        if (!hasText(exactText)) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "未提供可用于精确定位的原文片段");
        }
        String markdown = readGateway.fetchDocFullMarkdown(docRef).getContent();
        int occurrences = structureParser.countOccurrences(markdown, exactText);
        if (occurrences == 0) {
            throw new AiAssistantException(BusinessCode.NOT_FOUND_ERROR, "未在文档中找到指定原文片段");
        }
        if (occurrences > 1) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "指定原文片段命中多处内容，请先明确章节或更精确的片段");
        }
        LarkDocFetchResult keywordFetch = readGateway.fetchDocByKeyword(docRef, exactText, "with-ids");
        List<String> blockIds = structureParser.parseBlockIds(keywordFetch.getContent());
        if (blockIds.size() != 1) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "原文片段未能唯一收敛到单个 block，请改用章节定位");
        }
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.BLOCK)
                .locatorStrategy(DocumentLocatorStrategy.BY_EXACT_TEXT)
                .relativePosition(decision.relativePosition())
                .locatorValue(exactText)
                .matchedExcerpt(exactText)
                .matchedBlockIds(blockIds)
                .build();
    }

    private DocumentTargetSelector resolveByHeading(
            Artifact artifact,
            String docRef,
            DocumentIterationIntentType intentType,
            DocumentAnchorIntentService.AnchorDecision decision,
            String instruction
    ) {
        LarkDocFetchResult outline = readGateway.fetchDocOutline(docRef);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());
        String query = hasText(decision.locatorValue()) ? decision.locatorValue() : instruction;
        List<DocumentStructureParser.HeadingBlock> matches = headings.stream()
                .filter(h -> h.getText() != null && (h.getText().contains(query) || query.contains(h.getText())))
                .toList();
        HeadingGranularity expectedGranularity = determineExpectedHeadingGranularity(instruction, headings);
        if (matches.isEmpty()) {
            DocumentStructureParser.HeadingBlock modelSelectedHeading = selectHeadingByModel(instruction, headings, expectedGranularity);
            if (modelSelectedHeading == null) {
                throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "未能定位目标章节，请明确指出标题或引用需要修改的原文");
            }
            matches = List.of(modelSelectedHeading);
        }
        if (matches.size() > 1) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR,
                    "命中多个章节：" + matches.stream().map(DocumentStructureParser.HeadingBlock::getText).toList());
        }
        DocumentStructureParser.HeadingBlock heading = matches.get(0);
        if (intentType == DocumentIterationIntentType.INSERT && decision.relativePosition() == DocumentRelativePosition.BEFORE) {
            String headingMarkdown = structureParser.unwrapMarkdownFragment(
                    readGateway.fetchDocRangeMarkdown(docRef, heading.getBlockId(), heading.getBlockId()).getContent()
            );
            return DocumentTargetSelector.builder()
                    .docId(resolveDocId(artifact))
                    .docUrl(artifact.getExternalUrl())
                    .targetType(DocumentTargetType.TITLE)
                    .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                    .relativePosition(decision.relativePosition())
                    .locatorValue(heading.getText())
                    .matchedExcerpt(headingMarkdown)
                    .matchedBlockIds(List.of(heading.getBlockId()))
                    .build();
        }
        LarkDocFetchResult sectionMarkdown = readGateway.fetchDocSectionMarkdown(docRef, heading.getBlockId());
        LarkDocFetchResult sectionXml = readGateway.fetchDocSection(docRef, heading.getBlockId(), "with-ids");
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.SECTION)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .relativePosition(decision.relativePosition())
                .locatorValue(heading.getText())
                .matchedExcerpt(structureParser.unwrapMarkdownFragment(sectionMarkdown.getContent()))
                .matchedBlockIds(structureParser.parseBlockIds(sectionXml.getContent()))
                .build();
    }

    private DocumentStructureParser.HeadingBlock selectHeadingByModel(
            String instruction,
            List<DocumentStructureParser.HeadingBlock> headings,
            HeadingGranularity expectedGranularity
    ) {
        if (headings == null || headings.isEmpty()) {
            return null;
        }
        StringBuilder outline = new StringBuilder();
        for (DocumentStructureParser.HeadingBlock heading : headings) {
            outline.append("- blockId=")
                    .append(heading.getBlockId())
                    .append(", level=h")
                    .append(heading.getLevel())
                    .append(", title=")
                    .append(heading.getText())
                    .append('\n');
        }
        String prompt = """
                你是文档标题定位助手。
                基于用户指令和当前文档目录，选出最匹配的一个标题 block。

                规则：
                1. 只能返回一行：BLOCK_ID=<目录里已有的blockId>，或者 BLOCK_ID=NOT_FOUND。
                2. 如果用户使用相对描述（如“第一小节”“第二部分”），必须严格依据当前目录结构理解，不要臆造缺失标题。
                3. “小节”优先指向较细粒度的子标题，不要把它提升解释成父章节标题。
                4. 如果用户说“第一小节”，但当前目录里第一个细粒度小节已经缺失、只剩“2.2/3.1”之类后续项，返回 BLOCK_ID=NOT_FOUND。
                5. 如果目录结构已经被破坏，导致指令存在歧义，返回 BLOCK_ID=NOT_FOUND。
                6. 不要为了给出结果而勉强选择父标题或相邻标题。
                7. 不要解释，不要返回标题文本。
                8. 用户如果要“整个大章节/整章/顶层章节”，只能选择顶层标题。
                9. 用户如果要“小节/子节”，只能选择非顶层标题。

                示例1：
                用户指令：删除第一小节的内容
                目录：
                - h2 一、背景
                - h3 2.1 目标
                - h3 2.2 非目标
                返回：BLOCK_ID=<2.1对应的blockId>

                示例2：
                用户指令：删除第一小节的内容
                目录：
                - h2 一、背景
                - h3 2.2 非目标
                - h2 二、架构
                返回：BLOCK_ID=NOT_FOUND

                示例3：
                用户指令：删除第一小节的内容
                目录：
                - h2 二、项目目标
                - h2 三、项目架构
                返回：BLOCK_ID=NOT_FOUND

                用户指令：
                %s

                期望目标粒度：
                %s

                当前文档目录：
                %s
                """.formatted(instruction, expectedGranularity.name(), outline);
        String response = chatModel.call(prompt);
        if (response == null || response.isBlank()) {
            return null;
        }
        String line = response.trim();
        if (!line.startsWith("BLOCK_ID=")) {
            return null;
        }
        String blockId = line.substring("BLOCK_ID=".length()).trim();
        if ("NOT_FOUND".equalsIgnoreCase(blockId) || blockId.isBlank()) {
            return null;
        }
        DocumentStructureParser.HeadingBlock selected = headings.stream()
                .filter(heading -> blockId.equals(heading.getBlockId()))
                .findFirst()
                .orElse(null);
        return isSelectionCompatibleWithGranularity(headings, selected, expectedGranularity) ? selected : null;
    }

    private boolean isSelectionCompatibleWithGranularity(
            List<DocumentStructureParser.HeadingBlock> headings,
            DocumentStructureParser.HeadingBlock selected,
            HeadingGranularity expectedGranularity
    ) {
        if (selected == null || headings == null || headings.isEmpty()) {
            return selected != null;
        }
        int minLevel = headings.stream().mapToInt(DocumentStructureParser.HeadingBlock::getLevel).min().orElse(Integer.MAX_VALUE);
        return switch (expectedGranularity) {
            case TOP_LEVEL -> selected.getLevel() == minLevel;
            case SUBSECTION -> selected.getLevel() > minLevel;
            case ANY -> true;
        };
    }

    private HeadingGranularity determineExpectedHeadingGranularity(
            String instruction,
            List<DocumentStructureParser.HeadingBlock> headings
    ) {
        if (instruction == null || instruction.isBlank() || headings == null || headings.isEmpty()) {
            return HeadingGranularity.ANY;
        }
        StringBuilder outline = new StringBuilder();
        for (DocumentStructureParser.HeadingBlock heading : headings) {
            outline.append("- h")
                    .append(heading.getLevel())
                    .append(' ')
                    .append(heading.getText())
                    .append('\n');
        }
        String prompt = """
                你是文档标题层级判定助手。
                请根据用户指令和当前目录，判断用户要定位的是哪种标题层级。

                只能返回一个枚举值，不要解释：
                TOP_LEVEL
                SUBSECTION
                ANY

                规则：
                1. 删除/修改“整个大章节、整章、顶层章节、一级章节”返回 TOP_LEVEL。
                2. 删除/修改“小节、子节、某个 2.1/3.2 级标题”返回 SUBSECTION。
                3. 无法仅凭指令判断时返回 ANY。

                用户指令：
                %s

                当前目录：
                %s
                """.formatted(instruction, outline);
        String response = chatModel.call(prompt);
        if (response == null || response.isBlank()) {
            return HeadingGranularity.ANY;
        }
        try {
            return HeadingGranularity.valueOf(response.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return HeadingGranularity.ANY;
        }
    }

    private DocumentTargetSelector resolveByKeyword(
            Artifact artifact,
            String docRef,
            DocumentAnchorIntentService.AnchorDecision decision,
            String instruction
    ) {
        String keyword = hasText(decision.locatorValue()) ? decision.locatorValue() : instruction;
        LarkDocFetchResult keywordFetch = readGateway.fetchDocByKeyword(docRef, keyword, "with-ids");
        List<String> blockIds = structureParser.parseBlockIds(keywordFetch.getContent());
        if (blockIds.size() != 1) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "关键词未能唯一收敛到单个 block，请改用更明确的标题或原文片段");
        }
        return DocumentTargetSelector.builder()
                .docId(resolveDocId(artifact))
                .docUrl(artifact.getExternalUrl())
                .targetType(DocumentTargetType.BLOCK)
                .locatorStrategy(DocumentLocatorStrategy.BY_KEYWORD)
                .relativePosition(decision.relativePosition())
                .locatorValue(keyword)
                .matchedExcerpt(keywordFetch.getContent())
                .matchedBlockIds(blockIds)
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
        return hasText(artifact.getDocumentId()) ? artifact.getDocumentId() : extractDocumentId(resolveDocRef(artifact));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String extractDocumentId(String docIdOrUrl) {
        if (docIdOrUrl == null || docIdOrUrl.isBlank()) {
            return docIdOrUrl;
        }
        String trimmed = docIdOrUrl.trim();
        Matcher matcher = DOC_URL_PATTERN.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : trimmed;
    }
}
