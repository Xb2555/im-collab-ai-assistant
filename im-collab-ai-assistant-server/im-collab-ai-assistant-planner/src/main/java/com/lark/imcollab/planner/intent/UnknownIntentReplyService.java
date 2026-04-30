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
import java.util.Locale;
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
        builder.append("The user's latest message could not be safely mapped to the supported task intents.\n");
        builder.append("Write ONE short, natural Chinese reply. Do not mention JSON, intent labels, routing, or classifiers.\n");
        builder.append("Be warm and specific. If the user is just giving feedback or weak approval, acknowledge it and keep the current plan; do not say you failed to understand.\n");
        builder.append("If the user seems to ask for a plan or status, tell them what you can show next naturally.\n");
        builder.append("Do not pretend to start execution, confirm execution, or complete actions. Do not ask multiple-choice questions.\n");
        builder.append("Limit to 45 Chinese characters if possible.\n\n");
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
            if (looksLikePlanFeedback(rawInput)) {
                return "好，那我先保留当前计划。要继续就回复“开始执行”，要调整也可以直接说。";
            }
            return "我先保留当前计划。你可以继续补充想改的点；不用改的话回复“开始执行”就行。";
        }
        return "我还没完全理解你想让我做什么。你可以直接说目标和想要的产物，我来帮你拆计划。";
    }

    private boolean looksLikePlanFeedback(String rawInput) {
        String normalized = rawInput == null ? "" : rawInput.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        String compact = normalized
                .replaceAll("\\s+", "")
                .replace("。", "")
                .replace(".", "")
                .replace("！", "")
                .replace("!", "");
        if (compact.length() > 24) {
            return false;
        }
        return compact.contains("还行")
                || compact.contains("不错")
                || compact.contains("可以")
                || compact.contains("挺好")
                || compact.contains("没问题")
                || compact.contains("先这样")
                || compact.contains("方案感觉")
                || compact.equals("ok")
                || compact.equals("okay")
                || compact.equals("好")
                || compact.equals("好的")
                || compact.equals("收到");
    }

    private String normalizeReply(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim()
                .replaceAll("^```[a-zA-Z]*", "")
                .replace("```", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (looksLikeExecutionClaim(normalized)) {
            return null;
        }
        if (normalized.length() > 140) {
            return normalized.substring(0, 140);
        }
        return normalized;
    }

    private boolean looksLikeExecutionClaim(String text) {
        String compact = text == null ? "" : text
                .replaceAll("\\s+", "")
                .replace("。", "")
                .replace(".", "");
        return compact.contains("开始执行")
                || compact.contains("马上执行")
                || compact.contains("执行计划")
                || compact.contains("按计划执行")
                || compact.contains("我会执行")
                || compact.contains("我来执行");
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
