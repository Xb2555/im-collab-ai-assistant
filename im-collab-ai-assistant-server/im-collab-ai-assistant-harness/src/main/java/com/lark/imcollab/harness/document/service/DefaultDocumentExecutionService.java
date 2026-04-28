package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultDocumentExecutionService implements DocumentExecutionService {

    private final CompiledGraph documentWorkflow;
    private final PlannerRuntimeFacade plannerRuntimeFacade;
    private final DocumentExecutionGuard executionGuard;

    public DefaultDocumentExecutionService(
            @Qualifier("documentWorkflow") CompiledGraph documentWorkflow,
            PlannerRuntimeFacade plannerRuntimeFacade,
            DocumentExecutionGuard executionGuard) {
        this.documentWorkflow = documentWorkflow;
        this.plannerRuntimeFacade = plannerRuntimeFacade;
        this.executionGuard = executionGuard;
    }

    @Override
    public PlanTaskSession execute(String taskId, String cardId, String userFeedback) {
        executionGuard.execute(taskId + ":" + cardId, () -> runWorkflow(taskId, cardId, userFeedback));
        return plannerRuntimeFacade.getSession(taskId);
    }

    @Override
    public PlanTaskSession resume(String taskId, String userFeedback) {
        PlanTaskSession session = plannerRuntimeFacade.getSession(taskId);
        UserPlanCard card = session.getPlanCards().stream()
                .filter(item -> item.getType() == PlanCardTypeEnum.DOC)
                .filter(item -> "BLOCKED".equals(item.getStatus()) || "RUNNING".equals(item.getStatus()))
                .findFirst()
                .orElseGet(() -> session.getPlanCards().stream()
                        .filter(item -> item.getType() == PlanCardTypeEnum.DOC)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No resumable document card")));
        executionGuard.execute(taskId + ":" + card.getCardId(), () -> runWorkflow(taskId, card.getCardId(), userFeedback));
        return plannerRuntimeFacade.getSession(taskId);
    }

    @Override
    public PlanTaskSession interrupt(String taskId) {
        PlanTaskSession session = plannerRuntimeFacade.getSession(taskId);
        session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        session.setAborted(true);
        session.setTransitionReason("Harness interrupted");
        plannerRuntimeFacade.saveSession(session);
        plannerRuntimeFacade.publishEvent(taskId, "ABORTED");
        return session;
    }

    private void runWorkflow(String taskId, String cardId, String userFeedback) {
        RunnableConfig config = RunnableConfig.builder()
                .threadId(taskId + ":" + cardId)
                .build();
        Optional<StateSnapshot> snapshot = documentWorkflow.stateOf(config);
        Map<String, Object> initialState = new HashMap<>();
        if (snapshot.isPresent()) {
            initialState.putAll(snapshot.get().state().data());
        }
        initialState.put(DocumentStateKeys.TASK_ID, taskId);
        initialState.put(DocumentStateKeys.CARD_ID, cardId);
        initialState.put(DocumentStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        documentWorkflow.invoke(new OverAllState(initialState), config);
    }
}
