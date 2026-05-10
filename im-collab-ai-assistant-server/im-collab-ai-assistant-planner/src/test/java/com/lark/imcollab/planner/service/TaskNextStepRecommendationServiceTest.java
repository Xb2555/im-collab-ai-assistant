package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.NextStepRecommendationCodeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskNextStepRecommendationServiceTest {

    private final ReactAgent agent = mock(ReactAgent.class);
    private final TaskNextStepRecommendationService service =
            new TaskNextStepRecommendationService(agent, new ObjectMapper());

    @Test
    void recommendsPptFromDocAndSummaryForDocOnlyTask() throws Exception {
        String message = """
                {
                  "recommendations": [
                    {
                      "code": "GENERATE_PPT_FROM_DOC",
                      "title": "基于当前文档生成一版汇报 PPT",
                      "reason": "文档已经结构化完成，适合继续沉淀成汇报材料。",
                      "suggestedUserInstruction": "基于这份文档生成一版汇报PPT"
                    },
                    {
                      "code": "GENERATE_SHAREABLE_SUMMARY",
                      "title": "生成一段可直接发送的任务摘要",
                      "reason": "当前内容已成型，适合补一段可直接同步出去的摘要。",
                      "suggestedUserInstruction": "基于当前任务内容生成一段可直接发送的摘要"
                    }
                  ]
                }
                """;
        when(agent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of("message", new AssistantMessage(message)))));

        List<NextStepRecommendation> recommendations = service.recommend(session(), snapshot(
                ArtifactTypeEnum.DOC
        ));

        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).getCode()).isEqualTo(NextStepRecommendationCodeEnum.GENERATE_PPT_FROM_DOC);
        assertThat(recommendations.get(0).getRecommendationId()).isEqualTo("GENERATE_PPT_FROM_DOC");
        assertThat(recommendations.get(0).getFollowUpMode()).isEqualTo(FollowUpModeEnum.CONTINUE_CURRENT_TASK);
        assertThat(recommendations.get(0).getTargetTaskId()).isEqualTo("task-1");
        assertThat(recommendations.get(0).getArtifactPolicy()).isEqualTo("KEEP_EXISTING_CREATE_NEW");
        assertThat(recommendations.get(1).getCode()).isEqualTo(NextStepRecommendationCodeEnum.GENERATE_SHAREABLE_SUMMARY);
    }

    @Test
    void recommendsSummaryFirstWhenDocAndPptBothExist() throws Exception {
        String message = """
                {
                  "recommendations": [
                    {
                      "code": "GENERATE_SHAREABLE_SUMMARY",
                      "title": "生成一段可直接发送的任务摘要",
                      "reason": "主要产物已经齐备，下一步适合整理成摘要文本。",
                      "suggestedUserInstruction": "基于当前任务内容生成一段可直接发送的摘要"
                    }
                  ]
                }
                """;
        when(agent.invoke(anyString(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of("message", new AssistantMessage(message)))));

        List<NextStepRecommendation> recommendations = service.recommend(session(), snapshot(
                ArtifactTypeEnum.DOC,
                ArtifactTypeEnum.PPT
        ));

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getCode()).isEqualTo(NextStepRecommendationCodeEnum.GENERATE_SHAREABLE_SUMMARY);
        assertThat(recommendations.get(0).getPriority()).isEqualTo(1);
    }

    @Test
    void fallsBackToDeterministicCandidatesWhenAgentOutputIsInvalid() throws Exception {
        when(agent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "message", "not-json"
        ))));

        List<NextStepRecommendation> recommendations = service.recommend(session(), snapshot(
                ArtifactTypeEnum.DOC
        ));

        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).getCode()).isEqualTo(NextStepRecommendationCodeEnum.GENERATE_PPT_FROM_DOC);
        assertThat(recommendations.get(1).getCode()).isEqualTo(NextStepRecommendationCodeEnum.GENERATE_SHAREABLE_SUMMARY);
    }

    private PlanTaskSession session() {
        return PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("整理技术方案")
                .clarifiedInstruction("整理技术方案并形成可复用的材料")
                .build();
    }

    private TaskRuntimeSnapshot snapshot(ArtifactTypeEnum... types) {
        return TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(TaskStatusEnum.COMPLETED).build())
                .artifacts(java.util.Arrays.stream(types)
                        .map(type -> ArtifactRecord.builder()
                                .artifactId("artifact-" + type.name())
                                .type(type)
                                .title(type.name())
                                .build())
                        .toList())
                .build();
    }
}
