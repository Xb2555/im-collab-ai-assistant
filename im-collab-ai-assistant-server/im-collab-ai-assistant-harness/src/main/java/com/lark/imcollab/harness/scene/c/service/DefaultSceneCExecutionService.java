package com.lark.imcollab.harness.scene.c.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.harness.scene.c.support.SceneCExecutionGuard;
import com.lark.imcollab.harness.scene.c.support.SceneCExecutionSupport;
import com.lark.imcollab.harness.scene.c.workflow.SceneCStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultSceneCExecutionService implements SceneCExecutionService {

    private final CompiledGraph sceneCDocWorkflow;
    private final PlannerRuntimeFacade plannerRuntimeFacade;
    private final SceneCExecutionGuard executionGuard;

    public DefaultSceneCExecutionService(
            @Qualifier("sceneCDocWorkflow") CompiledGraph sceneCDocWorkflow,
            PlannerRuntimeFacade plannerRuntimeFacade,
            SceneCExecutionGuard executionGuard) {
        this.sceneCDocWorkflow = sceneCDocWorkflow;
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
                        .orElseThrow(() -> new IllegalArgumentException("No resumable scene C card")));
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
        Optional<StateSnapshot> snapshot = sceneCDocWorkflow.stateOf(config);
        Map<String, Object> initialState = new HashMap<>();
        if (snapshot.isPresent()) {
            initialState.putAll(snapshot.get().state().data());
        }
        initialState.put(SceneCStateKeys.TASK_ID, taskId);
        initialState.put(SceneCStateKeys.CARD_ID, cardId);
        initialState.put(SceneCStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        sceneCDocWorkflow.invoke(new OverAllState(initialState), config);
    }
}
