package com.lark.imcollab.planner.replan;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class PlanAdjustmentInterpreter {

    private final ReactAgent planningAgent;
    private final ObjectMapper objectMapper;
    private final PlannerProperties plannerProperties;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public PlanAdjustmentInterpreter(
            @Qualifier("planningAgent") ReactAgent planningAgent,
            ObjectMapper objectMapper,
            PlannerProperties plannerProperties
    ) {
        this.planningAgent = planningAgent;
        this.objectMapper = objectMapper;
        this.plannerProperties = plannerProperties;
    }

    public PlanPatchIntent interpret(PlanTaskSession session, String instruction, WorkspaceContext workspaceContext) {
        List<UserPlanCard> cards = activeCards(session);
        Optional<PlanPatchIntent> local = interpretLocally(cards, instruction);
        if (local.isPresent() && shouldUseLocalImmediately(local.get())) {
            return local.get();
        }
        Optional<PlanPatchIntent> model = interpretWithModel(session, cards, instruction)
                .flatMap(intent -> normalizeModelIntent(intent, cards, instruction));
        if (local.isPresent()
                && conflictsWithLocalAdd(local.get(), model.orElse(null), instruction)) {
            model = Optional.empty();
        }
        if (model.isPresent()) {
            return model.get();
        }
        if (local.isPresent() && isUsableFallback(local.get())) {
            return local.get();
        }
        PlanPatchIntent fallback = local.orElseGet(() -> clarify("我没完全判断清楚，你想修改哪一个步骤？"));
        if (fallback.getOperation() == null || !isUsableFallback(fallback)) {
            String question = fallback.getClarificationQuestion();
            return clarify(hasText(question) ? question : "我没完全判断清楚，你想修改哪一个步骤？");
        }
        return fallback;
    }

    private Optional<PlanPatchIntent> interpretLocally(List<UserPlanCard> cards, String instruction) {
        String normalized = normalize(instruction);
        if (normalized.isBlank()) {
            return Optional.of(clarify("我没收到具体修改内容，你想调整哪个步骤？"));
        }
        if (containsAny(normalized, "全部重做", "重新规划", "从头规划", "整体重做", "regenerate all")) {
            return Optional.of(PlanPatchIntent.builder()
                    .operation(PlanPatchOperation.REGENERATE_ALL)
                    .confidence(0.95d)
                    .reason("explicit full replan")
                    .build());
        }
        if (containsAny(normalized, "不想", "不要", "去掉", "删除", "删掉", "移除", "不用")) {
            List<String> targets = matchTargets(cards, normalized);
            if (!targets.isEmpty()) {
                return Optional.of(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.REMOVE_STEP)
                        .targetCardIds(targets)
                        .confidence(0.9d)
                        .reason("local remove patch")
                        .build());
            }
            return Optional.of(clarify("你想去掉计划里的哪一步？"));
        }
        if (containsAny(normalized, "改成", "换成", "调整为", "改为")) {
            List<String> targets = targetByOrdinal(cards, normalized);
            if (targets.isEmpty()) {
                targets = matchTargets(cards, normalized);
            }
            if (!targets.isEmpty()) {
                return Optional.of(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.UPDATE_STEP)
                        .targetCardIds(targets)
                        .newCardDrafts(List.of(draftFromInstruction(normalized)))
                        .confidence(0.55d)
                        .reason("local update patch")
                        .build());
            }
            return Optional.of(clarify("你想把哪一步改掉？"));
        }
        if (looksLikeReorder(normalized)) {
            List<String> ordered = inferOrder(cards, normalized);
            if (ordered.size() == cards.size()) {
                return Optional.of(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.REORDER_STEP)
                        .orderedCardIds(ordered)
                        .confidence(0.55d)
                        .reason("local reorder patch")
                        .build());
            }
        }
        if (containsAny(normalized, "再加", "加上", "加个", "新增", "补一", "补个", "补一下", "补充", "追加", "还要", "来一份", "也来")) {
            return Optional.of(PlanPatchIntent.builder()
                    .operation(PlanPatchOperation.ADD_STEP)
                    .newCardDrafts(List.of(draftFromInstruction(normalized)))
                    .confidence(0.55d)
                    .reason("local add patch")
                    .build());
        }
        return Optional.empty();
    }

    private Optional<PlanPatchIntent> interpretWithModel(PlanTaskSession session, List<UserPlanCard> cards, String instruction) {
        if (planningAgent == null) {
            return Optional.empty();
        }
        if (!plannerProperties.getReplan().isPatchIntentModelEnabled()) {
            return Optional.empty();
        }
        String prompt = buildPrompt(cards, instruction);
        RunnableConfig config = RunnableConfig.builder()
                .threadId((session == null ? "unknown" : session.getTaskId()) + "-patch-intent")
                .build();
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return planningAgent.call(prompt, config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .thenApply(this::parseModelIntent)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlanPatchIntent> parseModelIntent(String text) {
        if (!hasText(text)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            PlanPatchOperation operation = PlanPatchOperation.valueOf(root.path("operation").asText("CLARIFY_REQUIRED"));
            List<String> targets = strings(root.path("targetCardIds"));
            List<String> ordered = strings(root.path("orderedCardIds"));
            List<PlanPatchCardDraft> drafts = new ArrayList<>();
            root.path("newCardDrafts").forEach(node -> drafts.add(PlanPatchCardDraft.builder()
                    .title(node.path("title").asText(null))
                    .description(node.path("description").asText(null))
                    .type(parseType(node.path("type").asText(null)))
                    .build()));
            return Optional.of(PlanPatchIntent.builder()
                    .operation(operation)
                    .targetCardIds(targets)
                    .orderedCardIds(ordered)
                    .newCardDrafts(drafts)
                    .confidence(root.path("confidence").asDouble(0.0d))
                    .reason(root.path("reason").asText(null))
                    .clarificationQuestion(root.path("clarificationQuestion").asText(null))
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlanPatchIntent> normalizeModelIntent(PlanPatchIntent intent, List<UserPlanCard> cards, String instruction) {
        if (intent == null || intent.getOperation() == null || !passesModelThreshold(intent)) {
            return Optional.empty();
        }
        if (intent.getOperation() == PlanPatchOperation.REGENERATE_ALL || intent.getOperation() == PlanPatchOperation.CLARIFY_REQUIRED) {
            return Optional.of(intent);
        }
        if ((intent.getOperation() == PlanPatchOperation.REMOVE_STEP || intent.getOperation() == PlanPatchOperation.UPDATE_STEP)
                && (intent.getTargetCardIds() == null || intent.getTargetCardIds().isEmpty())) {
            List<String> targets = matchTargets(cards, normalize(instruction));
            if (targets.isEmpty()) {
                return Optional.empty();
            }
            intent.setTargetCardIds(targets);
        }
        if (intent.getOperation() == PlanPatchOperation.ADD_STEP
                && (intent.getNewCardDrafts() == null || intent.getNewCardDrafts().isEmpty())) {
            intent.setNewCardDrafts(List.of(draftFromInstruction(normalize(instruction))));
        }
        if (intent.getOperation() == PlanPatchOperation.UPDATE_STEP
                && (intent.getNewCardDrafts() == null || intent.getNewCardDrafts().isEmpty())) {
            intent.setNewCardDrafts(List.of(draftFromInstruction(normalize(instruction))));
        }
        if (intent.getOperation() == PlanPatchOperation.REORDER_STEP
                && (intent.getOrderedCardIds() == null || intent.getOrderedCardIds().isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(intent);
    }

    private String buildPrompt(List<UserPlanCard> cards, String instruction) {
        StringBuilder builder = new StringBuilder();
        builder.append("You classify one user plan adjustment into a JSON patch intent. ");
        builder.append("Do not rewrite the whole plan unless the user explicitly asks to regenerate everything.\n");
        builder.append("Allowed operations: ADD_STEP, REMOVE_STEP, UPDATE_STEP, REORDER_STEP, REGENERATE_ALL, CLARIFY_REQUIRED.\n");
        builder.append("Return JSON only: {\"operation\":\"...\",\"targetCardIds\":[],\"orderedCardIds\":[],\"newCardDrafts\":[{\"title\":\"\",\"description\":\"\",\"type\":\"DOC|PPT|SUMMARY\"}],\"confidence\":0.0,\"reason\":\"\",\"clarificationQuestion\":\"\"}\n");
        builder.append("Important: additive phrases such as 顺手补一个, 再加, 追加, also include, add one more should be ADD_STEP with empty targetCardIds. Do not overwrite an existing card for additive requests.\n");
        builder.append("Current cards:\n");
        for (UserPlanCard card : cards) {
            builder.append("- ")
                    .append(card.getCardId()).append(" | ")
                    .append(card.getType()).append(" | ")
                    .append(card.getTitle()).append(" | ")
                    .append(card.getDescription() == null ? "" : card.getDescription())
                    .append("\n");
        }
        builder.append("User adjustment: ").append(instruction == null ? "" : instruction.trim());
        return builder.toString();
    }

    private List<String> matchTargets(List<UserPlanCard> cards, String normalizedInstruction) {
        List<ScoredCard> scored = new ArrayList<>();
        for (UserPlanCard card : cards) {
            String cardText = normalize(card.getTitle() + " " + card.getDescription() + " " + card.getType());
            int score = overlapScore(normalizedInstruction, cardText);
            if (card.getType() == PlanCardTypeEnum.SUMMARY && containsAny(normalizedInstruction, "摘要", "总结", "进展", "summary")) {
                score += 5;
            }
            if (card.getType() == PlanCardTypeEnum.PPT && containsAny(normalizedInstruction, "ppt", "演示", "汇报")) {
                score += 3;
            }
            if (card.getType() == PlanCardTypeEnum.DOC && containsAny(normalizedInstruction, "文档", "doc")) {
                score += 3;
            }
            if (score > 2 && hasText(card.getCardId())) {
                scored.add(new ScoredCard(card.getCardId(), score));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        int bestScore = scored.stream().mapToInt(ScoredCard::score).max().orElse(0);
        return scored.stream()
                .filter(card -> card.score() == bestScore)
                .sorted(Comparator.comparing(ScoredCard::cardId))
                .map(ScoredCard::cardId)
                .toList();
    }

    private List<String> targetByOrdinal(List<UserPlanCard> cards, String normalizedInstruction) {
        if (cards.isEmpty()) {
            return List.of();
        }
        if (containsAny(normalizedInstruction, "最后一步", "最后一个")) {
            return List.of(cards.get(cards.size() - 1).getCardId());
        }
        if (containsAny(normalizedInstruction, "第一步", "第一个")) {
            return List.of(cards.get(0).getCardId());
        }
        if (containsAny(normalizedInstruction, "第二步", "第二个") && cards.size() > 1) {
            return List.of(cards.get(1).getCardId());
        }
        return List.of();
    }

    private PlanPatchCardDraft draftFromInstruction(String normalizedInstruction) {
        PlanCardTypeEnum type = inferType(normalizedInstruction);
        return PlanPatchCardDraft.builder()
                .type(type)
                .title(titleFor(type, normalizedInstruction))
                .description(descriptionFor(type, normalizedInstruction))
                .build();
    }

    private PlanCardTypeEnum inferType(String normalizedInstruction) {
        if (containsAny(normalizedInstruction, "ppt", "演示稿", "汇报")) {
            return PlanCardTypeEnum.PPT;
        }
        if (containsAny(normalizedInstruction, "文档", "doc", "风险", "评估", "表", "清单")) {
            return PlanCardTypeEnum.DOC;
        }
        return PlanCardTypeEnum.SUMMARY;
    }

    private String titleFor(PlanCardTypeEnum type, String normalizedInstruction) {
        if (type == PlanCardTypeEnum.PPT && containsAny(normalizedInstruction, "老板", "领导", "管理层", "boss")) {
            return "生成老板汇报 PPT";
        }
        if (type == PlanCardTypeEnum.PPT) {
            return "生成补充 PPT";
        }
        if (type == PlanCardTypeEnum.DOC && containsAny(normalizedInstruction, "风险", "评估", "表")) {
            return "生成项目风险评估表";
        }
        if (type == PlanCardTypeEnum.DOC) {
            return "生成补充文档";
        }
        if (containsAny(normalizedInstruction, "一句话", "一句", "结论")) {
            return "生成一句话总结";
        }
        if (containsAny(normalizedInstruction, "群", "进展")) {
            return "生成群内项目进展摘要";
        }
        String content = conciseAdjustmentContent(normalizedInstruction);
        return hasText(content) ? "补充：" + content : "生成补充摘要";
    }

    private String descriptionFor(PlanCardTypeEnum type, String normalizedInstruction) {
        if (type == PlanCardTypeEnum.PPT) {
            return "基于已有文档和计划内容生成补充汇报 PPT";
        }
        if (type == PlanCardTypeEnum.DOC) {
            if (containsAny(normalizedInstruction, "风险", "评估", "表")) {
                return "基于当前方案补充风险、影响和应对措施表";
            }
            return "基于当前任务上下文生成补充文档";
        }
        return "基于已有文档和汇报材料生成补充摘要";
    }

    private boolean looksLikeReorder(String normalizedInstruction) {
        return containsAny(normalizedInstruction, "先做", "放到前", "提前", "最后做", "顺序");
    }

    private List<String> inferOrder(List<UserPlanCard> cards, String normalizedInstruction) {
        if (cards.size() < 2 || !containsAny(normalizedInstruction, "先做")) {
            return List.of();
        }
        List<UserPlanCard> ordered = new ArrayList<>(cards);
        if (normalizedInstruction.indexOf("ppt") >= 0 && normalizedInstruction.indexOf("文档") > normalizedInstruction.indexOf("ppt")) {
            ordered.sort((left, right) -> {
                if (left.getType() == PlanCardTypeEnum.PPT && right.getType() != PlanCardTypeEnum.PPT) {
                    return -1;
                }
                if (left.getType() != PlanCardTypeEnum.PPT && right.getType() == PlanCardTypeEnum.PPT) {
                    return 1;
                }
                return 0;
            });
            return ordered.stream().map(UserPlanCard::getCardId).toList();
        }
        return List.of();
    }

    private PlanPatchIntent clarify(String question) {
        return PlanPatchIntent.builder()
                .operation(PlanPatchOperation.CLARIFY_REQUIRED)
                .confidence(1.0d)
                .clarificationQuestion(question)
                .reason("needs clarification")
                .build();
    }

    private List<UserPlanCard> activeCards(PlanTaskSession session) {
        List<UserPlanCard> cards = session == null ? null : session.getPlanCards();
        if ((cards == null || cards.isEmpty()) && session != null && session.getPlanBlueprint() != null) {
            cards = session.getPlanBlueprint().getPlanCards();
        }
        if (cards == null) {
            return List.of();
        }
        return cards.stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
    }

    private int overlapScore(String input, String candidate) {
        LinkedHashSet<Integer> chars = new LinkedHashSet<>();
        input.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(chars::add);
        int score = 0;
        for (Integer codePoint : chars) {
            if (candidate.indexOf(new String(Character.toChars(codePoint))) >= 0) {
                score++;
            }
        }
        return score;
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private boolean containsAny(String input, String... needles) {
        if (input == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && input.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean shouldUseLocalImmediately(PlanPatchIntent intent) {
        return intent.getOperation() == PlanPatchOperation.REGENERATE_ALL
                || intent.getOperation() == PlanPatchOperation.REMOVE_STEP
                || intent.getOperation() == PlanPatchOperation.CLARIFY_REQUIRED;
    }

    private boolean conflictsWithLocalAdd(PlanPatchIntent local, PlanPatchIntent model, String instruction) {
        if (local == null || model == null) {
            return false;
        }
        if (local.getOperation() != PlanPatchOperation.ADD_STEP) {
            return false;
        }
        if (model.getOperation() == PlanPatchOperation.ADD_STEP) {
            return false;
        }
        String normalized = normalize(instruction);
        return containsAny(normalized,
                "再加", "加上", "加个", "新增", "补一", "补个", "补一下", "补充", "追加",
                "还要", "来一份", "也来", "顺手补", "再帮我补", "add", "alsoinclude");
    }

    private boolean passesModelThreshold(PlanPatchIntent intent) {
        return intent.getConfidence() >= plannerProperties.getReplan().getPatchIntentPassThreshold();
    }

    private boolean isUsableFallback(PlanPatchIntent intent) {
        return intent.getConfidence() >= plannerProperties.getReplan().getLocalFallbackThreshold();
    }

    private String conciseAdjustmentContent(String normalizedInstruction) {
        String content = normalizedInstruction == null ? "" : normalizedInstruction;
        for (String prefix : List.of("再加一条", "再加上", "再加", "加上", "新增", "补充", "追加", "还要", "最后", "输出", "生成")) {
            content = content.replace(normalize(prefix), "");
        }
        if (content.length() > 24) {
            return content.substring(0, 24);
        }
        return content;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText(null);
            if (hasText(value)) {
                values.add(value.trim());
            }
        });
        return values;
    }

    private PlanCardTypeEnum parseType(String value) {
        if (!hasText(value)) {
            return PlanCardTypeEnum.SUMMARY;
        }
        try {
            return PlanCardTypeEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlanCardTypeEnum.SUMMARY;
        }
    }

    private int timeoutSeconds() {
        return Math.max(1, plannerProperties.getReplan().getPatchIntentTimeoutSeconds());
    }

    private record ScoredCard(String cardId, int score) {
    }
}
