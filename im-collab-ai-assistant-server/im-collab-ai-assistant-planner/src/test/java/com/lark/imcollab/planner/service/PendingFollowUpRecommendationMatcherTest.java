package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.planner.intent.LlmChoiceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingFollowUpRecommendationMatcherTest {

    private final LlmChoiceResolver choiceResolver = mock(LlmChoiceResolver.class);
    private final PendingFollowUpRecommendationMatcher matcher = new PendingFollowUpRecommendationMatcher(choiceResolver);

    @Test
    void selectsByExactSuggestedInstructionBeforeCallingLlm() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-ppt", "基于这份文档生成一版汇报PPT");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "基于这份文档生成一版汇报PPT",
                List.of(recommendation),
                false
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.SELECTED);
        assertThat(result.recommendation().getRecommendationId()).isEqualTo("rec-ppt");
    }

    @Test
    void exactSuggestedInstructionStillWinsEvenWhenUpstreamSuggestsStandaloneTask() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-ppt", "基于这份文档生成一版汇报PPT");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "基于这份文档生成一版汇报PPT",
                List.of(recommendation),
                false,
                true
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.SELECTED);
        assertThat(result.recommendation().getRecommendationId()).isEqualTo("rec-ppt");
    }

    @Test
    void standaloneTaskHintCanStillChooseNoneForOnlySimilarRecommendationText() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-ppt", "基于这份文档生成一版汇报PPT");
        when(choiceResolver.chooseOne(anyString(), eq(List.of("rec-ppt", "NONE")), anyString()))
                .thenReturn("NONE");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "帮我基于这份文档做一版汇报PPT，单独起一个新任务",
                List.of(recommendation),
                false,
                true
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.NONE);
    }

    @Test
    void standaloneTaskHintStillAllowsLlmToSelectRecommendationWhenSemanticallyClear() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-summary", "基于当前任务内容生成一段可直接发送的摘要");
        when(choiceResolver.chooseOne(anyString(), eq(List.of("rec-summary", "NONE")), anyString()))
                .thenReturn("rec-summary");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "基于当前任务内容生成一段可直接发送的摘要",
                List.of(recommendation),
                false,
                true
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.SELECTED);
        assertThat(result.recommendation().getRecommendationId()).isEqualTo("rec-summary");
    }

    @Test
    void prefixSupplementOfSuggestedInstructionSelectsRecommendationWithoutCallingLlm() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-summary", "基于当前任务内容生成一段可直接发送的摘要");
        when(choiceResolver.chooseOne(anyString(), eq(List.of("rec-summary", "NONE")), anyString()))
                .thenReturn("rec-summary");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "基于当前任务内容生成一段可直接发送的摘要，要求100字左右",
                List.of(recommendation),
                false,
                true
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.SELECTED);
        assertThat(result.recommendation().getRecommendationId()).isEqualTo("rec-summary");
    }

    @Test
    void explicitNewTaskSignalPreventsPrefixShortcutSelection() {
        PendingFollowUpRecommendation recommendation = recommendation("rec-summary", "基于当前任务内容生成一段可直接发送的摘要");
        when(choiceResolver.chooseOne(anyString(), eq(List.of("rec-summary", "NONE")), anyString()))
                .thenReturn("NONE");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "基于当前任务内容生成一段可直接发送的摘要，另起一个任务，要求100字左右",
                List.of(recommendation),
                false,
                true
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.NONE);
    }

    @Test
    void asksForSelectionWhenMultipleRecommendationsAndUserOnlySaysStart() {
        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "开始执行",
                List.of(
                        recommendation("rec-ppt", "基于这份文档生成一版汇报PPT"),
                        recommendation("rec-summary", "基于当前任务内容生成一段可直接发送的摘要")
                ),
                false
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION);
    }

    @Test
    void usesLlmToSelectSemanticMatch() {
        when(choiceResolver.chooseOne(anyString(), eq(List.of("rec-ppt", "rec-summary", "NONE")), anyString()))
                .thenReturn("rec-ppt");

        PendingFollowUpRecommendationMatcher.MatchResult result = matcher.match(
                "再帮我基于这个文档生成一版汇报用ppt，要求要5页",
                List.of(
                        recommendation("rec-ppt", "基于这份文档生成一版汇报PPT"),
                        recommendation("rec-summary", "基于当前任务内容生成一段可直接发送的摘要")
                ),
                false
        );

        assertThat(result.type()).isEqualTo(PendingFollowUpRecommendationMatcher.Type.SELECTED);
        assertThat(result.recommendation().getRecommendationId()).isEqualTo("rec-ppt");
    }

    private PendingFollowUpRecommendation recommendation(String id, String instruction) {
        return PendingFollowUpRecommendation.builder()
                .recommendationId(id)
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .sourceArtifactId("artifact-1")
                .sourceArtifactType(ArtifactTypeEnum.DOC)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                .suggestedUserInstruction(instruction)
                .priority(1)
                .build();
    }
}
