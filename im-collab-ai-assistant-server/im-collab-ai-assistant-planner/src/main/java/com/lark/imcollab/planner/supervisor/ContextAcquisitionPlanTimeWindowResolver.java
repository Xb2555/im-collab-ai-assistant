package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ContextAcquisitionPlanTimeWindowResolver {

    private static final long MAX_LOOKBACK_DAYS = 30L;

    private final ReactAgent timeWindowResolutionAgent;
    private final ObjectMapper objectMapper;

    @Autowired
    public ContextAcquisitionPlanTimeWindowResolver(
            @Qualifier("timeWindowResolutionAgent") ReactAgent timeWindowResolutionAgent,
            ObjectMapper objectMapper
    ) {
        this.timeWindowResolutionAgent = timeWindowResolutionAgent;
        this.objectMapper = objectMapper;
    }

    public ContextAcquisitionPlan ensureExecutable(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan plan
    ) {
        if (plan == null || !plan.isNeedCollection()) {
            return plan;
        }
        ContextAcquisitionPlan normalizedPlan = normalizeMinutePrecisionWindows(plan);
        if (!hasInvalidImSearchTimeRange(normalizedPlan)) {
            return enforceLookbackLimit(taskId, normalizedPlan);
        }
        log.info("IM_CONTEXT_TIMEWINDOW_INVALID taskId={} sources={}",
                taskId,
                summarizeImSources(normalizedPlan));
        Optional<ContextAcquisitionPlan> locallyRepaired = tryLocalStructuredWindowRepair(rawInstruction, normalizedPlan);
        if (locallyRepaired.isPresent()) {
            ContextAcquisitionPlan repaired = normalizeMinutePrecisionWindows(locallyRepaired.get());
            log.info("IM_CONTEXT_TIMEWINDOW_RESOLVED_LOCAL taskId={} sources={}",
                    taskId,
                    summarizeImSources(repaired));
            return enforceLookbackLimit(taskId, repaired);
        }
        Optional<ContextAcquisitionPlan> repaired = invokeTimeWindowResolutionAgent(
                taskId,
                rawInstruction,
                workspaceContext,
                normalizedPlan
        );
        if (repaired.isPresent()) {
            ContextAcquisitionPlan normalized = normalizeMinutePrecisionWindows(repaired.get());
            log.info("IM_CONTEXT_TIMEWINDOW_RESOLVED taskId={} sources={}",
                    taskId,
                    summarizeImSources(normalized));
            return enforceLookbackLimit(taskId, normalized);
        }
        log.info("IM_CONTEXT_TIMEWINDOW_AMBIGUOUS taskId={} sources={}",
                taskId,
                summarizeImSources(normalizedPlan));
        return ContextAcquisitionPlan.builder()
                .needCollection(false)
                .sources(List.of())
                .reason("ambiguous time range")
                .clarificationQuestion("请补充具体开始和结束时间。")
                .build();
    }

    boolean hasInvalidImSearchTimeRange(ContextAcquisitionPlan plan) {
        if (plan == null || !plan.isNeedCollection() || plan.getSources() == null) {
            return false;
        }
        return plan.getSources().stream().anyMatch(source -> source != null
                && source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH
                && hasAnyTimeConstraint(source)
                && !hasValidStructuredTimeWindow(source));
    }

    private boolean hasAnyTimeConstraint(ContextSourceRequest source) {
        return source != null
                && (hasText(source.getTimeRange())
                || hasText(source.getStartTime())
                || hasText(source.getEndTime()));
    }

    private boolean hasValidStructuredTimeWindow(ContextSourceRequest source) {
        if (source == null) {
            return false;
        }
        Optional<java.time.OffsetDateTime> start = parseIsoOffsetDateTime(source.getStartTime());
        Optional<java.time.OffsetDateTime> end = parseIsoOffsetDateTime(source.getEndTime());
        return start.isPresent() && end.isPresent() && !start.get().isAfter(end.get());
    }

    private Optional<java.time.OffsetDateTime> parseIsoOffsetDateTime(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(java.time.OffsetDateTime.parse(value.trim()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<ContextAcquisitionPlan> invokeTimeWindowResolutionAgent(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan invalidPlan
    ) {
        if (timeWindowResolutionAgent == null) {
            return Optional.empty();
        }
        try {
            Optional<OverAllState> state = timeWindowResolutionAgent.invoke(
                    buildTimeRangeRepairPrompt(rawInstruction, workspaceContext, invalidPlan),
                    RunnableConfig.builder().threadId(taskId + ":planner:time-window-resolution").build()
            );
            if (state.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> data = state.get().data();
            Object structured = data.get("messages") == null ? data.get("message") : data.get("messages");
            Optional<ContextAcquisitionPlan> plan = extractStructured(structured);
            if (plan.isEmpty()) {
                log.info("IM_CONTEXT_TIMEWINDOW_AGENT_UNPARSEABLE taskId={} payloadType={}",
                        taskId,
                        structured == null ? "null" : structured.getClass().getName());
                return Optional.empty();
            }
            if (!hasInvalidImSearchTimeRange(plan.get())) {
                return plan;
            }
            log.info("IM_CONTEXT_TIMEWINDOW_AGENT_INVALID_RESULT taskId={} sources={}",
                    taskId,
                    summarizeImSources(plan.get()));
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<ContextAcquisitionPlan> extractStructured(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof ContextAcquisitionPlan plan) {
            return Optional.of(plan);
        }
        if (value instanceof Map<?, ?> map) {
            try {
                return Optional.of(objectMapper.convertValue(map, ContextAcquisitionPlan.class));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
            return parseText(assistantMessage.getText());
        }
        if (value instanceof org.springframework.ai.chat.messages.Message message) {
            return parseText(message.getText());
        }
        if (value instanceof CharSequence text) {
            return parseText(text.toString());
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new java.util.ArrayList<>();
            iterable.forEach(values::add);
            java.util.Collections.reverse(values);
            for (Object item : values) {
                Optional<ContextAcquisitionPlan> parsed = extractStructured(item);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ContextAcquisitionPlan> parseText(String text) {
        if (!hasText(text)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(extractJson(text), ContextAcquisitionPlan.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String extractJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildTimeRangeRepairPrompt(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan invalidPlan
    ) {
        return """
                You are a strict ContextAcquisitionPlan JSON repairer for IM message time windows.
                The previous plan is not executable because one or more IM_MESSAGE_SEARCH sources have missing or invalid startTime/endTime.

                Return the corrected ContextAcquisitionPlan JSON only.
                Preserve sourceType, chatId, threadId, query, selectionInstruction, limit, pageSize, and pageLimit.
                Preserve the user's original time phrase in timeRange.
                For every IM_MESSAGE_SEARCH source with any time condition, fill startTime and endTime as ISO_OFFSET_DATE_TIME strings.
                startTime/endTime are the execution parameters; timeRange is only for traceability.
                If timeRange contains the whole user sentence, extra explanation, or multiple lines, ignore the unrelated narrative and extract only the actual time expression from User instruction and selectionInstruction.
                Prefer the latest User instruction over stale or noisy timeRange text inside the invalid JSON.

                Current time: %s
                User instruction: %s
                Workspace chatId: %s
                Workspace threadId: %s
                Workspace chatType: %s
                Current timezone: %s

                Resolve both relative and absolute Chinese time expressions, including:
                - 5月7号凌晨2点到2点06分
                - 5月7日 14:00-14:30
                - 昨天 9 点到 10 点
                - 今天下午 3 点前后的讨论
                - 同一天省略结束日期的时间区间
                - 凌晨 / 上午 / 中午 / 下午 / 晚上
                - X点 / X点Y分
                - 没写年份、只有月日时，按 Current time 推断“最近一次且不晚于当前时间”的日期，例如当前时间是 2026-05-13，则 5月7号 => 2026-05-07；当前时间是 2026-01-02，则 12月30号 => 2025-12-30。

                Common relative time rules using Current time timezone:
                - 昨天下午: yesterday 12:00:00 to yesterday 18:00:00.
                - 昨天上午: yesterday 00:00:00 to yesterday 12:00:00.
                - 昨天: yesterday 00:00:00 to today 00:00:00.
                - 今天上午: today 00:00:00 to today 12:00:00.
                - 今天下午: today 12:00:00 to today 18:00:00.
                - 今天: today 00:00:00 to current time.
                - 最近N分钟/小时: current time minus N minutes/hours to current time.
                - N分钟前: a 10-minute window centered at N minutes before current time.

                Do not return a clarification for common resolvable expressions.
                If you can infer the exact calendar date and clock time, output it directly.
                If the resolved window would be older than 30 days before Current time, return {"needCollection":false,"sources":[],"reason":"message window exceeds 30-day lookback","clarificationQuestion":"暂时无法获取超过30天的消息，您可手动把消息复制发给我。"}.
                If and only if the phrase is genuinely ambiguous, return {"needCollection":false,"sources":[],"reason":"ambiguous time range","clarificationQuestion":"请补充具体开始和结束时间。"}.

                Previous invalid JSON:
                %s
                """.formatted(
                currentTime(),
                rawInstruction == null ? "" : rawInstruction,
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getChatId()),
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getThreadId()),
                workspaceContext == null ? "" : nullToEmpty(workspaceContext.getChatType()),
                java.time.ZoneId.systemDefault(),
                safeJson(invalidPlan)
        );
    }

    private String currentTime() {
        return java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ContextAcquisitionPlan enforceLookbackLimit(String taskId, ContextAcquisitionPlan plan) {
        if (plan == null || !plan.isNeedCollection() || plan.getSources() == null) {
            return plan;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(MAX_LOOKBACK_DAYS);
        boolean exceedsLookback = plan.getSources().stream()
                .filter(source -> source != null && source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                .map(ContextSourceRequest::getEndTime)
                .filter(this::hasText)
                .map(this::parseIsoOffsetDateTime)
                .flatMap(Optional::stream)
                .anyMatch(end -> end.isBefore(cutoff));
        if (!exceedsLookback) {
            return plan;
        }
        log.info("IM_CONTEXT_TIMEWINDOW_TOO_OLD taskId={} cutoff={} sources={}",
                taskId,
                cutoff.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                summarizeImSources(plan));
        return ContextAcquisitionPlan.builder()
                .needCollection(false)
                .sources(List.of())
                .reason("message window exceeds 30-day lookback")
                .clarificationQuestion("暂时无法获取超过30天的消息，您可手动把消息复制发给我。")
                .build();
    }

    private ContextAcquisitionPlan normalizeMinutePrecisionWindows(ContextAcquisitionPlan plan) {
        if (plan == null || plan.getSources() == null || plan.getSources().isEmpty()) {
            return plan;
        }
        boolean changed = false;
        List<ContextSourceRequest> normalizedSources = new java.util.ArrayList<>();
        for (ContextSourceRequest source : plan.getSources()) {
            if (source == null || source.getSourceType() != ContextSourceTypeEnum.IM_MESSAGE_SEARCH) {
                normalizedSources.add(source);
                continue;
            }
            String normalizedEnd = normalizeInclusiveMinuteEnd(source.getTimeRange(), source.getEndTime());
            if (!hasText(normalizedEnd) || normalizedEnd.equals(source.getEndTime())) {
                normalizedSources.add(source);
                continue;
            }
            normalizedSources.add(ContextSourceRequest.builder()
                    .sourceType(source.getSourceType())
                    .chatId(source.getChatId())
                    .threadId(source.getThreadId())
                    .timeRange(source.getTimeRange())
                    .startTime(source.getStartTime())
                    .endTime(normalizedEnd)
                    .query(source.getQuery())
                    .docRefs(source.getDocRefs())
                    .selectionInstruction(source.getSelectionInstruction())
                    .limit(source.getLimit())
                    .pageSize(source.getPageSize())
                    .pageLimit(source.getPageLimit())
                    .build());
            changed = true;
        }
        if (!changed) {
            return plan;
        }
        return ContextAcquisitionPlan.builder()
                .needCollection(plan.isNeedCollection())
                .sources(normalizedSources)
                .reason(plan.getReason())
                .clarificationQuestion(plan.getClarificationQuestion())
                .build();
    }

    private String normalizeInclusiveMinuteEnd(String timeRange, String endTime) {
        if (!hasText(timeRange) || !hasText(endTime) || !shouldExpandInclusiveMinuteEnd(timeRange)) {
            return endTime;
        }
        Optional<OffsetDateTime> parsedEnd = parseIsoOffsetDateTime(endTime);
        if (parsedEnd.isEmpty()) {
            return endTime;
        }
        OffsetDateTime end = parsedEnd.get();
        if (end.getSecond() != 0 || end.getNano() != 0) {
            return endTime;
        }
        return end.withSecond(59).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private boolean shouldExpandInclusiveMinuteEnd(String timeRange) {
        String normalized = normalizeDashes(timeRange == null ? "" : timeRange.trim());
        if (normalized.isBlank() || normalized.contains("秒")) {
            return false;
        }
        Matcher colonTime = Pattern.compile("(\\d{1,2}:\\d{2})(?!:\\d{2})").matcher(normalized);
        if (colonTime.find()) {
            return true;
        }
        Matcher chineseMinuteTime = Pattern.compile("\\d{1,2}点\\d{1,2}分").matcher(normalized);
        return chineseMinuteTime.find();
    }

    private Optional<ContextAcquisitionPlan> tryLocalStructuredWindowRepair(String rawInstruction, ContextAcquisitionPlan plan) {
        if (plan == null || plan.getSources() == null || plan.getSources().isEmpty()) {
            return Optional.empty();
        }
        List<ContextSourceRequest> repairedSources = new java.util.ArrayList<>();
        boolean changed = false;
        for (ContextSourceRequest source : plan.getSources()) {
            if (source == null || source.getSourceType() != ContextSourceTypeEnum.IM_MESSAGE_SEARCH || !hasInvalidTimeWindow(source)) {
                repairedSources.add(source);
                continue;
            }
            if (shouldDeferStructuredTimeRangeToLlm(rawInstruction, source)) {
                return Optional.empty();
            }
            Optional<StructuredWindow> resolved = resolveStructuredWindowFromTimeRange(source.getTimeRange());
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            StructuredWindow window = resolved.get();
            repairedSources.add(ContextSourceRequest.builder()
                    .sourceType(source.getSourceType())
                    .chatId(source.getChatId())
                    .threadId(source.getThreadId())
                    .timeRange(source.getTimeRange())
                    .startTime(window.start())
                    .endTime(window.end())
                    .query(source.getQuery())
                    .docRefs(source.getDocRefs())
                    .selectionInstruction(source.getSelectionInstruction())
                    .limit(source.getLimit())
                    .pageSize(source.getPageSize())
                    .pageLimit(source.getPageLimit())
                    .build());
            changed = true;
        }
        if (!changed) {
            return Optional.empty();
        }
        ContextAcquisitionPlan repaired = ContextAcquisitionPlan.builder()
                .needCollection(plan.isNeedCollection())
                .sources(repairedSources)
                .reason(plan.getReason())
                .clarificationQuestion(plan.getClarificationQuestion())
                .build();
        return hasInvalidImSearchTimeRange(repaired) ? Optional.empty() : Optional.of(repaired);
    }

    private boolean shouldDeferStructuredTimeRangeToLlm(String rawInstruction, ContextSourceRequest source) {
        if (source == null || !hasText(source.getTimeRange()) || !looksLikeStructuredCalendarTimeRange(source.getTimeRange())) {
            return false;
        }
        String latestInstruction = firstNonBlank(source.getSelectionInstruction(), rawInstruction);
        return containsNaturalLanguageTimeCue(latestInstruction);
    }

    private boolean looksLikeStructuredCalendarTimeRange(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = normalizeDashes(value.trim());
        return normalized.matches("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:-|至|到)\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)$");
    }

    private boolean containsNaturalLanguageTimeCue(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        return normalized.contains("月")
                || normalized.contains("日")
                || normalized.contains("号")
                || normalized.contains("凌晨")
                || normalized.contains("上午")
                || normalized.contains("中午")
                || normalized.contains("下午")
                || normalized.contains("晚上")
                || normalized.contains("今天")
                || normalized.contains("昨天")
                || normalized.contains("前天")
                || normalized.matches(".*(最近\\d{1,4}(分钟|分|小时|时|天|日)|前\\d{1,4}(分钟|分|小时|时|天|日)|\\d{1,4}(分钟|分|小时|时|天|日)前).*");
    }

    private boolean hasInvalidTimeWindow(ContextSourceRequest source) {
        return source != null
                && hasAnyTimeConstraint(source)
                && !hasValidStructuredTimeWindow(source);
    }

    private Optional<StructuredWindow> resolveStructuredWindowFromTimeRange(String timeRange) {
        if (!hasText(timeRange)) {
            return Optional.empty();
        }
        String normalized = normalizeDashes(timeRange.trim());
        Matcher fullMatcher = Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*(?:-|至|到)\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)$"
        ).matcher(normalized);
        if (fullMatcher.matches()) {
            return buildWindow(fullMatcher.group(1), fullMatcher.group(2), fullMatcher.group(3), fullMatcher.group(4));
        }
        Matcher sameDayMatcher = Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*(?:-|至|到)\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)$"
        ).matcher(normalized);
        if (sameDayMatcher.matches()) {
            return buildWindow(sameDayMatcher.group(1), sameDayMatcher.group(2), sameDayMatcher.group(1), sameDayMatcher.group(3));
        }
        return Optional.empty();
    }

    private Optional<StructuredWindow> buildWindow(String startDate, String startTime, String endDate, String endTime) {
        try {
            LocalDate startLocalDate = LocalDate.parse(startDate);
            LocalDate endLocalDate = LocalDate.parse(endDate);
            LocalTime startLocalTime = parseLocalTime(startTime);
            LocalTime endLocalTime = parseLocalTime(endTime);
            OffsetDateTime start = startLocalDate.atTime(startLocalTime).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            OffsetDateTime end = endLocalDate.atTime(endLocalTime).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            if (start.isAfter(end)) {
                return Optional.empty();
            }
            return Optional.of(new StructuredWindow(
                    start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private LocalTime parseLocalTime(String value) {
        DateTimeFormatter formatterWithSeconds = DateTimeFormatter.ofPattern("H:mm:ss");
        DateTimeFormatter formatterWithoutSeconds = DateTimeFormatter.ofPattern("H:mm");
        return value != null && value.trim().split(":").length == 3
                ? LocalTime.parse(value.trim(), formatterWithSeconds)
                : LocalTime.parse(value.trim(), formatterWithoutSeconds);
    }

    private String normalizeDashes(String value) {
        return value == null ? "" : value.replace('—', '-').replace('－', '-');
    }

    private String summarizeImSources(ContextAcquisitionPlan plan) {
        if (plan == null || plan.getSources() == null) {
            return "[]";
        }
        return plan.getSources().stream()
                .filter(source -> source != null && source.getSourceType() == ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                .map(source -> "{chatId=%s,query=%s,timeRange=%s,start=%s,end=%s}".formatted(
                        nullToEmpty(source.getChatId()),
                        nullToEmpty(source.getQuery()),
                        nullToEmpty(source.getTimeRange()),
                        nullToEmpty(source.getStartTime()),
                        nullToEmpty(source.getEndTime())
                ))
                .toList()
                .toString();
    }

    private record StructuredWindow(String start, String end) {
    }
}
