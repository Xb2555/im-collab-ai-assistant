package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.facade.PlannerFacade;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingLarkInboundMessageDispatcher implements LarkInboundMessageDispatcher {

    private final PlannerFacade plannerFacade;

    @Override
    public void dispatch(LarkInboundMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            log.warn("Inbound message ignored: empty content messageId={}", message == null ? null : message.messageId());
            return;
        }

        Conversation conversation = Conversation.builder()
                .conversationId(UUID.randomUUID().toString())
                .userId(message.senderOpenId())
                .chatId(message.chatId())
                .rawMessage(message.content())
                .messageId(message.messageId())
                .receivedAt(Instant.now())
                .build();

        Task task = plannerFacade.plan(conversation);
        log.info("IM message dispatched to planner: messageId={}, taskId={}, status={}",
                message.messageId(), task.getTaskId(), task.getStatus());
    }
}
