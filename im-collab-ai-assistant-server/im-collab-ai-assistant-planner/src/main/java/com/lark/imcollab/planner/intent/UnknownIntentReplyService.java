package com.lark.imcollab.planner.intent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class UnknownIntentReplyService {

    private final ReactAgent unknownIntentReplyAgent;
    private final PlannerProperties plannerProperties;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Autowired
    public UnknownIntentReplyService(
            @Qualifier("unknownIntentReplyAgent") ReactAgent unknownIntentReplyAgent,
            PlannerProperties plannerProperties
    ) {
        this.unknownIntentReplyAgent = unknownIntentReplyAgent;
        this.plannerProperties = plannerProperties;
    }

    public UnknownIntentReplyService() {
        this(null, new PlannerProperties());
    }

    public String reply(PlanTaskSession session, String rawInput, String reason) {
        String fallback = fallbackReply(session, rawInput);
        if (unknownIntentReplyAgent == null || !plannerProperties.getIntent().isUnknownReplyModelEnabled()) {
            return fallback;
        }
        String prompt = buildPrompt(session, rawInput, reason);
        RunnableConfig config = RunnableConfig.builder()
                .threadId((session == null ? "unknown" : session.getTaskId()) + "-unknown-reply")
                .build();
        try {
            String text = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return unknownIntentReplyAgent.call(prompt, config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
            String normalized = normalizeReply(text);
            return normalized == null ? fallback : normalized;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String buildPrompt(PlanTaskSession session, String rawInput, String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are replying in a Feishu IM chat as a collaborative task agent.\n");
        builder.append("Your product identity is: a Planner / Agent-Pilot assistant for IM and GUI collaboration.\n");
        builder.append("You help users turn IM messages, selected context, and document links into confirmable plans, then track DOC, PPT, and SUMMARY progress.\n");
        builder.append("You are not a generic todo-list bot. Never describe yourself as managing todo items, personal todos, or task lists.\n");
        builder.append("The user's latest message could not be safely mapped to the supported task intents.\n");
        builder.append("Write ONE short, natural Chinese reply. Do not mention JSON, intent labels, routing, or classifiers.\n");
        builder.append("Do not use a fixed template. Echo one concrete hint from the user's wording or the current plan when useful.\n");
        builder.append("For identity or capability questions like 你是谁 / 你能做什么, answer with the Planner identity and mention planning/context/progress, not todos.\n");
        builder.append("Be warm and specific. If the user is just giving feedback or weak approval, acknowledge it and keep the current plan; do not say you failed to understand.\n");
        builder.append("If there is no current plan and the user is chatting casually, reply like a present teammate and invite them to send a concrete task when ready.\n");
        builder.append("If the user seems to ask for a plan or status, tell them what you can show next naturally, but do not claim the plan changed.\n");
        builder.append("Do not pretend to start execution, confirm execution, or complete actions. Do not ask multiple-choice questions.\n");
        builder.append("Avoid bureaucratic phrases like '我没完全判断清楚'. Limit to 55 Chinese characters if possible.\n\n");
        builder.append("Session phase: ").append(session == null ? "unknown" : session.getPlanningPhase()).append("\n");
        builder.append("Has plan: ").append(hasPlan(session)).append("\n");
        builder.append("Plan cards: ").append(cardSummary(session)).append("\n");
        builder.append("Routing reason: ").append(reason == null ? "" : reason).append("\n");
        builder.append("User message: ").append(rawInput == null ? "" : rawInput.trim()).append("\n");
        builder.append("Reply:");
        return builder.toString();
    }

    private String fallbackReply(PlanTaskSession session, String rawInput) {
        if (hasPlan(session)) {
            return "我先不动当前计划。想看细节、调整步骤或推进执行，都可以直接说。";
        }
        String input = rawInput == null ? "" : rawInput.trim();
        if (isIdentityQuestion(input)) {
            return "我是协作规划助手，负责把 IM/GUI 里的材料整理成可确认的计划，并跟进文档、PPT 或摘要进度。";
        }
        if (input.contains("计划") || input.contains("进度") || input.contains("任务") || input.contains("产物")) {
            return "现在还没有任务。你可以直接告诉我想做什么，我会先帮你规划。";
        }
        if (input.contains("执行") || input.contains("开始") || input.contains("确认")) {
            return "现在还没有可执行的计划。你先告诉我任务目标，我会拆好步骤等你确认。";
        }
        return "我在。你把想整理的材料或目标发我，我会先帮你拆成计划。";
    }

    private String normalizeReply(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim()
                .replaceAll("^```[a-zA-Z]*", "")
                .replace("```", "")
                .replaceFirst("^(回复|答复)[:：]", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (looksLikeExecutionClaim(normalized)) {
            return null;
        }
        if (looksLikeTodoBotIdentity(normalized)) {
            return null;
        }
        if (normalized.length() > 140) {
            return normalized.substring(0, 140);
        }
        return normalized;
    }

    private boolean isIdentityQuestion(String input) {
        String compact = input == null ? "" : input.replaceAll("\\s+", "");
        return compact.contains("你是谁")
                || compact.contains("你能做什么")
                || compact.contains("你可以做什么")
                || compact.contains("你是干嘛")
                || compact.contains("你有什么用")
                || compact.contains("介绍一下你");
    }

    private boolean looksLikeExecutionClaim(String text) {
        String compact = text == null ? "" : text
                .replaceAll("\\s+", "")
                .replace("。", "")
                .replace(".", "");
        return compact.contains("已开始执行")
                || compact.contains("正在执行")
                || compact.contains("执行中")
                || compact.contains("马上执行")
                || compact.contains("好的开始执行")
                || compact.contains("我会开始执行")
                || compact.contains("我来开始执行")
                || compact.contains("执行计划")
                || compact.contains("按计划执行")
                || compact.contains("我会执行")
                || compact.contains("我来执行");
    }

    private boolean looksLikeTodoBotIdentity(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.contains("待办事项")
                || compact.contains("待办清单")
                || compact.contains("管理待办")
                || (compact.contains("待办") && (compact.contains("创建") || compact.contains("管理")));
    }

    private boolean hasPlan(PlanTaskSession session) {
        return session != null
                && ((session.getPlanCards() != null && !session.getPlanCards().isEmpty())
                || session.getPlanBlueprint() != null);
    }

    private String cardSummary(PlanTaskSession session) {
        if (session == null || session.getPlanCards() == null || session.getPlanCards().isEmpty()) {
            return "[]";
        }
        List<UserPlanCard> cards = session.getPlanCards();
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(cards.size(), 3);
        for (int index = 0; index < limit; index++) {
            UserPlanCard card = cards.get(index);
            if (index > 0) {
                builder.append("; ");
            }
            builder.append(card == null ? "unknown" : card.getTitle());
        }
        if (cards.size() > limit) {
            builder.append("; +").append(cards.size() - limit).append(" more");
        }
        builder.append("]");
        return builder.toString();
    }

    private long timeoutSeconds() {
        return Math.max(1, plannerProperties.getIntent().getUnknownReplyTimeoutSeconds());
    }
}
