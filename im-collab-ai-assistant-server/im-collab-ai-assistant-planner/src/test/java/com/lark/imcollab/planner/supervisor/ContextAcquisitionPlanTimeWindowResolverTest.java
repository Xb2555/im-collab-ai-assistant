package com.lark.imcollab.planner.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextSourceRequest;
import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextAcquisitionPlanTimeWindowResolverTest {

    @Test
    void locallyRepairsSameDayStructuredWindowWithoutLlm() {
        ContextAcquisitionPlanTimeWindowResolver resolver =
                new ContextAcquisitionPlanTimeWindowResolver(null, new ObjectMapper());
        LocalDate recentDate = OffsetDateTime.now().minusDays(6).toLocalDate();
        String recentDateText = recentDate.toString();

        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(List.of(ContextSourceRequest.builder()
                        .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                        .chatId("oc_1")
                        .timeRange(recentDateText + " 02:00 - 02:06")
                        .query("")
                        .pageSize(50)
                        .pageLimit(5)
                        .build()))
                .reason("intent source scope requires context acquisition")
                .clarificationQuestion("")
                .build();

        ContextAcquisitionPlan repaired = resolver.ensureExecutable(
                "task-1",
                "根据该时间窗的群消息来总结",
                null,
                plan
        );

        assertThat(repaired.isNeedCollection()).isTrue();
        assertThat(repaired.getSources()).hasSize(1);
        assertThat(repaired.getSources().get(0).getStartTime()).isEqualTo(recentDateText + "T02:00:00+08:00");
        assertThat(repaired.getSources().get(0).getEndTime()).isEqualTo(recentDateText + "T02:06:59+08:00");
    }

    @Test
    void rejectsWindowOlderThan30Days() {
        ContextAcquisitionPlanTimeWindowResolver resolver =
                new ContextAcquisitionPlanTimeWindowResolver(null, new ObjectMapper());

        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(List.of(ContextSourceRequest.builder()
                        .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                        .chatId("oc_1")
                        .timeRange("2024-05-07 02:00 - 02:06")
                        .build()))
                .reason("intent source scope requires context acquisition")
                .clarificationQuestion("")
                .build();

        ContextAcquisitionPlan rejected = resolver.ensureExecutable(
                "task-old",
                "根据很久之前的群消息来总结",
                null,
                plan
        );

        assertThat(rejected.isNeedCollection()).isFalse();
        assertThat(rejected.getClarificationQuestion()).isEqualTo("暂时无法获取超过30天的消息，您可手动把消息复制发给我。");
    }

    @Test
    void staleStructuredTimeRangeDefersToLlmWhenSelectionStillHasNaturalTimePhrase() throws Exception {
        ReactAgent repairAgent = mock(ReactAgent.class);
        when(repairAgent.invoke(any(String.class), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                new AssistantMessage("""
                        {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"oc_1","timeRange":"5月7号凌晨2点到2点06分","start_time":"2026-05-07T02:00:00+08:00","end_time":"2026-05-07T02:06:00+08:00","selectionInstruction":"根据5月7号凌晨2点到2点06分的群消息来总结","query":""}],"reason":"resolved from latest user instruction","clarificationQuestion":""}
                        """)
        ))));
        ContextAcquisitionPlanTimeWindowResolver resolver =
                new ContextAcquisitionPlanTimeWindowResolver(repairAgent, new ObjectMapper());

        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(List.of(ContextSourceRequest.builder()
                        .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                        .chatId("oc_1")
                        .timeRange("2025-05-07 02:00 - 02:06")
                        .selectionInstruction("根据5月7号凌晨2点到2点06分的群消息来总结")
                        .build()))
                .reason("intent source scope requires context acquisition")
                .clarificationQuestion("")
                .build();

        ContextAcquisitionPlan repaired = resolver.ensureExecutable(
                "task-stale",
                "根据5月7号凌晨2点到2点06分的群消息来总结",
                null,
                plan
        );

        assertThat(repaired.isNeedCollection()).isTrue();
        assertThat(repaired.getSources().get(0).getStartTime()).isEqualTo("2026-05-07T02:00:00+08:00");
        assertThat(repaired.getSources().get(0).getEndTime()).isEqualTo("2026-05-07T02:06:59+08:00");
        verify(repairAgent).invoke(any(String.class), any(RunnableConfig.class));
    }

    @Test
    void resolvesAgentOutputWhenMessagesAreReturnedAsIterable() throws Exception {
        ReactAgent repairAgent = mock(ReactAgent.class);
        when(repairAgent.invoke(any(String.class), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                "messages",
                List.of(
                        new AssistantMessage("thinking"),
                        new AssistantMessage("""
                                {"needCollection":true,"sources":[{"sourceType":"IM_MESSAGE_SEARCH","chatId":"oc_1","timeRange":"5月7号凌晨2点到2点06分","startTime":"2026-05-07T02:00:00+08:00","endTime":"2026-05-07T02:06:00+08:00","selectionInstruction":"根据5月7号凌晨2点到2点06分的群消息来总结","query":""}],"reason":"resolved from latest user instruction","clarificationQuestion":""}
                                """)
                )
        ))));
        ContextAcquisitionPlanTimeWindowResolver resolver =
                new ContextAcquisitionPlanTimeWindowResolver(repairAgent, new ObjectMapper());

        ContextAcquisitionPlan plan = ContextAcquisitionPlan.builder()
                .needCollection(true)
                .sources(List.of(ContextSourceRequest.builder()
                        .sourceType(ContextSourceTypeEnum.IM_MESSAGE_SEARCH)
                        .chatId("oc_1")
                        .timeRange("2025-05-07 02:00 - 02:06")
                        .selectionInstruction("根据5月7号凌晨2点到2点06分的群消息来总结")
                        .build()))
                .reason("intent source scope requires context acquisition")
                .clarificationQuestion("")
                .build();

        ContextAcquisitionPlan repaired = resolver.ensureExecutable(
                "task-iterable",
                "根据5月7号凌晨2点到2点06分的群消息来总结",
                null,
                plan
        );

        assertThat(repaired.isNeedCollection()).isTrue();
        assertThat(repaired.getSources().get(0).getStartTime()).isEqualTo("2026-05-07T02:00:00+08:00");
        assertThat(repaired.getSources().get(0).getEndTime()).isEqualTo("2026-05-07T02:06:59+08:00");
    }
}
