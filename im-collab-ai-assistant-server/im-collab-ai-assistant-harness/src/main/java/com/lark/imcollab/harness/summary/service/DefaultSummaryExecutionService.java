package com.lark.imcollab.harness.summary.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Step;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DefaultSummaryExecutionService implements SummaryExecutionService {

    private static final int MAX_CONTEXT_CHARS = 6000;
    private static final int MAX_ARTIFACT_CHARS = 3000;

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final StepRepository stepRepository;
    private final TaskEventRepository eventRepository;
    private final DocumentExecutionSupport executionSupport;
    private final ChatModel chatModel;

    public DefaultSummaryExecutionService(
            TaskRepository taskRepository,
            ArtifactRepository artifactRepository,
            StepRepository stepRepository,
            TaskEventRepository eventRepository,
            DocumentExecutionSupport executionSupport,
            ChatModel chatModel
    ) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.stepRepository = stepRepository;
        this.eventRepository = eventRepository;
        this.executionSupport = executionSupport;
        this.chatModel = chatModel;
    }

    @Override
    public void execute(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        String stepId = executionSupport.findSummaryStepId(taskId)
                .orElseGet(() -> executionSupport.subtaskId(taskId, "generate_summary"));
        List<Artifact> artifacts = artifactRepository.findByTaskId(taskId);
        try {
            executionSupport.markSummaryStepRunning(taskId);
            executionSupport.publishEvent(taskId, stepId, TaskEventType.STEP_STARTED, "开始生成任务上下文摘要");
            String summary = sanitizeForIm(chatModel.call(buildPrompt(
                    task,
                    stepRepository.findByTaskId(taskId),
                    eventRepository.findByTaskId(taskId),
                    artifacts
            )));
            if (summary.isBlank()) {
                summary = fallbackSummary(task, artifacts);
            }
            summary = appendShareableLinks(summary, artifacts);
            executionSupport.saveArtifact(taskId, stepId, ArtifactType.SUMMARY, summaryTitle(task), summary, null);
            executionSupport.markSummaryStepCompleted(taskId, summary);
            executionSupport.publishEvent(taskId, stepId, TaskEventType.ARTIFACT_CREATED, "已生成任务上下文摘要");
            executionSupport.publishEvent(taskId, stepId, TaskEventType.STEP_COMPLETED, "任务上下文摘要已完成");
        } catch (RuntimeException exception) {
            String reason = readableReason(exception);
            executionSupport.markSummaryStepFailed(taskId, reason);
            executionSupport.publishEvent(taskId, stepId, TaskEventType.TASK_FAILED, reason);
            throw exception;
        }
    }

    private String buildPrompt(Task task, List<Step> steps, List<TaskEvent> events, List<Artifact> artifacts) {
        ExecutionContract contract = task.getExecutionContract();
        WorkspaceContext context = contract == null ? null : contract.getSourceScope();
        SummaryToneProfile toneProfile = resolveToneProfile(task, contract, context);
        return """
                你是 SUMMARY Agent，负责把整个任务上下文整理成可直接发到 IM 聊天框里的工作汇报文本。

                输出要求：
                1. 只输出最终摘要正文，不要解释你的思考过程。
                2. 不要使用 Markdown 语法：不要 #/##/### 标题，不要 - 或 * 列表符号，不要 **加粗**，不要代码块，不要表格。
                3. 默认写成 1 到 2 段、可直接转发的工作汇报口吻，像人在群里或私聊里同步进展，不要写成“任务记录”“执行日志”或方案说明书。
                4. 不要使用“一、二、三”这类分节编号，不要逐条罗列“第一步/第二步/第三步”。
                5. 开头优先直接同步结果和进展，例如“%s”这类口吻，但不要机械照抄模板。
                6. 必须基于下方任务上下文和已有产物，不要编造未出现的事实。
                7. 如果上下文里包含已选消息或已拉取消息，优先提炼事实、结论、风险、待办，而不是复述原文。
                8. 如果任务已产生执行步骤、事件或产物，要优先说明已完成的交付物、补充修改和当前可用于汇报/转发的结果。
                9. 如果已有产物里包含可访问链接，摘要正文里必须自然带上这些链接，便于用户直接转发；可在结尾自然写成“文档：...；PPT：...”。
                10. 语气偏好：%s。

                任务信息：
                任务ID：%s
                当前状态：%s
                原始指令：%s
                澄清后目标：%s
                任务摘要：%s
                约束：%s
                受众：%s
                时间范围：%s

                工作空间上下文：
                %s

                计划步骤与进度：
                %s

                运行事件：
                %s

                已有产物：
                %s
                """.formatted(
                toneProfile.openingHint(),
                toneProfile.toneInstruction(),
                safe(task.getTaskId()),
                task.getStatus() == null ? "" : task.getStatus().name(),
                safe(task.getRawInstruction()),
                safe(task.getClarifiedInstruction()),
                safe(task.getTaskBrief()),
                contract == null || contract.getConstraints() == null ? "" : String.join("；", contract.getConstraints()),
                safe(contract == null ? null : contract.getAudience()),
                safe(contract == null ? null : contract.getTimeScope()),
                summarizeWorkspaceContext(context, contract),
                summarizeSteps(steps),
                summarizeEvents(events),
                summarizeArtifacts(artifacts)
        );
    }

    private String summarizeWorkspaceContext(WorkspaceContext context, ExecutionContract contract) {
        StringBuilder builder = new StringBuilder();
        if (contract != null && contract.getDomainContext() != null && !contract.getDomainContext().isBlank()) {
            builder.append("领域上下文：").append(limit(contract.getDomainContext(), MAX_CONTEXT_CHARS)).append("\n");
        }
        if (contract != null && contract.getContextRefs() != null && !contract.getContextRefs().isEmpty()) {
            builder.append("上下文引用：").append(String.join("；", contract.getContextRefs())).append("\n");
        }
        if (context == null) {
            return builder.isEmpty() ? "无显式工作空间上下文" : builder.toString();
        }
        if (context.getSelectedMessages() != null && !context.getSelectedMessages().isEmpty()) {
            builder.append("已选/已拉取消息：\n");
            for (String message : context.getSelectedMessages()) {
                if (message != null && !message.isBlank()) {
                    builder.append("- ").append(limit(message, 500)).append("\n");
                }
            }
        }
        if (context.getDocRefs() != null && !context.getDocRefs().isEmpty()) {
            builder.append("文档引用：").append(String.join("；", context.getDocRefs())).append("\n");
        }
        if (context.getTimeRange() != null && !context.getTimeRange().isBlank()) {
            builder.append("时间范围：").append(context.getTimeRange()).append("\n");
        }
        if (context.getChatId() != null && !context.getChatId().isBlank()) {
            builder.append("会话：").append(context.getChatId()).append("\n");
        }
        return builder.isEmpty() ? "无显式工作空间上下文" : limit(builder.toString(), MAX_CONTEXT_CHARS);
    }

    private String summarizeSteps(List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return "暂无计划步骤";
        }
        return steps.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Step::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(step -> {
                    StringBuilder builder = new StringBuilder();
                    builder.append(firstNonBlank(step.getName(), step.getStepId(), "未命名步骤"));
                    if (step.getStatus() != null) {
                        builder.append("，状态：").append(step.getStatus().name());
                    }
                    if (step.getRetryCount() > 0) {
                        builder.append("，重试次数：").append(step.getRetryCount());
                    }
                    if (step.getInput() != null && !step.getInput().isBlank()) {
                        builder.append("，输入：").append(limit(step.getInput(), 240));
                    }
                    if (step.getOutput() != null && !step.getOutput().isBlank()) {
                        builder.append("，输出：").append(limit(step.getOutput(), 240));
                    }
                    if (step.getFailReason() != null && !step.getFailReason().isBlank()) {
                        builder.append("，失败原因：").append(limit(step.getFailReason(), 240));
                    }
                    return builder.toString();
                })
                .collect(Collectors.joining("\n", "", ""));
    }

    private String summarizeEvents(List<TaskEvent> events) {
        if (events == null || events.isEmpty()) {
            return "暂无运行事件";
        }
        return events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TaskEvent::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .skip(Math.max(0, events.size() - 12L))
                .map(event -> {
                    String type = event.getType() == null ? "UNKNOWN" : event.getType().name();
                    String payload = safe(event.getPayload());
                    String step = safe(event.getStepId());
                    return (step.isBlank() ? type : type + "@" + step)
                            + (payload.isBlank() ? "" : "：" + limit(payload, 240));
                })
                .collect(Collectors.joining("\n", "", ""));
    }

    private String summarizeArtifacts(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return "暂无已有产物";
        }
        return artifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> artifact.getType() != ArtifactType.DOC_OUTLINE
                        && artifact.getType() != ArtifactType.DOC_DRAFT)
                .map(artifact -> {
                    String title = safe(artifact.getTitle()).isBlank() ? artifact.getType().name() : artifact.getTitle();
                    String content = firstNonBlank(artifact.getContent(), artifact.getExternalUrl(), artifact.getDocumentId());
                    return title + "：" + limit(content, 800);
                })
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n", "", ""));
    }

    private String sanitizeForIm(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value
                .replace("**", "")
                .replace("__", "")
                .replace("`", "");
        cleaned = cleaned.lines()
                .map(line -> {
                    String normalized = line
                            .replaceFirst("^\\s*[一二三四五六七八九十]+、\\s*", "")
                            .replaceFirst("^\\s*#+\\s*", "")
                            .replaceFirst("^\\s*[-*+]\\s+", "")
                            .replaceFirst("^\\s*#+\\s*", "")
                            .replaceFirst("^\\s*[-*+]\\s+", "");
                    return normalized.trim();
                })
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return limit(cleaned.replaceAll("\\n{3,}", "\n\n").trim(), MAX_ARTIFACT_CHARS);
    }

    private String fallbackSummary(Task task, List<Artifact> artifacts) {
        SummaryToneProfile toneProfile = resolveToneProfile(
                task,
                task == null ? null : task.getExecutionContract(),
                task == null || task.getExecutionContract() == null ? null : task.getExecutionContract().getSourceScope()
        );
        StringBuilder builder = new StringBuilder();
        builder.append(toneProfile.openingHint())
                .append("本次任务已完成，");
        String artifactSummary = summarizeArtifactTitles(artifacts);
        if (!artifactSummary.isBlank()) {
            builder.append("目前已产出").append(artifactSummary).append("，");
        } else {
            builder.append("已完成摘要整理，");
        }
        builder.append("核心目标围绕")
                .append(firstNonBlank(task == null ? null : task.getClarifiedInstruction(),
                        task == null ? null : task.getRawInstruction(),
                        "当前任务内容"))
                .append("展开，当前结果已可直接用于后续同步或汇报。");
        return builder.toString();
    }

    private String summarizeArtifactTitles(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return "";
        }
        List<String> titles = artifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> artifact.getType() != ArtifactType.DOC_DRAFT
                        && artifact.getType() != ArtifactType.DOC_OUTLINE
                        && artifact.getType() != ArtifactType.SUMMARY)
                .map(artifact -> firstNonBlank(artifact.getTitle(), artifact.getType() == null ? null : artifact.getType().name()))
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(3)
                .toList();
        if (titles.isEmpty()) {
            return "";
        }
        if (titles.size() == 1) {
            return titles.get(0);
        }
        if (titles.size() == 2) {
            return titles.get(0) + "和" + titles.get(1);
        }
        return titles.get(0) + "、" + titles.get(1) + "等产物";
    }

    private SummaryToneProfile resolveToneProfile(Task task, ExecutionContract contract, WorkspaceContext context) {
        String combined = String.join("\n",
                safe(task == null ? null : task.getRawInstruction()),
                safe(task == null ? null : task.getClarifiedInstruction()),
                safe(task == null ? null : task.getTaskBrief()),
                safe(contract == null ? null : contract.getAudience()),
                safe(context == null ? null : context.getChatId()));
        String lower = combined.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "老板", "领导", "管理层", "汇报", "汇总给老板")) {
            return new SummaryToneProfile("跟您同步一下当前进展：", "偏正式、简洁、面向老板汇报");
        }
        if (containsAny(lower, "群", "群里", "工作群", "同步", "同事", "团队")) {
            return new SummaryToneProfile("各位同步一下当前进展：", "偏工作群同步，简洁自然，适合直接转发");
        }
        return new SummaryToneProfile("同步一下当前进展：", "偏工作汇报，简洁自然，适合直接发送");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String appendShareableLinks(String summary, List<Artifact> artifacts) {
        List<String> links = artifacts == null ? List.of() : artifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> artifact.getExternalUrl() != null && !artifact.getExternalUrl().isBlank())
                .map(artifact -> firstNonBlank(artifact.getTitle(), artifact.getType() == null ? null : artifact.getType().name())
                        + "："
                        + artifact.getExternalUrl().trim())
                .distinct()
                .toList();
        if (links.isEmpty()) {
            return summary;
        }
        String linkBlock = "相关产物链接：" + String.join("；", links);
        if (summary != null && links.stream().allMatch(link -> summary.contains(link.substring(link.indexOf('：') + 1)))) {
            return summary;
        }
        if (summary == null || summary.isBlank()) {
            return linkBlock;
        }
        return limit(summary.trim() + "\n\n" + linkBlock, MAX_ARTIFACT_CHARS);
    }

    private String summaryTitle(Task task) {
        String brief = firstNonBlank(task.getTaskBrief(), task.getClarifiedInstruction(), task.getRawInstruction(), "任务上下文摘要");
        return limit(sanitizeTitle(brief), 40);
    }

    private String sanitizeTitle(String value) {
        String title = safe(value).replaceAll("[\\r\\n]+", " ").trim();
        if (title.endsWith("。")) {
            title = title.substring(0, title.length() - 1);
        }
        return title.isBlank() ? "任务上下文摘要" : title;
    }

    private String readableReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "摘要生成失败，请稍后重试。";
        }
        return limit(message, 220);
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

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private record SummaryToneProfile(String openingHint, String toneInstruction) {
    }
}
