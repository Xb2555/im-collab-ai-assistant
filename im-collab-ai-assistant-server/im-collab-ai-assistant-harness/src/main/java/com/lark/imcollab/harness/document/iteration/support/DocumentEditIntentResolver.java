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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentEditIntentResolver {

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
            return enrichWithWorkspaceContext(normalizeForInstruction(instruction, fromPayload(instruction, payload)), workspaceContext);
        } catch (Exception e) {
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

        return """
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
                    "mediaCaption": "媒体说明文字，BY_MEDIA_CAPTION 时填"
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

                用户指令：%s
                """.formatted(intentTypes, semanticActions, riskLevels, anchorKinds, matchModes, assetTypes, sourceTypes, instruction);
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

    private DocumentEditIntent normalizeForInstruction(String instruction, DocumentEditIntent intent) {
        if (intent == null || instruction == null) {
            return intent;
        }
        String lower = instruction.trim().toLowerCase(java.util.Locale.ROOT);
        String headingTitle = extractHeadingTarget(instruction);
        if (headingTitle != null && !headingTitle.isBlank()) {
            intent = ensureHeadingAnchor(intent, headingTitle);
        }
        boolean deleteHint = lower.contains("删") || lower.contains("去掉") || lower.contains("移除");
        if (!deleteHint) {
            return intent;
        }
        boolean sectionHint = lower.matches(".*(\\d+(\\.\\d+)*\\s*[^\\s]+).*")
                || lower.contains("章节")
                || lower.contains("小节")
                || lower.contains("节")
                || lower.contains("标题");
        DocumentSemanticActionType semanticAction = sectionHint
                ? DocumentSemanticActionType.DELETE_WHOLE_SECTION
                : DocumentSemanticActionType.DELETE_INLINE_TEXT;
        return DocumentEditIntent.builder()
                .intentType(DocumentIterationIntentType.DELETE)
                .semanticAction(semanticAction)
                .userInstruction(intent.getUserInstruction())
                .anchorSpec(intent.getAnchorSpec())
                .rewriteSpec(intent.getRewriteSpec())
                .assetSpec(intent.getAssetSpec())
                .clarificationNeeded(intent.isClarificationNeeded())
                .clarificationHint(intent.getClarificationHint())
                .riskLevel(intent.getRiskLevel())
                .riskHints(intent.getRiskHints())
                .build();
    }

    private DocumentEditIntent ensureHeadingAnchor(DocumentEditIntent intent, String headingTitle) {
        if (intent.getAnchorSpec() != null
                && intent.getAnchorSpec().getMatchMode() == DocumentAnchorMatchMode.BY_HEADING_TITLE
                && hasText(intent.getAnchorSpec().getHeadingTitle())) {
            return intent;
        }
        DocumentAnchorSpec anchorSpec = DocumentAnchorSpec.builder()
                .anchorKind(DocumentAnchorKind.DOCUMENT_HEAD)
                .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                .headingTitle(headingTitle)
                .build();
        return DocumentEditIntent.builder()
                .intentType(intent.getIntentType())
                .semanticAction(intent.getSemanticAction())
                .userInstruction(intent.getUserInstruction())
                .anchorSpec(anchorSpec)
                .rewriteSpec(intent.getRewriteSpec())
                .assetSpec(intent.getAssetSpec())
                .clarificationNeeded(intent.isClarificationNeeded())
                .clarificationHint(intent.getClarificationHint())
                .riskLevel(intent.getRiskLevel())
                .riskHints(intent.getRiskHints())
                .build();
    }

    private String extractHeadingTarget(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        String normalized = instruction.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?:在|于)?\\s*([0-9一二三四五六七八九十百千万]+(?:\\.[0-9一二三四五六七八九十百千万]+)*\\s*[^，。；:：\\n]+?)\\s*(?:中|里|内)?\\s*(?:新增|添加|插入|修改|改写|删除|去掉|移除)"
        ).matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = java.util.regex.Pattern.compile(
                "(?:删除|修改|改写|新增|添加|插入)\\s*([0-9一二三四五六七八九十百千万]+(?:\\.[0-9一二三四五六七八九十百千万]+)*\\s*[^，。；:：\\n]+)"
        ).matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
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
}
