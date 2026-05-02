package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ReadOnlyNodeService {

    private final PlannerSessionService sessionService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerRuntimeTool runtimeTool;
    private final ReactAgent runtimeStatusAgent;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public ReadOnlyNodeService(
            PlannerSessionService sessionService,
            PlannerConversationMemoryService memoryService,
            PlannerRuntimeTool runtimeTool,
            @Qualifier("runtimeStatusAgent") ReactAgent runtimeStatusAgent
    ) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.runtimeTool = runtimeTool;
        this.runtimeStatusAgent = runtimeStatusAgent;
    }

    public PlanTaskSession readOnly(String taskId, String userInput, PlannerSupervisorDecisionResult decision) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session == null) {
            return null;
        }
        if (!hasUsablePlan(session) && isStatusQuery(session)) {
            String reply = emptyTaskReply(session);
            TaskIntakeState intakeState = session.getIntakeState();
            if (intakeState != null) {
                intakeState.setAssistantReply(reply);
            }
            memoryService.appendAssistantTurn(session, reply);
            sessionService.saveWithoutVersionChange(session);
            return session;
        }
        if (decision != null && decision.action() == PlannerSupervisorAction.UNKNOWN && decision.needsClarification()) {
            String existingReply = session.getIntakeState() == null ? null : session.getIntakeState().getAssistantReply();
            String clarification = firstNonBlank(
                    existingReply,
                    decision.userFacingReply(),
                    decision.clarificationQuestion(),
                    "我先不动当前计划。你想看细节、调整步骤，还是推进执行？"
            );
            TaskIntakeState intakeState = session.getIntakeState();
            if (intakeState != null) {
                intakeState.setAssistantReply(clarification);
            }
            memoryService.appendAssistantTurn(session, clarification);
            sessionService.saveWithoutVersionChange(session);
            return session;
        }
        if (session.getIntakeState() != null && "PLAN".equalsIgnoreCase(session.getIntakeState().getReadOnlyView())) {
            String reply = fullPlanReply(session);
            TaskIntakeState intakeState = session.getIntakeState();
            intakeState.setAssistantReply(reply);
            memoryService.appendAssistantTurn(session, reply);
            sessionService.saveWithoutVersionChange(session);
            return session;
        }
        String reply = firstNonBlank(
                decision == null ? null : decision.userFacingReply(),
                generateReadOnlyReply(session, userInput)
        );
        if (hasText(reply)) {
            TaskIntakeState intakeState = session.getIntakeState();
            if (intakeState != null) {
                intakeState.setAssistantReply(reply);
            }
            memoryService.appendAssistantTurn(session, reply);
            sessionService.saveWithoutVersionChange(session);
        }
        return session;
    }

    private String generateReadOnlyReply(PlanTaskSession session, String userInput) {
        if (runtimeStatusAgent == null) {
            return null;
        }
        String taskId = session == null ? "unknown" : session.getTaskId();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(taskId + ":planner:runtime-status")
                .build();
        try {
            return CompletableFuture.supplyAsync(() -> {
                        try {
                            return runtimeStatusAgent.call(buildPrompt(session, userInput), config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(3, TimeUnit.SECONDS)
                    .get(4, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildPrompt(PlanTaskSession session, String userInput) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 Planner 的只读状态回复子 Agent。你只能根据下面的任务状态回答用户，不要改计划，不要执行任务。\n");
        builder.append("如果用户想看完整计划，请列出所有 plan cards 的标题、类型和描述。");
        builder.append("如果用户想看进度，请简短说明任务状态、当前/下一步、完成数量和产物数量。");
        builder.append("如果用户表达认可但没有要求执行，请自然确认会保留当前计划，避免固定模板。");
        builder.append("如果用户原话像是在新增、删除、修改计划，你也只能说明当前计划尚未变化，不能说已收到新需求、已增加、会增加或当前计划多了新步骤。");
        builder.append("回复要像同事对话，可以引用用户原话里的一个关键词；不要说“我没完全判断清楚”。");
        builder.append("不要输出 JSON，不要暴露内部字段名。\n\n");
        builder.append("用户原话：").append(userInput == null ? "" : userInput.trim()).append("\n");
        builder.append("任务阶段：").append(session == null ? "" : session.getPlanningPhase()).append("\n");
        builder.append("当前计划：\n");
        List<UserPlanCard> cards = cards(session);
        if (cards.isEmpty()) {
            builder.append("- 暂无计划步骤\n");
        } else {
            for (int index = 0; index < cards.size(); index++) {
                UserPlanCard card = cards.get(index);
                if (card == null) {
                    continue;
                }
                builder.append(index + 1).append(". ")
                        .append(card.getType()).append(" | ")
                        .append(card.getTitle()).append(" | ")
                        .append(card.getDescription() == null ? "" : card.getDescription())
                        .append("\n");
            }
        }
        TaskRuntimeSnapshot snapshot = runtimeTool == null || session == null ? null : runtimeTool.getSnapshot(session.getTaskId());
        if (snapshot != null && snapshot.getTask() != null) {
            builder.append("Runtime：")
                    .append(snapshot.getTask().getStatus()).append(" / ")
                    .append(snapshot.getTask().getCurrentStage()).append(" / progress=")
                    .append(snapshot.getTask().getProgress())
                    .append("\n");
            builder.append("步骤数：").append(snapshot.getSteps() == null ? 0 : snapshot.getSteps().size())
                    .append("，产物数：").append(snapshot.getArtifacts() == null ? 0 : snapshot.getArtifacts().size())
                    .append("\n");
        }
        return builder.toString();
    }

    private String fullPlanReply(PlanTaskSession session) {
        List<UserPlanCard> cards = cards(session);
        if (cards.isEmpty()) {
            return "当前还没有生成完整计划。";
        }
        StringBuilder builder = new StringBuilder("完整计划如下：");
        for (int index = 0; index < cards.size(); index++) {
            UserPlanCard card = cards.get(index);
            if (card == null) {
                continue;
            }
            builder.append("\n").append(index + 1).append(". ");
            if (card.getType() != null) {
                builder.append("[").append(card.getType().name()).append("] ");
            }
            builder.append(firstNonBlank(card.getTitle(), "未命名步骤"));
            if (hasText(card.getDescription())) {
                builder.append(" - ").append(card.getDescription().trim());
            }
        }
        return builder.toString();
    }

    private boolean isStatusQuery(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && session.getIntakeState().getIntakeType() != null
                && "STATUS_QUERY".equals(session.getIntakeState().getIntakeType().name());
    }

    private boolean hasUsablePlan(PlanTaskSession session) {
        return !cards(session).isEmpty();
    }

    private String emptyTaskReply(PlanTaskSession session) {
        String view = session == null || session.getIntakeState() == null ? null : session.getIntakeState().getReadOnlyView();
        if ("PLAN".equalsIgnoreCase(view)) {
            return "现在还没有可展示的完整计划。你可以直接告诉我想做什么，我会先帮你拆成计划。";
        }
        if ("ARTIFACTS".equalsIgnoreCase(view)) {
            return "现在还没有任务产物。你可以先发起一个任务，生成后我会把链接和进度同步给你。";
        }
        return "现在没有正在进行的任务。你可以直接发一个新任务，我会先规划，再等你确认。";
    }

    private List<UserPlanCard> cards(PlanTaskSession session) {
        if (session == null) {
            return List.of();
        }
        if (session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            return session.getPlanCards().stream()
                    .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                    .toList();
        }
        if (session.getPlanBlueprint() == null || session.getPlanBlueprint().getPlanCards() == null) {
            return List.of();
        }
        return session.getPlanBlueprint().getPlanCards().stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
