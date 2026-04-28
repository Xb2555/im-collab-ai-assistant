package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionTaskDispatcherTests {

    @Test
    void shouldRouteDocCardsToDocumentExecution() {
        DocumentExecutionService documentExecutionService = mock(DocumentExecutionService.class);
        PresentationExecutionService presentationExecutionService = mock(PresentationExecutionService.class);
        ExecutionTaskDispatcher dispatcher = new ExecutionTaskDispatcher(documentExecutionService, presentationExecutionService);

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-doc")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();

        when(documentExecutionService.execute("task-1", "card-doc", null)).thenReturn(session);

        dispatcher.dispatch(session);

        verify(documentExecutionService).execute("task-1", "card-doc", null);
    }
}
