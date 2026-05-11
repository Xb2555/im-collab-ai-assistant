package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.intent.LlmChoiceResolver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PendingFollowUpRecommendationMatcher {

    private static final Pattern SINGLE_DIGIT_SELECTION = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");

    private final LlmChoiceResolver llmChoiceResolver;

    public PendingFollowUpRecommendationMatcher(LlmChoiceResolver llmChoiceResolver) {
        this.llmChoiceResolver = llmChoiceResolver;
    }

    public MatchResult match(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection
    ) {
        return match(userInput, recommendations, awaitingSelection, false);
    }

    public MatchResult match(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection,
            boolean upstreamSuggestsStandaloneTask
    ) {
        if (!hasText(userInput) || recommendations == null || recommendations.isEmpty()) {
            return MatchResult.none();
        }
        Integer index = parseCandidateIndex(userInput);
        if (index != null && index >= 1 && index <= recommendations.size()) {
            return MatchResult.selected(recommendations.get(index - 1));
        }
        Optional<PendingFollowUpRecommendation> explicitCarryForward = explicitCarryForwardMatch(userInput, recommendations);
        if (explicitCarryForward.isPresent()) {
            return MatchResult.selected(explicitCarryForward.get());
        }
        if (!upstreamSuggestsStandaloneTask
                && recommendations.size() == 1
                && ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            return MatchResult.selected(recommendations.get(0));
        }
        if (recommendations.size() > 1 && ExecutionCommandGuard.isExplicitExecutionRequest(userInput)) {
            return MatchResult.askSelection();
        }
        String choice = llmChoiceResolver.chooseOne(
                buildInstruction(userInput, recommendations, awaitingSelection, upstreamSuggestsStandaloneTask),
                allowedChoices(recommendations),
                """
                你负责判断用户这句回复，是否在承接“上一轮任务完成后的推荐下一步动作”。

                只允许从给定 recommendationId 或 NONE 中选择一个。

                规则：
                1. 只有当用户这句回复明显是在继续某条推荐动作时，才选 recommendationId。
                2. 如果用户是在开启一个全新任务、忽略之前推荐、或表达内容与所有推荐都不相干，选 NONE。
                3. 如果有多条推荐，而用户只说“开始/继续/就这个”这类无法区分具体哪一条的确认语，选 NONE。
                4. 不要因为用户消息里提到“文档/PPT/摘要”就随便匹配；要看整体语义是否在承接某条推荐。
                5. 如果用户这句回复是在复述某条推荐语，并补充了字数、页数、风格、受众、详略、标题等执行约束，仍然视为承接该推荐，不要误判为新任务。
                6. 只有当用户明确表达“新建一个任务 / 新开一个任务 / 另起一个任务 / 再开一个任务”等意思时，才优先按新任务理解并选 NONE。
                7. 如果上游意图分类已经偏向 START_TASK，把它当成一个重要提示；但如果用户明显是在承接推荐并补充要求，仍然可以选择 recommendationId。
                8. 只返回一个可选值本身，不要解释。
                """
        );
        if (!hasText(choice) || "NONE".equalsIgnoreCase(choice.trim())) {
            return MatchResult.none();
        }
        return recommendations.stream()
                .filter(value -> value != null && hasText(value.getRecommendationId()))
                .filter(value -> value.getRecommendationId().equalsIgnoreCase(choice.trim()))
                .findFirst()
                .map(MatchResult::selected)
                .orElseGet(MatchResult::none);
    }

    public boolean isExplicitCarryForwardCandidate(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        return explicitCarryForwardMatch(userInput, recommendations).isPresent();
    }

    private Optional<PendingFollowUpRecommendation> explicitCarryForwardMatch(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (!hasText(userInput) || recommendations == null || recommendations.isEmpty()) {
            return Optional.empty();
        }
        String normalizedInput = compact(userInput);
        for (PendingFollowUpRecommendation recommendation : recommendations) {
            if (recommendation == null || !hasText(recommendation.getSuggestedUserInstruction())) {
                continue;
            }
            String normalizedSuggestion = compact(recommendation.getSuggestedUserInstruction());
            if (normalizedInput.equals(normalizedSuggestion)) {
                return Optional.of(recommendation);
            }
            if (normalizedInput.startsWith(normalizedSuggestion)
                    && !looksLikeExplicitFreshTask(userInput)) {
                return Optional.of(recommendation);
            }
        }
        return Optional.empty();
    }

    private List<String> allowedChoices(List<PendingFollowUpRecommendation> recommendations) {
        List<String> choices = recommendations.stream()
                .filter(value -> value != null && hasText(value.getRecommendationId()))
                .map(PendingFollowUpRecommendation::getRecommendationId)
                .distinct()
                .toList();
        return java.util.stream.Stream.concat(choices.stream(), java.util.stream.Stream.of("NONE")).toList();
    }

    private String buildInstruction(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection,
            boolean upstreamSuggestsStandaloneTask
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户回复：").append(userInput.trim()).append("\n");
        builder.append("当前是否正在等待用户按编号选择推荐：").append(awaitingSelection).append("\n");
        builder.append("上游意图分类是否偏向 START_TASK：").append(upstreamSuggestsStandaloneTask).append("\n");
        builder.append("可承接推荐：\n");
        for (int index = 0; index < recommendations.size(); index++) {
            PendingFollowUpRecommendation recommendation = recommendations.get(index);
            if (recommendation == null) {
                continue;
            }
            builder.append(index + 1)
                    .append(". recommendationId=").append(recommendation.getRecommendationId())
                    .append(" | targetDeliverable=").append(recommendation.getTargetDeliverable())
                    .append(" | suggestedUserInstruction=").append(nullToEmpty(recommendation.getSuggestedUserInstruction()))
                    .append(" | plannerInstruction=").append(nullToEmpty(recommendation.getPlannerInstruction()))
                    .append("\n");
        }
        return builder.toString();
    }

    private Integer parseCandidateIndex(String input) {
        if (!hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.matches("\\d+")) {
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher matcher = SINGLE_DIGIT_SELECTION.matcher(trimmed);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String compact(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("！", "")
                .replace("!", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean looksLikeExplicitFreshTask(String value) {
        String normalized = compact(value);
        return normalized.contains("新建一个任务")
                || normalized.contains("新开一个任务")
                || normalized.contains("另起一个任务")
                || normalized.contains("再开一个任务")
                || normalized.contains("重新开始一个新任务")
                || normalized.contains("单独起一个新任务");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record MatchResult(Type type, PendingFollowUpRecommendation recommendation) {

        public static MatchResult selected(PendingFollowUpRecommendation recommendation) {
            return new MatchResult(Type.SELECTED, recommendation);
        }

        public static MatchResult askSelection() {
            return new MatchResult(Type.ASK_SELECTION, null);
        }

        public static MatchResult none() {
            return new MatchResult(Type.NONE, null);
        }
    }

    public enum Type {
        SELECTED,
        ASK_SELECTION,
        NONE
    }
}
