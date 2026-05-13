package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.PlannerContextAcquisitionFacade;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchItem;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchResult;
import com.lark.imcollab.skills.lark.im.LarkMessageSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DefaultPlannerContextAcquisitionFacade implements PlannerContextAcquisitionFacade {
    private static final Logger log = LoggerFactory.getLogger(DefaultPlannerContextAcquisitionFacade.class);

    private final LarkMessageSearchTool messageSearchTool;
    private final LarkDocTool docTool;
    private final PlannerProperties plannerProperties;
    private final ContextMessageSelectionService messageSelectionService;

    public DefaultPlannerContextAcquisitionFacade(
            LarkMessageSearchTool messageSearchTool,
            LarkDocTool docTool,
            PlannerProperties plannerProperties,
            ContextMessageSelectionService messageSelectionService
    ) {
        this.messageSearchTool = messageSearchTool;
        this.docTool = docTool;
        this.plannerProperties = plannerProperties;
        this.messageSelectionService = messageSelectionService;
    }

    @Override
    public ContextAcquisitionResult acquire(
            ContextAcquisitionPlan plan,
            WorkspaceContext workspaceContext,
            String rawInstruction
    ) {
        if (plan == null || !plan.isNeedCollection() || plan.getSources() == null || plan.getSources().isEmpty()) {
            return ContextAcquisitionResult.failure("没有可用的上下文收集计划。");
        }
        List<String> selectedMessages = new ArrayList<>();
        List<String> selectedMessageIds = new ArrayList<>();
        List<String> docFragments = new ArrayList<>();
        Set<String> sourceRefs = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();

        for (ContextSourceRequest source : plan.getSources()) {
            if (source == null || source.getSourceType() == null) {
                continue;
            }
            try {
                if (source.getSourceType() == ContextSourceTypeEnum.IM_HISTORY) {
                    collectImSearch(source, workspaceContext, rawInstruction, selectedMessages, selectedMessageIds, sourceRefs);
                } else if (source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH) {
                    collectImSearch(source, workspaceContext, rawInstruction, selectedMessages, selectedMessageIds, sourceRefs);
                } else if (source.getSourceType() == ContextSourceTypeEnum.LARK_DOC) {
                    collectDocs(source, workspaceContext, docFragments, sourceRefs);
                }
            } catch (RuntimeException exception) {
                log.warn("Planner context acquisition failed: sourceType={}, chatId={}, query={}, timeRange={}, reason={}",
                        source.getSourceType(), source.getChatId(), source.getQuery(), source.getTimeRange(), safeMessage(exception));
                failures.add(readableSourceName(source) + "读取失败：" + safeMessage(exception));
            }
        }

        boolean hasContent = !selectedMessages.isEmpty() || !docFragments.isEmpty();
        String summary = buildSummary(selectedMessages, docFragments, sourceRefs);
        String failureMessage = firstNonBlank(String.join("；", failures), "没有读取到可用上下文。");
        return ContextAcquisitionResult.builder()
                .success(hasContent)
                .sufficient(hasContent)
                .contextSummary(summary)
                .selectedMessages(selectedMessages)
                .selectedMessageIds(selectedMessageIds)
                .docFragments(docFragments)
                .sourceRefs(new ArrayList<>(sourceRefs))
                .message(hasContent ? summary : failureMessage)
                .clarificationQuestion(hasContent ? "" : buildNoContentQuestion(plan, workspaceContext, failures))
                .build();
    }

    private void collectImSearch(
            ContextSourceRequest source,
            WorkspaceContext workspaceContext,
            String rawInstruction,
            List<String> selectedMessages,
            List<String> selectedMessageIds,
            Set<String> sourceRefs
    ) {
        String chatId = firstNonBlank(source.getChatId(), workspaceContext == null ? null : workspaceContext.getChatId());
        String query = sanitizeSearchQuery(firstNonBlank(source.getQuery(), extractKeywordFromSelection(source.getSelectionInstruction())));
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        TimeRange range = resolveStructuredTimeRange(source);
        int maxMessages = normalizeLimit(source.getLimit(), configuredMaxImMessages());
        int pageSize = normalizeLimit(firstNonNull(source.getPageSize(), source.getLimit()), 50);
        int defaultPageLimit = hasText(query) ? 5 : defaultChatWindowPageLimit(pageSize, maxMessages);
        int pageLimit = firstNonNull(source.getPageLimit(), defaultPageLimit);
        log.info("IM_CONTEXT_SEARCH_START mode={} chatId={} query='{}' timeRange='{}' start={} end={} pageSize={} pageLimit={}",
                hasText(query) ? "keyword" : "chat-window",
                chatId,
                firstNonBlank(query),
                firstNonBlank(source.getTimeRange()),
                range == null ? "" : firstNonBlank(range.start()),
                range == null ? "" : firstNonBlank(range.end()),
                pageSize,
                pageLimit);
        LarkMessageSearchResult result = messageSearchTool.searchMessages(
                query,
                chatId,
                range == null ? null : range.start(),
                range == null ? null : range.end(),
                pageSize,
                pageLimit
        );
        int rawItemCount = result == null || result.items() == null ? 0 : result.items().size();
        log.info("IM_CONTEXT_SEARCH_RESULT mode={} chatId={} query='{}' rawItemCount={}",
                hasText(query) ? "keyword" : "chat-window",
                chatId,
                firstNonBlank(query),
                rawItemCount);
        if (result == null || result.items() == null || result.items().isEmpty()) {
            return;
        }
        sourceRefs.add(buildImSearchSourceRef(chatId, query, range));
        List<LarkMessageSearchItem> candidates = result.items().stream()
                .filter(item -> item != null
                        && !item.deleted()
                        && item.content() != null
                        && !item.content().isBlank()
                        && !"system".equalsIgnoreCase(item.msgType())
                        && !"app".equalsIgnoreCase(item.senderType())
                        && !"bot".equalsIgnoreCase(item.senderType())
                        && (workspaceContext == null
                        || workspaceContext.getMessageId() == null
                        || !workspaceContext.getMessageId().equals(item.messageId()))
                        && !sameText(item.content(), rawInstruction)
                        && !containsUserInstruction(item.content(), rawInstruction))
                .toList();
        Set<String> explicitMessageIds = selectedMessageIds(workspaceContext);
        if (!explicitMessageIds.isEmpty()) {
            candidates = candidates.stream()
                    .filter(item -> item.messageId() != null && explicitMessageIds.contains(item.messageId()))
                    .toList();
        }
        int selectedCount = Math.min(candidates.size(), maxMessages);
        log.info("IM_CONTEXT_SEARCH_FILTERED chatId={} query='{}' candidateCount={} selectedCount={} explicitMessageIds={}",
                chatId,
                firstNonBlank(query),
                candidates.size(),
                selectedCount,
                explicitMessageIds.size());
        for (LarkMessageSearchItem item : candidates.stream().limit(maxMessages).toList()) {
            selectedMessages.add(renderMessage(item));
            if (item.messageId() != null && !item.messageId().isBlank()) {
                selectedMessageIds.add(item.messageId());
            }
        }
    }

    private int defaultChatWindowPageLimit(int pageSize, int maxMessages) {
        int safePageSize = Math.max(1, pageSize);
        int safeMaxMessages = Math.max(1, maxMessages);
        int estimatedPages = (int) Math.ceil((double) safeMaxMessages / safePageSize);
        return Math.max(3, Math.min(10, estimatedPages + 2));
    }

    private String buildImSearchSourceRef(String chatId, String query, TimeRange range) {
        List<String> parts = new ArrayList<>();
        parts.add("im-search:chat:" + chatId);
        if (hasText(query)) {
            parts.add("query:" + query);
        }
        if (range != null) {
            parts.add("time:" + range.start() + "/" + range.end());
        }
        return String.join(":", parts);
    }

    private void collectDocs(
            ContextSourceRequest source,
            WorkspaceContext workspaceContext,
            List<String> docFragments,
            Set<String> sourceRefs
    ) {
        List<String> refs = new ArrayList<>();
        if (source.getDocRefs() != null) {
            refs.addAll(source.getDocRefs());
        }
        if (workspaceContext != null && workspaceContext.getDocRefs() != null) {
            refs.addAll(workspaceContext.getDocRefs());
        }
        int maxChars = Math.max(500, plannerProperties.getContextCollection().getMaxDocChars());
        for (String ref : refs.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
            LarkDocFetchResult result = docTool.fetchDoc(ref, "full", "simple");
            String content = truncate(result == null ? "" : result.getContent(), maxChars);
            if (content == null || content.isBlank()) {
                continue;
            }
            String title = result == null ? "" : firstNonBlank(result.getTitle(), ref);
            docFragments.add("文档《" + title + "》摘录：\n" + content);
            sourceRefs.add("doc:" + ref);
        }
    }

    private TimeRange resolveStructuredTimeRange(ContextSourceRequest source) {
        if (source == null || !hasAnyTimeConstraint(source)) {
            return null;
        }
        String normalizedStart = normalizeSearchTime(source.getStartTime());
        String normalizedEnd = normalizeSearchTime(source.getEndTime());
        if (!hasText(normalizedStart) || !hasText(normalizedEnd)) {
            throw new IllegalStateException("时间范围缺少可执行的开始或结束时间，请补充具体开始和结束时间。");
        }
        try {
            OffsetDateTime start = OffsetDateTime.parse(normalizedStart);
            OffsetDateTime end = OffsetDateTime.parse(normalizedEnd);
            if (start.isAfter(end)) {
                throw new IllegalStateException("时间范围的开始时间晚于结束时间，请检查后重试。");
            }
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("时间范围格式不合法，请补充具体开始和结束时间。", exception);
        }
        return new TimeRange(normalizedStart, normalizedEnd);
    }

    private boolean hasAnyTimeConstraint(ContextSourceRequest source) {
        return source != null
                && (hasText(source.getTimeRange())
                || hasText(source.getStartTime())
                || hasText(source.getEndTime()));
    }

    private String extractKeywordFromSelection(String value) {
        if (!hasText(value)) {
            return "";
        }
        List<Pattern> patterns = List.of(
                Pattern.compile("有关(.{2,40}?)(?:的)?(?:讨论|消息|记录|聊天|内容)"),
                Pattern.compile("关于(.{2,40}?)(?:的)?(?:讨论|消息|记录|聊天|内容)"),
                Pattern.compile("(.{2,40}?)(?:相关|有关|关于)(?:的)?(?:讨论|消息|记录|聊天|内容)")
        );
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                String cleaned = matcher.group(1)
                        .replaceAll("^(最近|近|之前|历史|历史消息|聊天记录|本群|群里|当前|有关|关于|和|与|中|\\d{1,4}\\s*(分钟|分|小时|时|天|日)前)+", "")
                        .replaceAll("(整理|总结|汇总|输出|生成|写成|文档|ppt|PPT|分析)+$", "")
                        .trim();
                return cleaned.length() > 40 ? cleaned.substring(0, 40).trim() : cleaned;
            }
        }
        return "";
    }

    private String sanitizeSearchQuery(String value) {
        if (!hasText(value)) {
            return "";
        }
        String cleaned = value.trim()
                .replaceAll("^(前\\s*\\d{1,4}\\s*(分钟|分|小时|时|天|日)|\\d{1,4}\\s*(分钟|分|小时|时|天|日)前)+", "")
                .replaceAll("^(消息|聊天|记录|消息总结|聊天总结|对话总结)+", "")
                .replaceAll("(消息|聊天|记录|消息总结|聊天总结|对话总结)+$", "")
                .trim();
        if (cleaned.matches("^(前\\s*\\d{1,4}\\s*(分钟|分|小时|时|天|日)|\\d{1,4}\\s*(分钟|分|小时|时|天|日)前)$")) {
            return "";
        }
        return cleaned;
    }

    private String normalizeSearchTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{10}")) {
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(Long.parseLong(trimmed)), java.time.ZoneId.systemDefault())
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (trimmed.matches("\\d{13}")) {
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(trimmed)), java.time.ZoneId.systemDefault())
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        try {
            return OffsetDateTime.ofInstant(java.time.Instant.parse(trimmed), java.time.ZoneId.systemDefault())
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(trimmed)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignoredAgain) {
                return "";
            }
        }
    }

    private String buildSummary(List<String> messages, List<String> docs, Set<String> sourceRefs) {
        List<String> parts = new ArrayList<>();
        if (!messages.isEmpty()) {
            parts.add("已读取 " + messages.size() + " 条聊天记录");
        }
        if (!docs.isEmpty()) {
            parts.add("已读取 " + docs.size() + " 份文档摘录");
        }
        if (!sourceRefs.isEmpty()) {
            parts.add("来源：" + String.join(", ", sourceRefs));
        }
        return String.join("；", parts);
    }

    private String buildNoContentQuestion(
            ContextAcquisitionPlan plan,
            WorkspaceContext workspaceContext,
            List<String> failures
    ) {
        boolean hasFailure = failures != null && failures.stream().anyMatch(value -> value != null && !value.isBlank());
        if (hasFailure && failures.stream().anyMatch(value -> value != null && value.contains("时间范围"))) {
            return "我识别到了时间条件，但还不能稳定换算成可查询的时间窗。你可以补充具体开始和结束时间吗？";
        }
        if (hasFailure) {
            return "我尝试读取你指定的材料时遇到问题。你可以确认一下机器人是否有权限，或直接把要整理的消息/文档链接发给我吗？";
        }
        if (isCurrentPrivateConversationWithoutResults(plan, workspaceContext)) {
            return "我查了当前这个私聊会话，在你指定的时间范围里没有找到可用消息。如果你是指某个群里的讨论，请直接在那个群里 @ 我，或者告诉我群和时间范围。";
        }
        return "我按你给的条件查了一遍，但没有找到符合条件的消息。你想扩大时间范围、换个关键词，还是直接贴几条要总结的消息给我？";
    }

    private boolean isCurrentPrivateConversationWithoutResults(
            ContextAcquisitionPlan plan,
            WorkspaceContext workspaceContext
    ) {
        if (workspaceContext == null || !"p2p".equalsIgnoreCase(firstNonBlank(workspaceContext.getChatType()))) {
            return false;
        }
        if (plan == null || plan.getSources() == null) {
            return false;
        }
        return plan.getSources().stream().anyMatch(source -> source != null
                && source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH
                && firstNonBlank(source.getChatId()).equals(firstNonBlank(workspaceContext.getChatId())));
    }

    private int normalizeLimit(Integer requested, int configuredMax) {
        int max = Math.max(1, configuredMax);
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    private int configuredMaxImMessages() {
        if (plannerProperties == null || plannerProperties.getContextCollection() == null) {
            return 30;
        }
        return Math.max(1, plannerProperties.getContextCollection().getMaxImMessages());
    }

    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String renderMessage(LarkMessageSearchItem item) {
        String sender = firstNonBlank(item.senderName(), item.senderId(), "未知成员");
        return sender + "：" + truncate(item.content(), 800);
    }

    private Set<String> selectedMessageIds(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || workspaceContext.getSelectedMessageIds() == null) {
            return Set.of();
        }
        return workspaceContext.getSelectedMessageIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private boolean containsUserInstruction(String content, String rawInstruction) {
        String normalizedContent = normalize(content);
        String normalizedInstruction = normalize(rawInstruction);
        return !normalizedInstruction.isBlank() && normalizedContent.contains(normalizedInstruction);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String readableSourceName(ContextSourceRequest source) {
        return source == null || source.getSourceType() == null ? "上下文" : source.getSourceType().name();
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知错误" : truncate(message, 160);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TimeRange(String start, String end) {
    }
}
