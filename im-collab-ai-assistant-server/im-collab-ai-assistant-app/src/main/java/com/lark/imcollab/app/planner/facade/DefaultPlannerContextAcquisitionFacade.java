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
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryTool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class DefaultPlannerContextAcquisitionFacade implements PlannerContextAcquisitionFacade {

    private final LarkMessageHistoryTool messageHistoryTool;
    private final LarkDocTool docTool;
    private final PlannerProperties plannerProperties;

    public DefaultPlannerContextAcquisitionFacade(
            LarkMessageHistoryTool messageHistoryTool,
            LarkDocTool docTool,
            PlannerProperties plannerProperties
    ) {
        this.messageHistoryTool = messageHistoryTool;
        this.docTool = docTool;
        this.plannerProperties = plannerProperties;
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
                    collectIm(source, workspaceContext, rawInstruction, selectedMessages, selectedMessageIds, sourceRefs);
                } else if (source.getSourceType() == ContextSourceTypeEnum.LARK_DOC) {
                    collectDocs(source, workspaceContext, docFragments, sourceRefs);
                }
            } catch (RuntimeException exception) {
                failures.add(readableSourceName(source) + "读取失败：" + safeMessage(exception));
            }
        }

        boolean hasContent = !selectedMessages.isEmpty() || !docFragments.isEmpty();
        String summary = buildSummary(selectedMessages, docFragments, sourceRefs);
        return ContextAcquisitionResult.builder()
                .success(hasContent)
                .sufficient(hasContent)
                .contextSummary(summary)
                .selectedMessages(selectedMessages)
                .selectedMessageIds(selectedMessageIds)
                .docFragments(docFragments)
                .sourceRefs(new ArrayList<>(sourceRefs))
                .message(hasContent ? summary : firstNonBlank(String.join("；", failures), "没有读取到可用上下文。"))
                .build();
    }

    private void collectIm(
            ContextSourceRequest source,
            WorkspaceContext workspaceContext,
            String rawInstruction,
            List<String> selectedMessages,
            List<String> selectedMessageIds,
            Set<String> sourceRefs
    ) {
        String threadId = firstNonBlank(source.getThreadId(), workspaceContext == null ? null : workspaceContext.getThreadId());
        String chatId = firstNonBlank(source.getChatId(), workspaceContext == null ? null : workspaceContext.getChatId());
        String containerId = firstNonBlank(threadId, chatId);
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        String containerIdType = threadId == null || threadId.isBlank() ? "chat" : "thread";
        TimeRange range = resolveTimeRange(firstNonBlank(source.getTimeRange(), workspaceContext == null ? null : workspaceContext.getTimeRange()));
        int limit = normalizeLimit(source.getLimit(), plannerProperties.getContextCollection().getMaxImMessages());
        LarkMessageHistoryResponse response = messageHistoryTool.fetchHistory(
                containerIdType,
                containerId,
                range.start(),
                range.end(),
                "ByCreateTimeAsc",
                limit,
                null
        );
        if (response == null || response.items() == null) {
            return;
        }
        sourceRefs.add("im:" + containerIdType + ":" + containerId);
        for (LarkMessageHistoryItem item : response.items()) {
            if (item == null || item.deleted() || item.content() == null || item.content().isBlank()) {
                continue;
            }
            if ("app".equalsIgnoreCase(item.senderType()) || sameText(item.content(), rawInstruction)) {
                continue;
            }
            selectedMessages.add(renderMessage(item));
            if (item.messageId() != null && !item.messageId().isBlank()) {
                selectedMessageIds.add(item.messageId());
            }
        }
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

    private TimeRange resolveTimeRange(String timeRange) {
        if (timeRange != null && !timeRange.isBlank()) {
            String[] parts = timeRange.split("[/,，~至到]+");
            if (parts.length >= 2) {
                String start = normalizeEpochSeconds(parts[0]);
                String end = normalizeEpochSeconds(parts[1]);
                if (!start.isBlank() && !end.isBlank()) {
                    return new TimeRange(start, end);
                }
            }
        }
        Instant end = Instant.now();
        Instant start = end.minusSeconds(Math.max(1, plannerProperties.getContextCollection().getDefaultLookbackMinutes()) * 60L);
        return new TimeRange(String.valueOf(start.getEpochSecond()), String.valueOf(end.getEpochSecond()));
    }

    private String normalizeEpochSeconds(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{10}")) {
            return trimmed;
        }
        if (trimmed.matches("\\d{13}")) {
            return trimmed.substring(0, 10);
        }
        try {
            return String.valueOf(Instant.parse(trimmed).getEpochSecond());
        } catch (DateTimeParseException ignored) {
            return "";
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

    private int normalizeLimit(Integer requested, int configuredMax) {
        int max = Math.max(1, configuredMax);
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    private String renderMessage(LarkMessageHistoryItem item) {
        String sender = firstNonBlank(item.senderName(), item.senderId(), "未知成员");
        return sender + "：" + truncate(item.content(), 800);
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
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

    private record TimeRange(String start, String end) {
    }
}
