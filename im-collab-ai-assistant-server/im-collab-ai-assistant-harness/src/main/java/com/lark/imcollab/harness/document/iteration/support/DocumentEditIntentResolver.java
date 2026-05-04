package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class DocumentEditIntentResolver {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditIntentResolver.class);

    private static final Pattern SECTION_ORDINAL_PATTERN = Pattern.compile("(?:^|\\s)(?:\\d+(?:\\.\\d+)*|第[一二三四五六七八九十百千万0-9]+[章节部分](?:第[一二三四五六七八九十百千万0-9]+[章节部分])*)");
    private static final Pattern SECTION_CONTENT_REFERENCE_PATTERN = Pattern.compile("(章节|小节|部分|段落|标题|内容|正文|数据|表述)");
    private static final Pattern SECTION_ACTION_MARKER_PATTERN = Pattern.compile("(给出|补充|增加|新增|插入|改写|修改|更新|替换|删除|移到|移动|调整|优化|展开|说明|完善|重写)");
    private static final Pattern INSERT_AFTER_PATTERN = Pattern.compile("(后插入|后新增|后面插入|之后插入)");
    private static final Pattern INSERT_BEFORE_PATTERN = Pattern.compile("(前插入|前新增|前面插入|之前插入)");
    private static final Pattern INSERTED_SECTION_CANDIDATE_PATTERN = Pattern.compile("(插入|新增)\\s*(?:第[一二三四五六七八九十百千万0-9]+[章节部分]|\\d+(?:\\.\\d+)+)");
    private static final Pattern DECIMAL_SECTION_HEADING_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)\\s*([^，。；,;\\n]+)");
    private static final Pattern DECIMAL_SECTION_PATH_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
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
            String response = chatModel.call(buildPrompt(instruction));
            Map<String, Object> payload = objectMapper.readValue(stripCodeFences(response), new TypeReference<>() {});
            DocumentEditIntent parsedIntent = fromPayload(instruction, payload);
            DocumentEditIntent validatedIntent = validate(parsedIntent);
            DocumentEditIntent enrichedIntent = enrichWithWorkspaceContext(validatedIntent, workspaceContext);
            log.info("DOC_ITER_INTENT resolved instruction='{}' rawIntentType={} rawAction={} finalIntentType={} finalAction={} anchorKind={} matchMode={} headingTitle='{}' outlinePath='{}' ordinal={} ordinalScope={} clarificationNeeded={} clarificationHint='{}'",
                    instruction,
                    parsedIntent == null ? null : parsedIntent.getIntentType(),
                    parsedIntent == null ? null : parsedIntent.getSemanticAction(),
                    enrichedIntent == null ? null : enrichedIntent.getIntentType(),
                    enrichedIntent == null ? null : enrichedIntent.getSemanticAction(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getAnchorKind(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getMatchMode(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getHeadingTitle(),
                    enrichedIntent == null || enrichedIntent.getAnchorSpec() == null ? null : enrichedIntent.getAnchorSpec().getOutlinePath(),
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
                    .outlinePath(str(anchorMap.get("outlinePath")))
                    .structuralOrdinal(toInt(anchorMap.get("structuralOrdinal")))
                    .structuralOrdinalScope(str(anchorMap.get("structuralOrdinalScope")))
                    .quotedText(str(anchorMap.get("quotedText")))
                    .mediaCaption(str(anchorMap.get("mediaCaption")))
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
        intent = normalizeSectionInsertIntent(intent);
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

    private String buildPrompt(String instruction) {
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
                    "outlinePath": "如 '第一章/第二节'，BY_OUTLINE_PATH 时填",
                    "structuralOrdinal": 1,
                    "structuralOrdinalScope": "TOP_LEVEL_SECTION|SUB_SECTION",
                    "quotedText": "用户引号内的文本，BY_QUOTED_TEXT 时填",
                    "mediaCaption": "媒体说明文字，BY_MEDIA_CAPTION 时填",
                    "blockId": "仅在上游已明确给出平台 blockId 时填写"
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
                2. anchorSpec.matchMode 必须是结构化 slot，不允许把中文"第三章"直接放进 headingTitle，应用 structuralOrdinal=3 + structuralOrdinalScope=TOP_LEVEL_SECTION。
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

                用户指令：%s
                """.formatted(intentTypes, semanticActions, riskLevels, anchorKinds, matchModes, assetTypes, sourceTypes, instruction);
        return prompt;
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
            case BY_OUTLINE_PATH -> hasText(anchorSpec.getOutlinePath());
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
        log.info("DOC_ITER_INTENT normalize_section instruction='{}' fromAction={} toAction={} headingTitle='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                DocumentSemanticActionType.REWRITE_SECTION_BODY,
                sectionReference.headingTitle(),
                sectionReference.outlinePath(),
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
        SectionReference sectionReference = extractSectionReference(intent.getUserInstruction());
        if (sectionReference == null) {
            return intent;
        }
        DocumentSemanticActionType normalizedAction = normalizeInsertSemanticAction(intent.getUserInstruction(), intent.getSemanticAction());
        DocumentAnchorSpec normalizedAnchor = buildSectionAnchor(sectionReference);
        log.info("DOC_ITER_INTENT normalize_insert instruction='{}' fromAction={} toAction={} headingTitle='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                normalizedAction,
                sectionReference.headingTitle(),
                sectionReference.outlinePath(),
                sectionReference.structuralOrdinal(),
                sectionReference.structuralOrdinalScope());
        intent.setIntentType(DocumentIterationIntentType.INSERT);
        intent.setSemanticAction(normalizedAction);
        intent.setAnchorSpec(normalizedAnchor);
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
        log.info("DOC_ITER_INTENT normalize_section_anchor instruction='{}' action={} headingTitle='{}' outlinePath='{}' ordinal={} ordinalScope={}",
                intent.getUserInstruction(),
                intent.getSemanticAction(),
                sectionReference.headingTitle(),
                sectionReference.outlinePath(),
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
        if (hasText(sectionReference.headingTitle())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                    .headingTitle(sectionReference.headingTitle())
                    .build();
        }
        if (hasText(sectionReference.outlinePath())) {
            return builder
                    .matchMode(DocumentAnchorMatchMode.BY_OUTLINE_PATH)
                    .outlinePath(sectionReference.outlinePath())
                    .build();
        }
        return builder
                .matchMode(DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL)
                .structuralOrdinal(sectionReference.structuralOrdinal())
                .structuralOrdinalScope(sectionReference.structuralOrdinalScope())
                .build();
    }

    private SectionReference extractSectionReference(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        java.util.regex.Matcher decimalMatcher = DECIMAL_SECTION_HEADING_PATTERN.matcher(instruction);
        if (decimalMatcher.find()) {
            String headingSuffix = normalizeSectionHeadingSuffix(decimalMatcher.group(2));
            if (hasText(headingSuffix)) {
                String headingTitle = (decimalMatcher.group(1) + " " + headingSuffix).trim();
                return new SectionReference(headingTitle, null, null, null);
            }
        }
        java.util.regex.Matcher decimalPathMatcher = DECIMAL_SECTION_PATH_PATTERN.matcher(instruction);
        if (decimalPathMatcher.find()) {
            String path = decimalPathMatcher.group(1);
            if (path.contains(".")) {
                if (path.chars().filter(ch -> ch == '.').count() >= 2) {
                    return new SectionReference(null, null, null, path);
                }
                String[] parts = path.split("\\.");
                String scope = parts.length == 2 ? "SUB_SECTION" : "TOP_LEVEL_SECTION";
                Integer ordinal = toInt(parts[parts.length - 1]);
                if (ordinal != null) {
                    return new SectionReference(null, ordinal, scope, null);
                }
            }
        }
        java.util.regex.Matcher chinesePathMatcher = CHINESE_SECTION_PATH_PATTERN.matcher(instruction);
        if (chinesePathMatcher.find()) {
            String path = normalizeChineseSectionPath(chinesePathMatcher.group(1));
            if (hasText(path) && path.contains("/")) {
                return new SectionReference(null, null, null, path);
            }
        }
        java.util.regex.Matcher chineseMatcher = CHINESE_SECTION_ORDINAL_PATTERN.matcher(instruction);
        if (chineseMatcher.find()) {
            Integer ordinal = parseChineseOrArabicOrdinal(chineseMatcher.group(1));
            if (ordinal != null) {
                String scope = "章".equals(chineseMatcher.group(2)) ? "TOP_LEVEL_SECTION" : "SUB_SECTION";
                return new SectionReference(null, ordinal, scope, null);
            }
        }
        return null;
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

    private boolean expectsSectionAnchor(DocumentSemanticActionType semanticAction) {
        if (semanticAction == null) {
            return false;
        }
        return switch (semanticAction) {
            case INSERT_SECTION_BEFORE_SECTION, REWRITE_SECTION_BODY, DELETE_SECTION_BODY, DELETE_WHOLE_SECTION, MOVE_SECTION, RELAYOUT_SECTION -> true;
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

    private record SectionReference(String headingTitle, Integer structuralOrdinal, String structuralOrdinalScope,
                                    String outlinePath) {
    }
}
