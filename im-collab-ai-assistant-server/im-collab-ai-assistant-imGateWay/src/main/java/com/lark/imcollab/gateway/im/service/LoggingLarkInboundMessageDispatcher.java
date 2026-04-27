package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingLarkInboundMessageDispatcher implements LarkInboundMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingLarkInboundMessageDispatcher.class);
    private static final String WORKSPACE_SELECTION_TYPE = "MESSAGE";

    private final PlannerPlanFacade plannerPlanFacade;

    public LoggingLarkInboundMessageDispatcher(PlannerPlanFacade plannerPlanFacade) {
        this.plannerPlanFacade = plannerPlanFacade;
    }

    @Override
    public void dispatch(LarkInboundMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            log.warn("Scenario A inbound message ignored because content is empty: messageId={}, chatId={}",
                    message == null ? null : message.messageId(),
                    message == null ? null : message.chatId());
            return;
        }

        PlanTaskSession session = plannerPlanFacade.plan(
                message.content(),
                buildWorkspaceContext(message),
                null,
                null
        );
        log.info("Scenario A inbound Lark message bridged to planner: messageId={}, chatId={}, taskId={}, phase={}",
                message.messageId(), message.chatId(), session.getTaskId(), session.getPlanningPhase());
    }

    private WorkspaceContext buildWorkspaceContext(LarkInboundMessage message) {
        return WorkspaceContext.builder()
                .selectionType(WORKSPACE_SELECTION_TYPE)
                .timeRange(message.createTime())
                .selectedMessages(message.content() == null || message.content().isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(message.content()))
                .build();
    }
}
