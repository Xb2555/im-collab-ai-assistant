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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
        String query = firstNonBlank(source.getQuery(), extractKeywordFromSelection(source.getSelectionInstruction()));
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        boolean timeConstrained = hasTimeConstraint(source, workspaceContext, rawInstruction);
        TimeRange range = timeConstrained
                ? resolveTimeRange(source, workspaceContext, rawInstruction)
                : null;
        if (timeConstrained && range == null) {
            throw new IllegalStateException("时间范围无法解析，请提供明确的开始和结束时间。");
        }
        int pageSize = normalizeLimit(firstNonNull(source.getPageSize(), source.getLimit()), 50);
        int pageLimit = firstNonNull(source.getPageLimit(), hasText(query) ? 5 : 1);
        LarkMessageSearchResult result = messageSearchTool.searchMessages(
                query,
                chatId,
                range == null ? null : range.start(),
                range == null ? null : range.end(),
                pageSize,
                pageLimit
        );
        if (result == null || result.items() == null || result.items().isEmpty()) {
            return;
        }
        sourceRefs.add(buildImSearchSourceRef(chatId, query, range));
        List<LarkMessageSearchItem> candidates = result.items().stream()
                .filter(item -> item != null
                        && !item.deleted()
                        && item.content() != null
                        && !item.content().isBlank()
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
        int maxMessages = normalizeLimit(source.getLimit(), configuredMaxImMessages());
        for (LarkMessageSearchItem item : candidates.stream().limit(maxMessages).toList()) {
            selectedMessages.add(renderMessage(item));
            if (item.messageId() != null && !item.messageId().isBlank()) {
                selectedMessageIds.add(item.messageId());
            }
        }
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

    private TimeRange resolveTimeRange(ContextSourceRequest source, WorkspaceContext workspaceContext, String rawInstruction) {
        String sourceRange = source == null ? "" : firstNonBlank(joinRange(source.getStartTime(), source.getEndTime()), source.getTimeRange());
        String timeRange = firstNonBlank(sourceRange, workspaceContext == null ? null : workspaceContext.getTimeRange(), rawInstruction);
        TimeRange relative = resolveRelativeTimeRange(timeRange);
        if (relative != null) {
            return relative;
        }
        if (timeRange != null && !timeRange.isBlank()) {
            String[] parts = timeRange.split("[/,，~至到]+");
            if (parts.length >= 2) {
                String start = normalizeSearchTime(parts[0]);
                String end = normalizeSearchTime(parts[1]);
                if (!start.isBlank() && !end.isBlank()) {
                    return new TimeRange(start, end);
                }
            }
        }
        return null;
    }

    private TimeRange resolveRelativeTimeRange(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalize(text);
        Matcher pointMatcher = Pattern.compile("(\\d{1,4})\\s*(分钟|分|小时|时|天|日)前").matcher(text);
        if (pointMatcher.find()) {
            long amount = Long.parseLong(pointMatcher.group(1));
            long seconds = toSeconds(amount, pointMatcher.group(2));
            Instant center = Instant.now().minusSeconds(seconds);
            long radius = Math.min(Math.max(300L, seconds / 10), 1800L);
            return new TimeRange(
                    formatSearchTime(center.minusSeconds(radius)),
                    formatSearchTime(center.plusSeconds(radius))
            );
        }
        Matcher matcher = Pattern.compile("(最近|近)\\s*(\\d{1,4})\\s*(分钟|分|小时|时|天|日)").matcher(text);
        if (!matcher.find()) {
            if (normalized.matches(".*(刚才|刚刚|前面|上面|这段对话|当前讨论).*")) {
            Instant end = Instant.now();
            Instant start = end.minusSeconds(Math.max(1, plannerProperties.getContextCollection().getDefaultLookbackMinutes()) * 60L);
                return new TimeRange(formatSearchTime(start), formatSearchTime(end));
            }
            return null;
        }
        long amount = Long.parseLong(matcher.group(2));
        long seconds = toSeconds(amount, matcher.group(3));
        Instant end = Instant.now();
        Instant start = end.minusSeconds(Math.max(60L, seconds));
        return new TimeRange(formatSearchTime(start), formatSearchTime(end));
    }

    private long toSeconds(long amount, String unit) {
        if ("分钟".equals(unit) || "分".equals(unit)) {
            return amount * 60L;
        }
        if ("小时".equals(unit) || "时".equals(unit)) {
            return amount * 3600L;
        }
        return amount * 86400L;
    }

    private boolean hasTimeConstraint(ContextSourceRequest source, WorkspaceContext workspaceContext, String rawInstruction) {
        return hasText(source == null ? null : source.getTimeRange())
                || hasText(source == null ? null : source.getStartTime())
                || hasText(source == null ? null : source.getEndTime())
                || hasText(workspaceContext == null ? null : workspaceContext.getTimeRange())
                || hasRelativeTimeReference(rawInstruction);
    }

    private boolean hasRelativeTimeReference(String text) {
        if (!hasText(text)) {
            return false;
        }
        String normalized = normalize(text);
        return normalized.matches(".*(刚才|刚刚|前面|上面|这段对话|当前讨论).*")
                || Pattern.compile("(最近|近)\\s*\\d{1,4}\\s*(分钟|分|小时|时|天|日)").matcher(text).find()
                || Pattern.compile("\\d{1,4}\\s*(分钟|分|小时|时|天|日)前").matcher(text).find();
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

    private String joinRange(String start, String end) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return "";
        }
        return start.trim() + "/" + end.trim();
    }

    private String normalizeSearchTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{10}")) {
            return formatSearchTime(Instant.ofEpochSecond(Long.parseLong(trimmed)));
        }
        if (trimmed.matches("\\d{13}")) {
            return formatSearchTime(Instant.ofEpochMilli(Long.parseLong(trimmed)));
        }
        try {
            return formatSearchTime(Instant.parse(trimmed));
        } catch (DateTimeParseException ignored) {
            try {
                return formatSearchTime(OffsetDateTime.parse(trimmed).toInstant());
            } catch (DateTimeParseException ignoredAgain) {
                return "";
            }
        }
    }

    private String formatSearchTime(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));
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
