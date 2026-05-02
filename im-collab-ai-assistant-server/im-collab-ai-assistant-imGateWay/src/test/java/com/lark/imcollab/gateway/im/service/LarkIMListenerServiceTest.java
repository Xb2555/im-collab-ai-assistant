package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkIMListenerServiceTest {

    @Test
    void failedSessionDoesNotSendGenericReceipt() {
        LarkMessageEventSubscriptionService subscriptionService = mock(LarkMessageEventSubscriptionService.class);
        LarkMessageReplyTool replyTool = mock(LarkMessageReplyTool.class);
        LarkInboundMessageDispatcher dispatcher = mock(LarkInboundMessageDispatcher.class);
        ArgumentCaptor<Consumer<LarkMessageEvent>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(subscriptionService.startMessageSubscription(anyString(), consumerCaptor.capture()))
                .thenReturn(new LarkEventSubscriptionStatus(true, "running", "now", null));
        when(dispatcher.dispatch(any())).thenReturn(PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.FAILED)
                .build());

        LarkIMListenerService listener = new LarkIMListenerService(subscriptionService, replyTool, dispatcher);
        listener.start();
        consumerCaptor.getValue().accept(event());

        verify(dispatcher).dispatch(any());
        verify(replyTool, never()).sendPrivateText(anyString(), anyString(), anyString());
        verify(replyTool, never()).replyText(anyString(), anyString(), anyString());
    }

    @Test
    void botAuthoredMessageIsIgnoredBeforeDispatch() {
        LarkMessageEventSubscriptionService subscriptionService = mock(LarkMessageEventSubscriptionService.class);
        LarkMessageReplyTool replyTool = mock(LarkMessageReplyTool.class);
        LarkInboundMessageDispatcher dispatcher = mock(LarkInboundMessageDispatcher.class);
        ArgumentCaptor<Consumer<LarkMessageEvent>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(subscriptionService.startMessageSubscription(anyString(), consumerCaptor.capture()))
                .thenReturn(new LarkEventSubscriptionStatus(true, "running", "now", null));

        LarkIMListenerService listener = new LarkIMListenerService(subscriptionService, replyTool, dispatcher);
        listener.start();
        consumerCaptor.getValue().accept(new LarkMessageEvent(
                "event-bot",
                "message-bot",
                "chat-1",
                "thread-1",
                "p2p",
                "text",
                "没问题的话回复“开始执行”。",
                "ou-bot",
                "bot",
                "2026-04-30T00:00:00Z",
                false
        ));

        verify(dispatcher, never()).dispatch(any());
        verify(replyTool, never()).sendPrivateText(anyString(), anyString(), anyString());
        verify(replyTool, never()).replyText(anyString(), anyString(), anyString());
    }

    private static LarkMessageEvent event() {
        return new LarkMessageEvent(
                "event-1",
                "message-1",
                "chat-1",
                "thread-1",
                "p2p",
                "text",
                "再加一条：最后还要输出一句话总结",
                "ou-user",
                "2026-04-30T00:00:00Z",
                false
        );
    }
}
