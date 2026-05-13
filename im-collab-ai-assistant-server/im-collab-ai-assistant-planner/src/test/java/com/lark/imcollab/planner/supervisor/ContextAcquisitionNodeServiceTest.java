package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ContextAcquisitionNodeServiceTest {

    @Test
    void preservesPlanClarificationWhenCollectionIsDisabled() {
        PlannerContextAcquisitionTool acquisitionTool = mock(PlannerContextAcquisitionTool.class);
        ContextAcquisitionNodeService service = new ContextAcquisitionNodeService(acquisitionTool);

        ContextCollectionOutcome outcome = service.collect(
                "task-1",
                "根据5月7号凌晨2点到2点06分的群消息来总结",
                WorkspaceContext.builder().chatId("oc_1").build(),
                ContextAcquisitionPlan.builder()
                        .needCollection(false)
                        .sources(List.of())
                        .reason("ambiguous time range")
                        .clarificationQuestion("请补充具体开始和结束时间。")
                        .build()
        );

        assertThat(outcome.contextResult().sufficient()).isFalse();
        assertThat(outcome.contextResult().clarificationQuestion()).isEqualTo("请补充具体开始和结束时间。");
        verify(acquisitionTool, never()).acquireContext(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
