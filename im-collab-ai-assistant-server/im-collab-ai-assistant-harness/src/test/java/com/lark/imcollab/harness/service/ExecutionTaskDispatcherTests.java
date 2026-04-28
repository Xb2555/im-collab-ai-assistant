package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTaskDispatcherTests {

    @Test
    void shouldRouteDocCardsToDocumentExecution() {
        RecordingDocumentExecutionService documentExecutionService = new RecordingDocumentExecutionService();
        RecordingPresentationExecutionService presentationExecutionService = new RecordingPresentationExecutionService();
        ExecutionTaskDispatcher dispatcher = new ExecutionTaskDispatcher(documentExecutionService, presentationExecutionService);

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-doc")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build();

        dispatcher.dispatch(session);

        assertThat(documentExecutionService.executedTaskId).isEqualTo("task-1");
        assertThat(documentExecutionService.executedCardId).isEqualTo("card-doc");
        assertThat(presentationExecutionService.executedCardId).isNull();
    }

    @Test
    void shouldRoutePptCardsToPresentationExecution() {
        RecordingDocumentExecutionService documentExecutionService = new RecordingDocumentExecutionService();
        RecordingPresentationExecutionService presentationExecutionService = new RecordingPresentationExecutionService();
        ExecutionTaskDispatcher dispatcher = new ExecutionTaskDispatcher(documentExecutionService, presentationExecutionService);

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-ppt")
                        .type(PlanCardTypeEnum.PPT)
                        .build()))
                .build();

        dispatcher.dispatch(session);

        assertThat(presentationExecutionService.executedTaskId).isEqualTo("task-2");
        assertThat(presentationExecutionService.executedCardId).isEqualTo("card-ppt");
        assertThat(documentExecutionService.executedCardId).isNull();
    }

    private static final class RecordingDocumentExecutionService implements DocumentExecutionService {
        private String executedTaskId;
        private String executedCardId;

        @Override
        public PlanTaskSession execute(String taskId, String cardId, String userFeedback) {
            this.executedTaskId = taskId;
            this.executedCardId = cardId;
            return PlanTaskSession.builder().taskId(taskId).build();
        }

        @Override
        public PlanTaskSession resume(String taskId, String userFeedback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanTaskSession interrupt(String taskId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingPresentationExecutionService implements PresentationExecutionService {
        private String executedTaskId;
        private String executedCardId;

        @Override
        public PlanTaskSession reserveExecution(String taskId, String cardId) {
            this.executedTaskId = taskId;
            this.executedCardId = cardId;
            return PlanTaskSession.builder().taskId(taskId).build();
        }
    }
}
