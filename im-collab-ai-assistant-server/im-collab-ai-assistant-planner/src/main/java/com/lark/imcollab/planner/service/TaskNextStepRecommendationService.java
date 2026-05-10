package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.NextStepRecommendationOutput;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.NextStepRecommendationCodeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.prompt.PromptContextKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TaskNextStepRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(TaskNextStepRecommendationService.class);

    private final ReactAgent nextStepRecommendationAgent;
    private final ObjectMapper objectMapper;

    public TaskNextStepRecommendationService(
            @Qualifier("nextStepRecommendationAgent") ReactAgent nextStepRecommendationAgent,
            ObjectMapper objectMapper
    ) {
        this.nextStepRecommendationAgent = nextStepRecommendationAgent;
        this.objectMapper = objectMapper;
    }

    public List<NextStepRecommendation> recommend(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        List<CandidateRecommendation> candidates = buildCandidates(session, snapshot);
        if (session == null || snapshot == null || candidates.isEmpty() || !isCompleted(snapshot)) {
            return List.of();
        }
        try {
            RunnableConfig config = buildConfig(session, snapshot, candidates);
            Optional<OverAllState> state = nextStepRecommendationAgent.invoke(
                    "请从候选动作中选择最合适的下一步推荐，最多返回 2 条。",
                    config
            );
            if (state.isEmpty()) {
                log.info("NEXT_STEP recommendation agent returned empty state, fallback to deterministic candidates: taskId={}",
                        session.getTaskId());
                return fallbackRecommendations(candidates);
            }
            Optional<NextStepRecommendationOutput> output = extractStructured(state.get().data(), NextStepRecommendationOutput.class);
            List<NextStepRecommendation> sanitized = sanitize(output.orElse(null), candidates);
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
            log.info("NEXT_STEP recommendation output empty after sanitize, fallback to deterministic candidates: taskId={}",
                    session.getTaskId());
            return fallbackRecommendations(candidates);
        } catch (Exception exception) {
            log.warn("NEXT_STEP recommendation generation failed, fallback to deterministic candidates: taskId={}, error={}",
                    session.getTaskId(), exception.getMessage(), exception);
            return fallbackRecommendations(candidates);
        }
    }

    List<CandidateRecommendation> buildCandidates(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (!isCompleted(snapshot)) {
            return List.of();
        }
        EnumSet<ArtifactTypeEnum> types = EnumSet.noneOf(ArtifactTypeEnum.class);
        for (ArtifactRecord artifact : defaultList(snapshot.getArtifacts())) {
            if (artifact != null && artifact.getType() != null) {
                types.add(artifact.getType());
            }
        }
        boolean hasDoc = types.contains(ArtifactTypeEnum.DOC);
        boolean hasPpt = types.contains(ArtifactTypeEnum.PPT);
        boolean hasSummary = types.contains(ArtifactTypeEnum.SUMMARY);
        if ((!hasDoc && !hasPpt) || defaultList(snapshot.getArtifacts()).isEmpty()) {
            return List.of();
        }
        List<CandidateRecommendation> candidates = new ArrayList<>();
        if (hasDoc && !hasPpt) {
            ArtifactRecord source = latestArtifact(snapshot, ArtifactTypeEnum.DOC).orElse(null);
            candidates.add(candidate(
                    NextStepRecommendationCodeEnum.GENERATE_PPT_FROM_DOC,
                    recommendationId(NextStepRecommendationCodeEnum.GENERATE_PPT_FROM_DOC),
                    "基于当前文档生成一版汇报 PPT",
                    "当前已经有结构化文档，下一步适合沉淀成便于汇报的 PPT。",
                    "基于这份文档生成一版汇报PPT",
                    ArtifactTypeEnum.PPT,
                    FollowUpModeEnum.CONTINUE_CURRENT_TASK,
                    session == null ? null : session.getTaskId(),
                    source == null ? null : source.getArtifactId(),
                    ArtifactTypeEnum.DOC,
                    "保留现有文档，基于该文档新增一份汇报PPT初稿。",
                    "KEEP_EXISTING_CREATE_NEW",
                    1
            ));
        }
        if (hasPpt && !hasDoc) {
            ArtifactRecord source = latestArtifact(snapshot, ArtifactTypeEnum.PPT).orElse(null);
            candidates.add(candidate(
                    NextStepRecommendationCodeEnum.GENERATE_DOC_FROM_PPT,
                    recommendationId(NextStepRecommendationCodeEnum.GENERATE_DOC_FROM_PPT),
                    "基于当前 PPT 补一份配套文档",
                    "当前已有汇报材料，下一步适合补充成可沉淀和流转的文档。",
                    "基于这份PPT补一份配套文档",
                    ArtifactTypeEnum.DOC,
                    FollowUpModeEnum.CONTINUE_CURRENT_TASK,
                    session == null ? null : session.getTaskId(),
                    source == null ? null : source.getArtifactId(),
                    ArtifactTypeEnum.PPT,
                    "保留现有PPT，基于该PPT新增一份配套文档。",
                    "KEEP_EXISTING_CREATE_NEW",
                    1
            ));
        }
        if (!hasSummary && (hasDoc || hasPpt)) {
            int priority = hasDoc && hasPpt ? 1 : 2;
            ArtifactRecord source = hasDoc
                    ? latestArtifact(snapshot, ArtifactTypeEnum.DOC).orElse(null)
                    : latestArtifact(snapshot, ArtifactTypeEnum.PPT).orElse(null);
            candidates.add(candidate(
                    NextStepRecommendationCodeEnum.GENERATE_SHAREABLE_SUMMARY,
                    recommendationId(NextStepRecommendationCodeEnum.GENERATE_SHAREABLE_SUMMARY),
                    "生成一段可直接发送的任务摘要",
                    "当前主要产物已经齐备，下一步适合整理成可直接转发到群里或私聊里的摘要文本。",
                    "基于当前任务内容生成一段可直接发送的摘要",
                    ArtifactTypeEnum.SUMMARY,
                    FollowUpModeEnum.CONTINUE_CURRENT_TASK,
                    session == null ? null : session.getTaskId(),
                    source == null ? null : source.getArtifactId(),
                    source == null ? null : source.getType(),
                    "保留现有产物，新增一段可直接发送的任务摘要。",
                    "KEEP_EXISTING_CREATE_NEW",
                    priority
            ));
        }
        return candidates.stream()
                .collect(LinkedHashMap<NextStepRecommendationCodeEnum, CandidateRecommendation>::new,
                        (map, candidate) -> map.putIfAbsent(candidate.code(), candidate),
                        Map::putAll)
                .values()
                .stream()
                .sorted(java.util.Comparator.comparingInt(CandidateRecommendation::priority))
                .toList();
    }

    private CandidateRecommendation candidate(
            NextStepRecommendationCodeEnum code,
            String recommendationId,
            String title,
            String reason,
            String suggestedUserInstruction,
            ArtifactTypeEnum targetDeliverable,
            FollowUpModeEnum followUpMode,
            String targetTaskId,
            String sourceArtifactId,
            ArtifactTypeEnum sourceArtifactType,
            String plannerInstruction,
            String artifactPolicy,
            int priority
    ) {
        return new CandidateRecommendation(
                code,
                recommendationId,
                title,
                reason,
                suggestedUserInstruction,
                targetDeliverable,
                followUpMode,
                targetTaskId,
                sourceArtifactId,
                sourceArtifactType,
                plannerInstruction,
                artifactPolicy,
                priority
        );
    }

    private RunnableConfig buildConfig(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            List<CandidateRecommendation> candidates
    ) {
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(session.getTaskId() + ":planner:next-step-recommendation");
        builder.addMetadata(PromptContextKeys.TASK_ID, safe(session.getTaskId()));
        builder.addMetadata(PromptContextKeys.PHASE,
                snapshot.getTask() == null || snapshot.getTask().getStatus() == null
                        ? ""
                        : snapshot.getTask().getStatus().name());
        builder.addMetadata(PromptContextKeys.AGENT_NAME, "next-step-recommendation-agent");
        builder.addMetadata(PromptContextKeys.NEXT_STEP_TASK_GOAL,
                firstNonBlank(session.getRawInstruction(), session.getExecutionContract() == null ? null : session.getExecutionContract().getTaskBrief()));
        builder.addMetadata(PromptContextKeys.NEXT_STEP_CLARIFIED_GOAL, safe(session.getClarifiedInstruction()));
        builder.addMetadata(PromptContextKeys.NEXT_STEP_PLAN_CARDS, summarizePlanCards(session.getPlanCards()));
        builder.addMetadata(PromptContextKeys.NEXT_STEP_COMPLETED_STEPS, summarizeCompletedSteps(snapshot.getSteps()));
        builder.addMetadata(PromptContextKeys.NEXT_STEP_ARTIFACTS, summarizeArtifacts(snapshot.getArtifacts()));
        builder.addMetadata(PromptContextKeys.NEXT_STEP_SUPPORTED_CAPABILITIES, "DOC, PPT, SUMMARY");
        builder.addMetadata(PromptContextKeys.NEXT_STEP_CANDIDATE_ACTIONS, summarizeCandidates(candidates));
        return builder.build();
    }

    private String summarizePlanCards(List<UserPlanCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "无";
        }
        return cards.stream()
                .filter(card -> card != null)
                .map(card -> "[" + (card.getType() == null ? "" : card.getType().name()) + "] "
                        + firstNonBlank(card.getTitle(), card.getDescription(), "未命名步骤"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无");
    }

    private String summarizeCompletedSteps(List<TaskStepRecord> steps) {
        List<String> values = defaultList(steps).stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.COMPLETED)
                .map(step -> firstNonBlank(step.getName(), step.getOutputSummary(), step.getStepId()))
                .filter(this::hasText)
                .toList();
        return values.isEmpty() ? "无" : String.join("\n", values);
    }

    private String summarizeArtifacts(List<ArtifactRecord> artifacts) {
        List<String> values = defaultList(artifacts).stream()
                .filter(artifact -> artifact != null && artifact.getType() != null)
                .map(artifact -> "[" + artifact.getType().name() + "] "
                        + firstNonBlank(artifact.getTitle(), artifact.getPreview(), artifact.getArtifactId()))
                .toList();
        return values.isEmpty() ? "无" : String.join("\n", values);
    }

    private String summarizeCandidates(List<CandidateRecommendation> candidates) {
        return candidates.stream()
                .map(candidate -> """
                        - code=%s
                          recommendationId=%s
                          title=%s
                          targetDeliverable=%s
                          followUpMode=%s
                          priority=%s
                          defaultReason=%s
                          defaultInstruction=%s
                          plannerInstruction=%s
                        """.formatted(
                        candidate.code().name(),
                        candidate.recommendationId(),
                        candidate.title(),
                        candidate.targetDeliverable().name(),
                        candidate.followUpMode().name(),
                        candidate.priority(),
                        candidate.reason(),
                        candidate.suggestedUserInstruction(),
                        candidate.plannerInstruction()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无");
    }

    private List<NextStepRecommendation> sanitize(
            NextStepRecommendationOutput output,
            List<CandidateRecommendation> candidates
    ) {
        if (output == null || output.getRecommendations() == null || output.getRecommendations().isEmpty()) {
            return List.of();
        }
        Map<NextStepRecommendationCodeEnum, CandidateRecommendation> allowed = new LinkedHashMap<>();
        for (CandidateRecommendation candidate : candidates) {
            allowed.put(candidate.code(), candidate);
        }
        List<NextStepRecommendation> sanitized = new ArrayList<>();
        LinkedHashSet<NextStepRecommendationCodeEnum> seen = new LinkedHashSet<>();
        for (NextStepRecommendation recommendation : output.getRecommendations()) {
            if (recommendation == null || recommendation.getCode() == null || !seen.add(recommendation.getCode())) {
                continue;
            }
            CandidateRecommendation candidate = allowed.get(recommendation.getCode());
            if (candidate == null) {
                continue;
            }
            sanitized.add(NextStepRecommendation.builder()
                    .code(candidate.code())
                    .recommendationId(candidate.recommendationId())
                    .title(firstNonBlank(recommendation.getTitle(), candidate.title()))
                    .reason(firstNonBlank(recommendation.getReason(), candidate.reason()))
                    .suggestedUserInstruction(firstNonBlank(
                            stripSendWording(recommendation.getSuggestedUserInstruction()),
                            candidate.suggestedUserInstruction()))
                    .targetDeliverable(candidate.targetDeliverable())
                    .followUpMode(candidate.followUpMode())
                    .targetTaskId(candidate.targetTaskId())
                    .sourceArtifactId(candidate.sourceArtifactId())
                    .sourceArtifactType(candidate.sourceArtifactType())
                    .plannerInstruction(candidate.plannerInstruction())
                    .artifactPolicy(candidate.artifactPolicy())
                    .priority(candidate.priority())
                    .build());
        }
        return sanitized.stream()
                .sorted(java.util.Comparator.comparingInt(NextStepRecommendation::getPriority))
                .limit(2)
                .toList();
    }

    private List<NextStepRecommendation> fallbackRecommendations(List<CandidateRecommendation> candidates) {
        return candidates.stream()
                .sorted(java.util.Comparator.comparingInt(CandidateRecommendation::priority))
                .limit(2)
                .map(candidate -> NextStepRecommendation.builder()
                        .code(candidate.code())
                        .recommendationId(candidate.recommendationId())
                        .title(candidate.title())
                        .reason(candidate.reason())
                        .suggestedUserInstruction(candidate.suggestedUserInstruction())
                        .targetDeliverable(candidate.targetDeliverable())
                        .followUpMode(candidate.followUpMode())
                        .targetTaskId(candidate.targetTaskId())
                        .sourceArtifactId(candidate.sourceArtifactId())
                        .sourceArtifactType(candidate.sourceArtifactType())
                        .plannerInstruction(candidate.plannerInstruction())
                        .artifactPolicy(candidate.artifactPolicy())
                        .priority(candidate.priority())
                        .build())
                .toList();
    }

    private String recommendationId(NextStepRecommendationCodeEnum code) {
        return code == null ? null : code.name();
    }

    private Optional<ArtifactRecord> latestArtifact(TaskRuntimeSnapshot snapshot, ArtifactTypeEnum type) {
        return defaultList(snapshot == null ? null : snapshot.getArtifacts()).stream()
                .filter(artifact -> artifact != null && artifact.getType() == type)
                .sorted(java.util.Comparator
                        .comparing(ArtifactRecord::getUpdatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(ArtifactRecord::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .reversed())
                .findFirst();
    }

    private String stripSendWording(String instruction) {
        if (!hasText(instruction)) {
            return instruction;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        if (lower.contains("自动发送") || lower.contains("直接发送") || lower.contains("发给某人") || lower.contains("发到群里")) {
            return null;
        }
        return instruction.trim();
    }

    private <T> Optional<T> extractStructured(Map<String, Object> data, Class<T> type) {
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        Optional<T> fromMessage = extractStructured(data.get("message"), type);
        if (fromMessage.isPresent()) {
            return fromMessage;
        }
        return extractStructured(data.get("messages"), type);
    }

    private <T> Optional<T> extractStructured(Object value, Class<T> type) {
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        if (value instanceof AssistantMessage assistantMessage) {
            return parseText(assistantMessage.getText(), type);
        }
        if (value instanceof Message message) {
            return parseText(message.getText(), type);
        }
        if (value instanceof CharSequence text) {
            return parseText(text.toString(), type);
        }
        if (value instanceof Map<?, ?> map) {
            try {
                return Optional.of(objectMapper.convertValue(map, type));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            Collections.reverse(values);
            for (Object item : values) {
                Optional<T> parsed = extractStructured(item, type);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private <T> Optional<T> parseText(String text, Class<T> type) {
        if (!hasText(text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(text, type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean isCompleted(TaskRuntimeSnapshot snapshot) {
        return snapshot != null
                && snapshot.getTask() != null
                && snapshot.getTask().getStatus() == TaskStatusEnum.COMPLETED;
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    record CandidateRecommendation(
            NextStepRecommendationCodeEnum code,
            String recommendationId,
            String title,
            String reason,
            String suggestedUserInstruction,
            ArtifactTypeEnum targetDeliverable,
            FollowUpModeEnum followUpMode,
            String targetTaskId,
            String sourceArtifactId,
            ArtifactTypeEnum sourceArtifactType,
            String plannerInstruction,
            String artifactPolicy,
            int priority
    ) {
    }
}
