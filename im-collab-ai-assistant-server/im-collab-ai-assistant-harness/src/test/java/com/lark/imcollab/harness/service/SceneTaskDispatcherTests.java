package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.harness.scene.c.service.SceneCExecutionService;
import com.lark.imcollab.harness.scene.d.service.SceneDExecutionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SceneTaskDispatcherTests {

    @Test
    void shouldRouteDocCardsToSceneC() {
        SceneCExecutionService sceneCExecutionService = mock(SceneCExecutionService.class);
        SceneDExecutionService sceneDExecutionService = mock(SceneDExecutionService.class);
        SceneTaskDispatcher dispatcher = new SceneTaskDispatcher(sceneCExecutionService, sceneDExecutionService);

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-doc")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();

        when(sceneCExecutionService.execute("task-1", "card-doc", null)).thenReturn(session);

        dispatcher.dispatch(session);

        verify(sceneCExecutionService).execute("task-1", "card-doc", null);
    }
}
