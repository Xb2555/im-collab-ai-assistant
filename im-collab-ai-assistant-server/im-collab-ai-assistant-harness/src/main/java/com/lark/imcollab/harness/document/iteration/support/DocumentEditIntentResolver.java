package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.model.entity.DocumentAnchorSpec;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentRewriteSpec;
import com.lark.imcollab.common.model.entity.MediaAssetSpec;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.MediaAssetSourceType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocumentEditIntentResolver implements DocumentEditIntentFacade {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditIntentResolver.class);

    private static final Pattern SECTION_ORDINAL_PATTERN = Pattern.compile("(?:^|\\s)(?:\\d+(?:\\.\\d+)*|第[一二三四五六七八九十百千万0-9]+[章节部分](?:第[一二三四五六七八九十百千万0-9]+[章节部分])*)");
    private static final Pattern SECTION_CONTENT_REFERENCE_PATTERN = Pattern.compile("(章节|小节|部分|段落|标题|内容|正文|数据|表述)");
    private static final Pattern SECTION_ACTION_MARKER_PATTERN = Pattern.compile("(给出|补充|增加|新增|插入|改写|修改|更新|替换|删除|移到|移动|调整|优化|展开|说明|完善|重写)");
    private static final Pattern INSERT_AFTER_PATTERN = Pattern.compile("(后插入|后新增|后面插入|之后插入)");
    private static final Pattern INSERT_BEFORE_PATTERN = Pattern.compile("(前插入|前新增|前面插入|之前插入)");
    private static final Pattern INSERTED_SECTION_CANDIDATE_PATTERN = Pattern.compile("(插入|新增)\\s*(?:一章|一节|一部分|第[一二三四五六七八九十百千万0-9]+[章节部分]|\\d+(?:\\.\\d+)+)");
    private static final Pattern APPEND_SECTION_TO_END_PATTERN = Pattern.compile("(再加|再补|新增|追加|补充|最后补|最后加|末尾补|末尾加).*(一节|一小节|一个小节|一个章节|小节|章节)");
    private static final Pattern DECIMAL_SECTION_HEADING_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)\\s*([^，。；,;\\n]+)");
    private static final Pattern DECIMAL_SECTION_PATH_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern CHAPTER_THEN_SECTION_PATTERN = Pattern.compile("第([一二三四五六七八九十百千万0-9]+)章第([一二三四五六七八九十百千万0-9]+)节");
    private static final Pattern CHINESE_SECTION_ORDINAL_PATTERN = Pattern.compile("第([一二三四五六七八九十百千万0-9]+)(章|节|部分)");
    private static final Pattern CHINESE_SECTION_PATH_PATTERN = Pattern.compile("(第[一二三四五六七八九十百千万0-9]+[章节部分](?:第[一二三四五六七八九十百千万0-9]+[章节部分])*)");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DocumentEditIntentResolver(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public DocumentEditIntent resolve(String instruction, WorkspaceContext workspaceContext) {
        try {
            String response = chatModel.call(buildPrompt(instruction, workspaceContext));
            Map<String, Object> payload = objectMapper.readValue(stripCodeFences(response), new TypeReference<>() {});
            DocumentEditIntent parsedIntent = fromPayload(instruction, payload);
            DocumentEditIntent validatedIntent = validate(parsedIntent);
            DocumentEditIntent enrichedIntent = enrichWithWorkspaceContext(validatedIntent, workspaceContext);
            log.info("DOC_ITER_INTENT resolved instruction='{}' rawIntentType={} rawAction={} finalIntentType={} finalAction={} anchorKind={} matchMode={} headingTitle='{}' headingNumber='{}' outlinePath='{}' ordinal={} ordinalScope={} clarificationNeeded={} clarificationHint='{}'",
                    instruction,
                    parsedIntent == null ? null : parsedIntent.getIntentType(),
                    parsedIntent == null ? null : parsedIntent.getSemanticAction(),
                    enrichedIntent == null ? null : enrichedIntent.getIntentType(),
                    enrichedIntent == null ? null : enrichedIntent.getSemanticAction(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getAnchorKind(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getMatchMode(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getHeadingTitle(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getHeadingNumber(),
                    effectiveOutlinePath(enrichedIntent == null ? null : enrichedIntent.getAnchorSpec()),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getStructuralOrdinal(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getStructuralOrdinalScope(),
                    enrichedIntent != null && enrichedIntent.isClarificationNeeded(),
                    enrichedIntent == null ? null : enrichedIntent.getClarificationHint());
            return enrichedIntent;
        } catch (Exception e) {
            log.warn("DOC_ITER_INTENT failed instruction='{}' error='{}'", instruction, e.getMessage(), e);
            return enrichWithWorkspaceContext(DocumentEditIntent.builder()
                    .userInstruction(instruction)
                    .clarificationNeeded(true)
                    .clarificationHint("意图解析失败，请重新描述：" + e.getMessage())
                    .build(), workspaceContext);
        }
    }

    @Override
    public DocumentEditIntent resolve(String instruction) {
        return resolve(instruction, null);
    }

    private DocumentEditIntent fromPayload(String instruction, Map<String, Object> p) {
        DocumentIterationIntentType intentType = parseEnum(DocumentIterationIntentType.class, str(p.get("intentType")));
        DocumentSemanticActionType semanticAction = parseEnum(DocumentSemanticActionType.class, str(p.get("semanticAction")));
        boolean clarificationNeeded = Boolean.TRUE.equals(p.get("clarificationNeeded"));
        String clarificationHint = str(p.get("clarificationHint"));
        DocumentRiskLevel riskLevel = parseEnum(DocumentRiskLevel.class, str(p.get("riskLevel")));

        DocumentAnchorSpec anchorSpec = null;
        if (p.get("anchorSpec") instanceof Map<?, ?> anchorMap) {
            anchorSpec = DocumentAnchorSpec.builder()
                    .anchorKind(parseEnum(DocumentAnchorKind.class, str(anchorMap.get("anchorKind"))))
                    .matchMode(parseEnum(DocumentAnchorMatchMode.class, str(anchorMap.get("matchMode"))))
                    .blockId(str(anchorMap.get("blockId")))
                    .headingTitle(str(anchorMap.get("headingTitle")))
                    .headingNumber(str(anchorMap.get("headingNumber")))
                    .outlinePath(str(anchorMap.get("outlinePath")))
                    .outlinePathText(str(anchorMap.get("outlinePathText")))
                    .outlinePathNumbers(str(anchorMap.get("outlinePathNumbers")))
                    .parentHeadingTitle(str(anchorMap.get("parentHeadingTitle")))
                    .parentHeadingNumber(str(anchorMap.get("parentHeadingNumber")))
                    .structuralOrdinal(toInt(anchorMap.get("structuralOrdinal")))
                    .structuralOrdinalScope(str(anchorMap.get("structuralOrdinalScope")))
                    .quotedText(str(anchorMap.get("quotedText")))
                    .mediaCaption(str(anchorMap.get("mediaCaption")))
                    .relativePosition(str(anchorMap.get("relativePosition")))
                    .scopeHint(str(anchorMap.get("scopeHint")))
                    .build();
        }

        DocumentRewriteSpec rewriteSpec = null;
        if (p.get("rewriteSpec") instanceof Map<?, ?> rwMap) {
            rewriteSpec = DocumentRewriteSpec.builder()
                    .targetContent(str(rwMap.get("targetContent")))
                    .styleOnly(Boolean.TRUE.equals(rwMap.get("styleOnly")))
                    .newContent(str(rwMap.get("newContent")))
                    .build();
        }

        MediaAssetSpec assetSpec = null;
        if (p.get("assetSpec") instanceof Map<?, ?> assetMap) {
            MediaAssetType assetType = parseEnum(MediaAssetType.class, str(assetMap.get("assetType")));
            if (assetType != null) {
                assetSpec = MediaAssetSpec.builder()
                        .assetType(assetType)
                        .sourceType(parseEnum(MediaAssetSourceType.class, str(assetMap.get("sourceType"))))
                        .sourceRef(str(assetMap.get("sourceRef")))
                        .caption(str(assetMap.get("caption")))
                        .altText(str(assetMap.get("altText")))
                        .generationPrompt(str(assetMap.get("generationPrompt")))
                        .build();
            }
        }

        @SuppressWarnings("unchecked")
        List<String> riskHints = p.get("riskHints") instanceof List<?> list
                ? (List<String>) list : List.of();

        if (intentType == null || semanticAction == null) {
            clarificationNeeded = true;
            if (clarificationHint == null || clarificationHint.isBlank()) {
                clarificationHint = "无法识别操作类型，请更明确地描述要对文档做什么";
            }
        }

        return DocumentEditIntent.builder()
                .intentType(intentType)
                .semanticAction(semanticAction)
                .userInstruction(instruction)
                .anchorSpec(anchorSpec)
                .rewriteSpec(rewriteSpec)
                .assetSpec(assetSpec)
                .clarificationNeeded(clarificationNeeded)
                .clarificationHint(clarificationHint)
                .riskLevel(riskLevel)
                .riskHints(riskHints)
                .build();
    }

    private DocumentEditIntent validate(DocumentEditIntent intent) {
        if (intent == null) {
            log.info("DOC_ITER_INTENT validation_null_intent");
            return clarificationIntent(null, "意图解析结果为空，请重新描述要对文档执行的操作");
        }
        intent = normalizeAppendSectionToDocumentEnd(intent);
        intent = normalizeSectionInsertIntent(intent);
        intent = normalizeSectionInlineInsertIntent(intent);
        intent = normalizeSectionAnchorIntent(intent);
        intent = normalizeSectionIntent(intent);
        if (intent.isClarificationNeeded()) {
            log.info("DOC_ITER_INTENT validation_clarification instruction='{}' hint='{}'",
                    intent.getUserInstruction(), intent.getClarificationHint());
            return ensureClarificationHint(intent, "当前指令缺少足够信息，请明确操作类型、目标位置和目标内容");
        }
        if (intent.getIntentType() == null || intent.getSemanticAction() == null) {
            log.info("DOC_ITER_INTENT validation_missing_action instruction='{}' intentType={} action={}",
                    intent.getUserInstruction(), intent.getIntentType(), intent.getSemanticAction());
            return clarificationIntent(intent.getUserInstruction(), "无法识别操作类型，请明确要插入、改写、删除还是解释文档内容");
        }
        if (requiresAnchor(intent) && intent.getAnchorSpec() == null) {
            log.info("DOC_ITER_INTENT validation_missing_anchor instruction='{}' action={}",
                    intent.getUserInstruction(), intent.getSemanticAction());
            return clarificationIntent(intent.getUserInstruction(), "缺少明确锚点，请说明章节标题、结构序号或引用文本");
        }
        if (intent.getAnchorSpec() != null && !isAnchorSpecValid(intent.getAnchorSpec())) {
            log.info("DOC_ITER_INTENT validation_invalid_anchor instruction='{}' anchorKind={} matchMode={}",
                    intent.getUserInstruction(), intent.getAnchorSpec().getAnchorKind(), intent.getAnchorSpec().getMatchMode());
            return clarificationIntent(intent.getUserInstruction(), "锚点描述不完整，请提供明确的章节标题、结构序号、引用文本或媒体说明");
        }
        String semanticValidationError = validateAnchorSemantics(intent);
        if (semanticValidationError != null) {
            log.info("DOC_ITER_INTENT validation_semantic_reject instruction='{}' action={} anchorKind={} matchMode={} reason='{}'",
                    intent.getUserInstruction(),
                    intent.getSemanticAction(),
                    intent.getAnchorSpec() == null ? null : intent.getAnchorSpec().getAnchorKind(),
                    intent.getAnchorSpec() == null ? null : intent.getAnchorSpec().getMatchMode(),
                    semanticValidationError);
            return clarificationIntent(intent.getUserInstruction(), semanticValidationError);
        }
        return intent;
    }

    private DocumentEditIntent enrichWithWorkspaceContext(DocumentEditIntent intent, WorkspaceContext workspaceContext) {
        if (intent == null) {
            return null;
        }
        if (workspaceContext == null || workspaceContext.getAttachmentRefs() == null || workspaceContext.getAttachmentRefs().isEmpty()) {
            return intent;
        }
        MediaAssetSpec currentSpec = intent.getAssetSpec();
        if (currentSpec == null) {
            if (intent.getSemanticAction() != DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR) {
                return intent;
            }
            currentSpec = MediaAssetSpec.builder()
                    .assetType(MediaAssetType.IMAGE)
                    .build();
        }
        if (currentSpec.getAssetType() != MediaAssetType.IMAGE) {
            return intent;
        }
        if (hasText(currentSpec.getSourceRef())) {
            return intent;
        }
        String firstAttachment = workspaceContext.getAttachmentRefs().stream()
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
        if (!hasText(firstAttachment)) {
            return intent;
        }
        currentSpec.setSourceRef(firstAttachment);
        if (currentSpec.getSourceType() == null) {
            currentSpec.setSourceType(MediaAssetSourceType.ATTACHMENT);
        }
        intent.setAssetSpec(currentSpec);
        return intent;
    }

    private String buildPrompt(String instruction, WorkspaceContext workspaceContext) {
        String intentTypes = Arrays.stream(DocumentIterationIntentType.values()).map(Enum::name).collect(Collectors.joining("|"));
        String semanticActions = Arrays.stream(DocumentSemanticActionType.values()).map(Enum::name).collect(Collectors.joining("|"));
        String anchorKinds = Arrays.stream(DocumentAnchorKind.values()).map(Enum::name).collect(Collectors.joining("|"));
        String matchModes = Arrays.stream(DocumentAnchorMatchMode.values()).map(Enum::name).collect(Collectors.joining("|"));
        String assetTypes = Arrays.stream(MediaAssetType.values()).map(Enum::name).collect(Collectors.joining("|"));
        String sourceTypes = Arrays.stream(MediaAssetSourceType.values()).map(Enum::name).collect(Collectors.joining("|"));
        String riskLevels = Arrays.stream(DocumentRiskLevel.values()).map(Enum::name).collect(Collectors.joining("|"));

        String prompt = """
                你是文档编辑意图解析器。根据用户指令，一次性输出完整的结构化 JSON，不要解释，只输出合法 JSON。

                输出 schema（所有字段均可为 null）：
                {
                  "intentType": "%s",
                  "semanticAction": "%s",
                  "clarificationNeeded": false,
                  "clarificationHint": "无法确定时填写原因",
                  "riskLevel": "%s",
                  "riskHints": ["..."],
                  "anchorSpec": {
                    "anchorKind": "%s",
                    "matchMode": "%s",
                    "headingTitle": "标题文本，BY_HEADING_TITLE 时填",
                    "headingNumber": "如 '1.3'、'1.3.2'，BY_HEADING_NUMBER 时填",
                    "outlinePath": "兼容旧字段",
                    "outlinePathText": "如 '第3章/第2节'，BY_OUTLINE_PATH_TEXT 时填",
                    "outlinePathNumbers": "如 '1/3/2' 或 '1.3.2'，BY_OUTLINE_PATH_NUMBERS 时填",
                    "parentHeadingTitle": "父章节标题，父作用域定位时填",
                    "parentHeadingNumber": "父章节编号，如 '3' 或 '1.3'",
                    "structuralOrdinal": 1,
                    "structuralOrdinalScope": "TOP_LEVEL_SECTION|CHILD_OF_HEADING_NUMBER:3|CHILD_OF_HEADING_NUMBER:1.3|CHILD_OF_HEADING_TITLE:项目背景",
                    "quotedText": "用户引号内的文本，BY_QUOTED_TEXT 时填",
                    "mediaCaption": "媒体说明文字，BY_MEDIA_CAPTION 时填",
                    "blockId": "仅在上游已明确给出平台 blockId 时填写",
                    "relativePosition": "BEFORE|AFTER|INTO|WHOLE",
                    "scopeHint": "TOP_LEVEL_SECTION|CHILD_SECTION|BODY_BLOCK|HEAD_METADATA"
                  },
                  "rewriteSpec": {
                    "targetContent": "要改写的原文内容",
                    "styleOnly": false,
                    "newContent": "用户明确给出的新内容，否则为空"
                  },
                  "assetSpec": {
                    "assetType": "%s",
                    "sourceType": "%s",
                    "sourceRef": "",
                    "caption": "",
                    "altText": "",
                    "generationPrompt": ""
                  }
                }

                规则：
                1. intentType 和 semanticAction 必须从枚举值中选择，无法确定时设 clarificationNeeded=true。
                2. anchorSpec.matchMode 必须是结构化 slot。提到“1.3”“1.3.2”时优先使用 BY_HEADING_NUMBER；提到“第3章第2节”时优先使用 parentHeadingNumber=3 + structuralOrdinal=2 + structuralOrdinalScope=CHILD_OF_HEADING_NUMBER:3；提到层级路径时优先输出 BY_OUTLINE_PATH_TEXT 或 BY_OUTLINE_PATH_NUMBERS。
                3. 无法唯一确定锚点时，设 clarificationNeeded=true，不要猜。
                4. 非媒体操作时 assetSpec 为 null。
                5. 只要用户明确提到章节号、节号、标题序号或章节标题，例如“1.3 客源市场结构”“第三章”“第二节”，优先使用 SECTION 锚点；不要降级成 BLOCK/TEXT/BY_QUOTED_TEXT。
                6. 当用户表达“某章节中的数据/内容/正文/表述需要改写、补充、更新”时，优先判定为 REWRITE_SECTION_BODY；只有用户明确指向某个具体段落或单个 block 时，才使用 REWRITE_SINGLE_BLOCK。
                7. BY_QUOTED_TEXT 只允许用于文档中可直接定位的字面文本：
                   - 用户使用引号点名原文；
                   - 或用户明确给出一段稳定、可直接查找的原句。
                   不允许把“总体复苏态势中的数据”“客源市场结构那部分内容”这类抽象概括写入 quotedText。
                8. 如果用户已经给出章节号/章节标题，同时又描述该章节里的内容问题，应保留章节锚点，不要凭空构造 quotedText 锚点。
                9. 如果既不能稳定识别章节，也没有真实可引用原文，返回 clarificationNeeded=true，不要编造 anchorSpec。
                10. 如果用户明确要求“在文档里加/补/新增一个小节或章节”，即使没有给锚点，也应优先识别为 APPEND_SECTION_TO_DOCUMENT_END，而不是要求再次澄清。
                11. 如果用户说“关于前10分钟的消息总结”“基于最近聊天记录补一节总结”之类，历史消息只是写作素材来源，不等于锚点缺失；只要新增位置默认是文末追加，就不要因为没有章节锚点而澄清。

                已有上下文素材：
                %s

                用户指令：%s
                """.formatted(intentTypes, semanticActions, riskLevels, anchorKinds, matchModes, assetTypes, sourceTypes,
                summarizeWorkspaceContext(workspaceContext), instruction);
        return prompt;
    }

    private String summarizeWorkspaceContext(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return "无";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (hasText(workspaceContext.getTimeRange())) {
            parts.add("timeRange=" + workspaceContext.getTimeRange());
        }
        if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
            parts.add("selectedMessages=" + workspaceContext.getSelectedMessages().stream()
                    .filter(this::hasText)
                    .limit(6)
                    .collect(Collectors.joining(" | ")));
        }
        if (workspaceContext.getDocRefs() != null && !workspaceContext.getDocRefs().isEmpty()) {
            parts.add("docRefs=" + String.join(",", workspaceContext.getDocRefs()));
        }
        return parts.isEmpty() ? "无" : String.join("\n", parts);
    }

    private String stripCodeFences(String response) {
        if (response == null) {
            return null;
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd >= 0) {
                trimmed = trimmed.substring(firstLineEnd + 1).trim();
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private boolean requiresAnchor(DocumentEditIntent intent) {
        if (intent == null || intent.getSemanticAction() == null) {
            return false;
        }
        return switch (intent.getSemanticAction()) {
            case EXPLAIN_ONLY, APPEND_SECTION_TO_DOCUMENT_END -> false;
            default -> true;
        };
    }

    private boolean isAnchorSpecValid(DocumentAnchorSpec anchorSpec) {
        if (anchorSpec == null || anchorSpec.getMatchMode() == null) {
            return false;
        }
        return switch (anchorSpec.getMatchMode()) {
            case DOC_START, DOC_END -> anchorSpec.getAnchorKind() != null;
            case BY_HEADING_TITLE -> hasText(anchorSpec.getHeadingTitle());
            case BY_HEADING_NUMBER -> hasText(anchorSpec.getHeadingNumber());
            case BY_OUTLINE_PATH, BY_OUTLINE_PATH_TEXT -> hasText(effectiveOutlinePath(anchorSpec));
            case BY_OUTLINE_PATH_NUMBERS -> hasText(anchorSpec.getOutlinePathNumbers()) || hasText(anchorSpec.getHeadingNumber());
            case BY_STRUCTURAL_ORDINAL -> anchorSpec.getStructuralOrdinal() != null && hasText(anchorSpec.getStructuralOrdinalScope());
            case BY_QUOTED_TEXT -> hasText(anchorSpec.getQuotedText());
            case BY_BLOCK_ID -> hasText(anchorSpec.getBlockId());
            case BY_MEDIA_CAPTION -> hasText(anchorSpec.getMediaCaption());
        };
    }

    private String validateAnchorSemantics(DocumentEditIntent intent) {
        DocumentAnchorSpec anchorSpec = intent.getAnchorSpec();
        if (anchorSpec == null || anchorSpec.getMatchMode() == null) {
            return null;
        }
        if (expectsSectionAnchor(intent.getSemanticAction())
                && anchorSpec.getAnchorKind() != DocumentAnchorKind.SECTION
                && anchorSpec.getMatchMode() != DocumentAnchorMatchMode.BY_BLOCK_ID) {
            return "当前操作面向章节，请提供明确的章节标题或章节序号";
        }
        if (anchorSpec.getMatchMode() != DocumentAnchorMatchMode.BY_QUOTED_TEXT) {
            return null;
        }
        String instruction = str(intent.getUserInstruction());
        String quotedText = str(anchorSpec.getQuotedText());
        if (!hasText(quotedText)) {
            return "引用文本为空，请提供可直接定位的原文片段";
        }
        if (mentionsSectionReference(instruction) && !isLiteralQuotedReference(instruction, quotedText)) {
            return "当前指令更像章节定位，请提供明确的章节标题或章节序号，不要使用概括性引用文本";
        }
        return null;
    }

    private DocumentEditIntent normalizeSectionIntent(DocumentEditIntent intent) {
        if (intent == null || !hasText(intent.getUserInstruction())) {
            return intent;
        }
        SectionReference sectionReference = extractSectionReference(intent.getUserInstruction());
        if (sectionReference == null || !looksLikeSectionBodyRewrite(intent)) {
            return intent;
        }
        log.info("DOC_ITER_INTENT normalize_section instruction='{}' fromAction={} toAction={} headingTitle='{}' headingNumber='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                DocumentSemanticActionType.REWRITE_SECTION_BODY,
                sectionReference.headingTitle(),
                sectionReference.headingNumber(),
                sectionReference.effectiveOutlinePath(),
                sectionReference.structuralOrdinal(),
                sectionReference.structuralOrdinalScope());
        intent.setIntentType(DocumentIterationIntentType.UPDATE_CONTENT);
        intent.setSemanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        intent.setAnchorSpec(buildSectionAnchor(sectionReference));
        return intent;
    }

    private DocumentEditIntent normalizeSectionInsertIntent(DocumentEditIntent intent) {
        if (intent == null || !hasText(intent.getUserInstruction()) || intent.getIntentType() != DocumentIterationIntentType.INSERT) {
            return intent;
        }
        if (!looksLikeSectionInsert(intent) || !isExplicitSectionInsertInstruction(intent.getUserInstruction())) {
            return intent;
        }
        SectionReference sectionReference = extractInsertAnchorSectionReference(intent.getUserInstruction());
        if (sectionReference == null) {
            return intent;
        }
        DocumentSemanticActionType normalizedAction = normalizeInsertSemanticAction(intent.getUserInstruction(), intent.getSemanticAction());
        DocumentAnchorSpec normalizedAnchor = buildSectionAnchor(sectionReference);
        if (!hasText(normalizedAnchor.getRelativePosition())) {
            normalizedAnchor.setRelativePosition(detectRelativePosition(intent.getUserInstruction()));
        }
        log.info("DOC_ITER_INTENT normalize_insert instruction='{}' fromAction={} toAction={} headingTitle='{}' headingNumber='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                normalizedAction,
                sectionReference.headingTitle(),
                sectionReference.headingNumber(),
                sectionReference.effectiveOutlinePath(),
                sectionReference.structuralOrdinal(),
                sectionReference.structuralOrdinalScope());
        intent.setIntentType(DocumentIterationIntentType.INSERT);
        intent.setSemanticAction(normalizedAction);
        intent.setAnchorSpec(normalizedAnchor);
        return intent;
    }

    private DocumentEditIntent normalizeAppendSectionToDocumentEnd(DocumentEditIntent intent) {
        if (intent == null
                || !hasText(intent.getUserInstruction())
                || intent.getIntentType() != DocumentIterationIntentType.INSERT) {
            return intent;
        }
        String instruction = intent.getUserInstruction();
        if (!APPEND_SECTION_TO_END_PATTERN.matcher(instruction).find()) {
            return intent;
        }
        if (INSERT_AFTER_PATTERN.matcher(instruction).find() || INSERT_BEFORE_PATTERN.matcher(instruction).find()) {
            return intent;
        }
        if (mentionsSectionReference(instruction)) {
            return intent;
        }
        log.info("DOC_ITER_INTENT normalize_append_section_to_end instruction='{}' fromAction={} toAction={}",
                instruction,
                intent.getSemanticAction(),
                DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END);
        intent.setIntentType(DocumentIterationIntentType.INSERT);
        intent.setSemanticAction(DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END);
        intent.setAnchorSpec(null);
        intent.setClarificationNeeded(false);
        intent.setClarificationHint(null);
        return intent;
    }

    private DocumentEditIntent normalizeSectionInlineInsertIntent(DocumentEditIntent intent) {
        if (intent == null || intent.getIntentType() != DocumentIterationIntentType.INSERT) {
            return intent;
        }
        if (intent.getSemanticAction() != DocumentSemanticActionType.INSERT_INLINE_TEXT) {
            return intent;
        }
        DocumentAnchorSpec anchorSpec = intent.getAnchorSpec();
        if (anchorSpec == null || anchorSpec.getAnchorKind() != DocumentAnchorKind.SECTION) {
            return intent;
        }
        if (anchorSpec.getMatchMode() == null || anchorSpec.getMatchMode() == DocumentAnchorMatchMode.BY_QUOTED_TEXT) {
            return intent;
        }
        log.info("DOC_ITER_INTENT normalize_section_inline_insert instruction='{}' fromAction={} toAction={} anchorKind={} matchMode={} headingTitle='{}' headingNumber='{}' outlinePath='{}'",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR,
                anchorSpec.getAnchorKind(),
                anchorSpec.getMatchMode(),
                anchorSpec.getHeadingTitle(),
                anchorSpec.getHeadingNumber(),
                effectiveOutlinePath(anchorSpec));
        if (!hasText(anchorSpec.getRelativePosition())) {
            anchorSpec.setRelativePosition(detectRelativePosition(intent.getUserInstruction()));
        }
        intent.setSemanticAction(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        return intent;
    }

    private boolean looksLikeSectionInsert(DocumentEditIntent intent) {
        if (intent == null || intent.getSemanticAction() == null) {
            return false;
        }
        return switch (intent.getSemanticAction()) {
            case INSERT_SECTION_BEFORE_SECTION, INSERT_BLOCK_AFTER_ANCHOR, INSERT_INLINE_TEXT -> true;
            default -> false;
        };
    }

    private DocumentSemanticActionType normalizeInsertSemanticAction(String instruction, DocumentSemanticActionType currentAction) {
        if (!hasText(instruction)) {
            return currentAction;
        }
        if (INSERT_AFTER_PATTERN.matcher(instruction).find()) {
            return DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR;
        }
        if (INSERT_BEFORE_PATTERN.matcher(instruction).find()) {
            return DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION;
        }
        return currentAction == null ? DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION : currentAction;
    }

    private boolean isExplicitSectionInsertInstruction(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        boolean hasDirection = INSERT_AFTER_PATTERN.matcher(instruction).find()
                || INSERT_BEFORE_PATTERN.matcher(instruction).find();
        return hasDirection && INSERTED_SECTION_CANDIDATE_PATTERN.matcher(instruction).find();
    }

    private DocumentEditIntent normalizeSectionAnchorIntent(DocumentEditIntent intent) {
        if (intent == null || !hasText(intent.getUserInstruction()) || !expectsSectionAnchor(intent.getSemanticAction())) {
            return intent;
        }
        SectionReference sectionReference = extractSectionReference(intent.getUserInstruction());
        if (sectionReference == null) {
            return intent;
        }
        DocumentAnchorSpec anchorSpec = intent.getAnchorSpec();
        if (anchorSpec != null && anchorSpec.getMatchMode() != DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL) {
            return intent;
        }
        log.info("DOC_ITER_INTENT normalize_section_anchor instruction='{}' action={} headingTitle='{}' headingNumber='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                sectionReference.headingTitle(),
                sectionReference.headingNumber(),
                sectionReference.effectiveOutlinePath(),
                sectionReference.structuralOrdinal(),
                sectionReference.structuralOrdinalScope());
        intent.setAnchorSpec(buildSectionAnchor(sectionReference));
        return intent;
    }

    private boolean looksLikeSectionBodyRewrite(DocumentEditIntent intent) {
        if (intent == null) {
            return false;
        }
        DocumentSemanticActionType semanticAction = intent.getSemanticAction();
        if (semanticAction != DocumentSemanticActionType.REWRITE_SINGLE_BLOCK
                && semanticAction != DocumentSemanticActionType.REWRITE_INLINE_TEXT
                && semanticAction != DocumentSemanticActionType.REWRITE_SECTION_BODY) {
            return false;
        }
        DocumentAnchorSpec anchorSpec = intent.getAnchorSpec();
        if (anchorSpec == null) {
            return true;
        }
        return anchorSpec.getMatchMode() == DocumentAnchorMatchMode.BY_QUOTED_TEXT
                || anchorSpec.getAnchorKind() == DocumentAnchorKind.BLOCK
                || anchorSpec.getAnchorKind() == DocumentAnchorKind.TEXT
                || anchorSpec.getAnchorKind() == null;
    }

    private DocumentAnchorSpec buildSectionAnchor(SectionReference sectionReference) {
        DocumentAnchorSpec.DocumentAnchorSpecBuilder builder = DocumentAnchorSpec.builder()
                .anchorKind(DocumentAnchorKind.SECTION);
        if (hasText(sectionReference.headingNumber())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_HEADING_NUMBER)
                    .headingTitle(sectionReference.headingTitle())
                    .headingNumber(sectionReference.headingNumber())
                    .parentHeadingNumber(sectionReference.parentHeadingNumber())
                    .relativePosition(sectionReference.relativePosition())
                    .scopeHint(sectionReference.scopeHint())
                    .build();
        }
        if (hasText(sectionReference.outlinePathNumbers())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_OUTLINE_PATH_NUMBERS)
                    .outlinePath(sectionReference.outlinePathNumbers())
                    .outlinePathNumbers(sectionReference.outlinePathNumbers())
                    .parentHeadingNumber(sectionReference.parentHeadingNumber())
                    .relativePosition(sectionReference.relativePosition())
                    .scopeHint(sectionReference.scopeHint())
                    .build();
        }
        if (hasText(sectionReference.outlinePathText())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_OUTLINE_PATH_TEXT)
                    .outlinePath(sectionReference.outlinePathText())
                    .outlinePathText(sectionReference.outlinePathText())
                    .parentHeadingNumber(sectionReference.parentHeadingNumber())
                    .relativePosition(sectionReference.relativePosition())
                    .scopeHint(sectionReference.scopeHint())
                    .build();
        }
        if (hasText(sectionReference.headingTitle())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                    .headingTitle(sectionReference.headingTitle())
                    .parentHeadingNumber(sectionReference.parentHeadingNumber())
                    .relativePosition(sectionReference.relativePosition())
                    .scopeHint(sectionReference.scopeHint())
                    .build();
        }
        return builder
                .matchMode(DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL)
                .structuralOrdinal(sectionReference.structuralOrdinal())
                .structuralOrdinalScope(sectionReference.structuralOrdinalScope())
                .parentHeadingNumber(sectionReference.parentHeadingNumber())
                .relativePosition(sectionReference.relativePosition())
                .scopeHint(sectionReference.scopeHint())
                .build();
    }

    private SectionReference extractSectionReference(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        String relativePosition = detectRelativePosition(instruction);
        java.util.regex.Matcher decimalMatcher = DECIMAL_SECTION_HEADING_PATTERN.matcher(instruction);
        if (decimalMatcher.find()) {
            String headingSuffix = normalizeSectionHeadingSuffix(decimalMatcher.group(2));
            if (hasText(headingSuffix)) {
                String headingNumber = decimalMatcher.group(1);
                String headingTitle = (headingNumber + " " + headingSuffix).trim();
                return new SectionReference(headingTitle, headingNumber, null, null, null, null, relativePosition, "CHILD_SECTION");
            }
        }
        java.util.regex.Matcher decimalPathMatcher = DECIMAL_SECTION_PATH_PATTERN.matcher(instruction);
        if (decimalPathMatcher.find()) {
            String path = decimalPathMatcher.group(1);
            if (path.contains(".")) {
                String[] parts = path.split("\\.");
                if (parts.length >= 3) {
                    return new SectionReference(null, null, null, path, null, null, relativePosition, "CHILD_SECTION");
                }
                if (parts.length == 2) {
                    return new SectionReference(null, path, null, null, null, null, relativePosition, "CHILD_SECTION");
                }
            }
        }
        java.util.regex.Matcher chapterThenSectionMatcher = CHAPTER_THEN_SECTION_PATTERN.matcher(instruction);
        if (chapterThenSectionMatcher.find()) {
            Integer parentOrdinal = parseChineseOrArabicOrdinal(chapterThenSectionMatcher.group(1));
            Integer childOrdinal = parseChineseOrArabicOrdinal(chapterThenSectionMatcher.group(2));
            if (parentOrdinal != null && childOrdinal != null) {
                return new SectionReference(null, null, null, null, childOrdinal,
                        "CHILD_OF_HEADING_NUMBER:" + parentOrdinal, relativePosition, "CHILD_SECTION")
                        .withParentHeadingNumber(String.valueOf(parentOrdinal));
            }
        }
        java.util.regex.Matcher chinesePathMatcher = CHINESE_SECTION_PATH_PATTERN.matcher(instruction);
        if (chinesePathMatcher.find()) {
            String path = normalizeChineseSectionPath(chinesePathMatcher.group(1));
            if (hasText(path) && path.contains("/")) {
                String parentHeadingNumber = extractParentHeadingNumberFromChinesePath(path);
                return new SectionReference(null, null, path, null, null, null, relativePosition, "CHILD_SECTION")
                        .withParentHeadingNumber(parentHeadingNumber);
            }
        }
        java.util.regex.Matcher chineseMatcher = CHINESE_SECTION_ORDINAL_PATTERN.matcher(instruction);
        if (chineseMatcher.find()) {
            Integer ordinal = parseChineseOrArabicOrdinal(chineseMatcher.group(1));
            if (ordinal != null) {
                String scope = "章".equals(chineseMatcher.group(2)) ? "TOP_LEVEL_SECTION" : "SUB_SECTION";
                if ("章".equals(chineseMatcher.group(2))) {
                    return new SectionReference(null, null, null, null, ordinal, scope, relativePosition, "TOP_LEVEL_SECTION");
                }
                return new SectionReference(null, null, null, null, ordinal, scope, relativePosition, "CHILD_SECTION");
            }
        }
        return null;
    }

    private SectionReference extractInsertAnchorSectionReference(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        String relativePosition = detectRelativePosition(instruction);
        String anchorSegment = instruction;
        java.util.regex.Matcher afterMatcher = INSERT_AFTER_PATTERN.matcher(instruction);
        java.util.regex.Matcher beforeMatcher = INSERT_BEFORE_PATTERN.matcher(instruction);
        if (afterMatcher.find()) {
            anchorSegment = instruction.substring(0, afterMatcher.start()).trim();
        } else if (beforeMatcher.find()) {
            anchorSegment = instruction.substring(0, beforeMatcher.start()).trim();
        }
        SectionReference sectionReference = extractSectionReference(anchorSegment);
        if (sectionReference != null && !hasText(sectionReference.relativePosition()) && hasText(relativePosition)) {
            return new SectionReference(
                    sectionReference.headingTitle(),
                    sectionReference.headingNumber(),
                    sectionReference.outlinePathText(),
                    sectionReference.outlinePathNumbers(),
                    sectionReference.structuralOrdinal(),
                    sectionReference.structuralOrdinalScope(),
                    relativePosition,
                    sectionReference.scopeHint(),
                    sectionReference.parentHeadingNumber()
            );
        }
        return sectionReference != null ? sectionReference : extractSectionReference(instruction);
    }

    private Integer parseChineseOrArabicOrdinal(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> null;
            };
        }
    }

    private String normalizeSectionHeadingSuffix(String rawSuffix) {
        if (!hasText(rawSuffix)) {
            return null;
        }
        String normalized = rawSuffix.trim();
        java.util.regex.Matcher markerMatcher = SECTION_ACTION_MARKER_PATTERN.matcher(normalized);
        if (markerMatcher.find()) {
            normalized = normalized.substring(0, markerMatcher.start()).trim();
        }
        normalized = normalized.replaceFirst("(前|后|之前|之后)$", "").trim();
        normalized = normalized.replaceFirst("(中的|中|里|内的|内)(数据|内容|正文|表述|描述|部分).*$", "").trim();
        normalized = normalized.replaceFirst("的(数据|内容|正文|表述|描述|部分).*$", "").trim();
        normalized = normalized.replaceAll("[的:：，。；,;]+$", "").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeChineseSectionPath(String rawPath) {
        if (!hasText(rawPath)) {
            return null;
        }
        java.util.regex.Matcher matcher = CHINESE_SECTION_ORDINAL_PATTERN.matcher(rawPath);
        java.util.List<String> segments = new java.util.ArrayList<>();
        while (matcher.find()) {
            Integer ordinal = parseChineseOrArabicOrdinal(matcher.group(1));
            if (ordinal == null) {
                return null;
            }
            String unit = matcher.group(2);
            segments.add("第" + ordinal + unit);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private String extractParentHeadingNumberFromChinesePath(String path) {
        if (!hasText(path) || !path.contains("/")) {
            return null;
        }
        String first = path.substring(0, path.indexOf('/'));
        java.util.regex.Matcher matcher = CHINESE_SECTION_ORDINAL_PATTERN.matcher(first);
        if (!matcher.find()) {
            return null;
        }
        Integer ordinal = parseChineseOrArabicOrdinal(matcher.group(1));
        return ordinal == null ? null : String.valueOf(ordinal);
    }

    private String detectRelativePosition(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        if (INSERT_AFTER_PATTERN.matcher(instruction).find()) {
            return "AFTER";
        }
        if (INSERT_BEFORE_PATTERN.matcher(instruction).find()) {
            return "BEFORE";
        }
        return null;
    }

    private String effectiveOutlinePath(DocumentAnchorSpec anchorSpec) {
        if (anchorSpec == null) {
            return null;
        }
        if (hasText(anchorSpec.getOutlinePathText())) {
            return anchorSpec.getOutlinePathText();
        }
        if (hasText(anchorSpec.getOutlinePathNumbers())) {
            return anchorSpec.getOutlinePathNumbers();
        }
        return anchorSpec.getOutlinePath();
    }

    private boolean expectsSectionAnchor(DocumentSemanticActionType semanticAction) {
        if (semanticAction == null) {
            return false;
        }
        return switch (semanticAction) {
            case INSERT_SECTION_BEFORE_SECTION, INSERT_BLOCK_AFTER_ANCHOR, INSERT_INLINE_TEXT,
                 REWRITE_SECTION_BODY, DELETE_SECTION_BODY, DELETE_WHOLE_SECTION, MOVE_SECTION, RELAYOUT_SECTION -> true;
            default -> false;
        };
    }

    private boolean mentionsSectionReference(String instruction) {
        return hasText(instruction)
                && SECTION_ORDINAL_PATTERN.matcher(instruction).find()
                && SECTION_CONTENT_REFERENCE_PATTERN.matcher(instruction).find();
    }

    private boolean isLiteralQuotedReference(String instruction, String quotedText) {
        if (!hasText(instruction) || !hasText(quotedText)) {
            return false;
        }
        return instruction.contains("“" + quotedText + "”")
                || instruction.contains("\"" + quotedText + "\"")
                || instruction.contains("'" + quotedText + "'");
    }

    private DocumentEditIntent clarificationIntent(String instruction, String hint) {
        return DocumentEditIntent.builder()
                .userInstruction(instruction)
                .clarificationNeeded(true)
                .clarificationHint(hint)
                .build();
    }

    private DocumentEditIntent ensureClarificationHint(DocumentEditIntent intent, String fallbackHint) {
        if (intent.getClarificationHint() != null && !intent.getClarificationHint().isBlank()) {
            return intent;
        }
        intent.setClarificationHint(fallbackHint);
        return intent;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> clazz, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(clazz, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String str(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SectionReference(String headingTitle,
                                    String headingNumber,
                                    String outlinePathText,
                                    String outlinePathNumbers,
                                    Integer structuralOrdinal,
                                    String structuralOrdinalScope,
                                    String relativePosition,
                                    String scopeHint,
                                    String parentHeadingNumber) {
        private SectionReference(String headingTitle,
                                 String headingNumber,
                                 String outlinePathText,
                                 String outlinePathNumbers,
                                 Integer structuralOrdinal,
                                 String structuralOrdinalScope,
                                 String relativePosition,
                                 String scopeHint) {
            this(headingTitle, headingNumber, outlinePathText, outlinePathNumbers, structuralOrdinal,
                    structuralOrdinalScope, relativePosition, scopeHint, null);
        }

        private SectionReference withParentHeadingNumber(String value) {
            return new SectionReference(headingTitle, headingNumber, outlinePathText, outlinePathNumbers,
                    structuralOrdinal, structuralOrdinalScope, relativePosition, scopeHint, value);
        }

        private String effectiveOutlinePath() {
            if (outlinePathText != null && !outlinePathText.isBlank()) {
                return outlinePathText;
            }
            if (outlinePathNumbers != null && !outlinePathNumbers.isBlank()) {
                return outlinePathNumbers;
            }
            return null;
        }
    }
}
