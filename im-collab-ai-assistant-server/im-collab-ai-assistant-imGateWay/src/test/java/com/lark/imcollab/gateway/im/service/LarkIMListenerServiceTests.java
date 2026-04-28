package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import com.lark.imcollab.skills.lark.im.LarkBotMessageResult;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerServiceTests {

    private static final String RECEIPT_TEXT =
            "\u4efb\u52a1\u5df2\u6536\u5230\uff0c\u6b63\u5728\u5904\u7406\u3002\n\u8bf7\u7a0d\u7b49\uff0c\u6211\u4f1a\u5148\u5206\u6790\u5e76\u7ee7\u7eed\u56de\u590d\u4f60\u3002";

    @Test
    void shouldStartListenerAndDispatchIncomingMessageWithReceiptReply() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher);

        LarkIMListenerStatusResponse status = service.start();
        subscriptionTool.emit(new LarkMessageEvent(
                "evt-1",
                "om_1",
                "oc_p2p",
                null,
                "p2p",
                "text",
                "generate weekly report",
                "ou_1",
                "1773491924409",
                true
        ));

        assertThat(status.running()).isTrue();
        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).inputSource()).isEqualTo(InputSourceEnum.LARK_PRIVATE_CHAT);
        assertThat(dispatcher.messages.get(0).content()).isEqualTo("generate weekly report");
        assertThat(replyTool.openId).isEqualTo("ou_1");
        assertThat(replyTool.privateText).isEqualTo(RECEIPT_TEXT);
        assertThat(replyTool.privateSendCount).isEqualTo(1);
        assertThat(replyTool.replyCount).isZero();
    }

    @Test
    void shouldMapGroupMessageToGroupInputSource() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingStreamService streamService = new RecordingStreamService();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                dispatcher,
                streamService,
                null
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-2",
                "om_2",
                "oc_group",
                "thread-2",
                "group",
                "text",
                "generate proposal",
                "ou_2",
                "1773491924410",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).inputSource()).isEqualTo(InputSourceEnum.LARK_GROUP);
        assertThat(dispatcher.messages.get(0).threadId()).isEqualTo("thread-2");
        assertThat(replyTool.replyCount).isEqualTo(1);
        assertThat(replyTool.privateSendCount).isZero();
        assertThat(streamService.sourceEvent.messageId()).isEqualTo("om_2");
        assertThat(streamService.text).isEqualTo(RECEIPT_TEXT);
    }

    @Test
    void shouldIgnoreGroupMessageWithoutMentionForAgentListener() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher);
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-4",
                "om_4",
                "oc_group",
                null,
                "group",
                "text",
                "normal group message",
                "ou_4",
                "1773491924412",
                false
        ));

        assertThat(dispatcher.messages).isEmpty();
        assertThat(replyTool.replyCount).isZero();
        assertThat(replyTool.privateSendCount).isZero();
    }

    @Test
    void shouldIgnoreMirroredP2PEventAfterGroupMention() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher);
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-group",
                "om_group",
                "oc_group",
                null,
                "group",
                "text",
                "help me generate a weekly report",
                "ou_5",
                "1773491924413",
                true
        ));
        subscriptionTool.emit(new LarkMessageEvent(
                "evt-p2p",
                "om_p2p",
                "oc_p2p",
                null,
                "p2p",
                "text",
                "help me generate a weekly report",
                "ou_5",
                "1773491924414",
                false
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).chatId()).isEqualTo("oc_group");
        assertThat(replyTool.replyCount).isEqualTo(1);
        assertThat(replyTool.privateSendCount).isZero();
    }

    @Test
    void shouldStartListenerWithDefaultCliConfiguration() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                new RecordingDispatcher()
        );

        LarkIMListenerStatusResponse status = service.start();
        subscriptionTool.emit(new LarkMessageEvent(
                "evt-3",
                "om_3",
                "oc_p2p",
                null,
                "p2p",
                "text",
                "hello",
                "ou_3",
                "1773491924411",
                true
        ));

        assertThat(status.running()).isTrue();
        assertThat(replyTool.openId).isEqualTo("ou_3");
        assertThat(replyTool.privateSendCount).isEqualTo(1);
    }

    @Test
    void shouldKeepPlannerDispatchWhenReceiptReplyFails() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        ThrowingReplyTool replyTool = new ThrowingReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingRetryService retryService = new RecordingRetryService();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher, null, retryService);
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-5",
                "om_5",
                "oc_p2p",
                "thread-5",
                "p2p",
                "text",
                "please summarize the weekly progress",
                "ou_5",
                "1773491924415",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).threadId()).isEqualTo("thread-5");
        assertThat(replyTool.privateSendCount).isEqualTo(1);
        assertThat(retryService.privateOpenId).isEqualTo("ou_5");
        assertThat(retryService.privateText).isEqualTo(RECEIPT_TEXT);
    }

    @Test
    void shouldSkipReceiptWhenPlannerNeedsClarification() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.nextSession = PlanTaskSession.builder()
                .taskId("task-ask")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        RecordingStreamService streamService = new RecordingStreamService();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                dispatcher,
                streamService,
                null
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-6",
                "om_6",
                "oc_p2p",
                "thread-6",
                "p2p",
                "text",
                "need a boss briefing",
                "ou_6",
                "1773491924416",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(replyTool.privateSendCount).isZero();
        assertThat(replyTool.replyCount).isZero();
        assertThat(streamService.sourceEvent).isNull();
    }

    @Test
    void shouldSkipReceiptWhenPlannerIsAlreadyPlanReady() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.nextSession = PlanTaskSession.builder()
                .taskId("task-plan-ready")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        RecordingStreamService streamService = new RecordingStreamService();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                dispatcher,
                streamService,
                null
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-7",
                "om_7",
                "oc_p2p",
                "thread-7",
                "p2p",
                "text",
                "continue planning",
                "ou_7",
                "1773491924417",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(replyTool.privateSendCount).isZero();
        assertThat(replyTool.replyCount).isZero();
        assertThat(streamService.sourceEvent).isNull();
    }

    @Test
    void shouldSkipReceiptWhenPlannerAbortedTask() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.nextSession = PlanTaskSession.builder()
                .taskId("task-aborted")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();
        RecordingStreamService streamService = new RecordingStreamService();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                dispatcher,
                streamService,
                null
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-abort",
                "om_abort",
                "oc_p2p",
                "thread-abort",
                "p2p",
                "text",
                "\u53d6\u6d88\u4efb\u52a1",
                "ou_abort",
                "1773491924419",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(replyTool.privateSendCount).isZero();
        assertThat(replyTool.replyCount).isZero();
        assertThat(streamService.sourceEvent).isNull();
    }

    @Test
    void shouldIgnoreDuplicateInboundMessageByEventId() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher);
        service.start();

        LarkMessageEvent duplicateEvent = new LarkMessageEvent(
                "evt-dup-1",
                "om_dup_1",
                "oc_p2p",
                "thread-dup",
                "p2p",
                "text",
                "duplicate inbound",
                "ou_dup",
                "1773491924418",
                true
        );
        subscriptionTool.emit(duplicateEvent);
        subscriptionTool.emit(duplicateEvent);

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(replyTool.privateSendCount).isEqualTo(1);
    }

    @Test
    void shouldIgnoreStaleMessageReplayedOnStartup() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerProperties properties = new LarkIMListenerProperties();
        properties.setSuppressStartupReplayEnabled(true);
        properties.setStartupReplayGracePeriodMillis(1_000L);
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                dispatcher,
                null,
                null,
                properties
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-stale-1",
                "om_stale_1",
                "oc_p2p",
                "thread-stale",
                "p2p",
                "text",
                "old message should not start task",
                "ou_stale",
                String.valueOf(System.currentTimeMillis() - 60_000L),
                true
        ));

        assertThat(dispatcher.messages).isEmpty();
        assertThat(replyTool.privateSendCount).isZero();
    }

    private static final class StubSubscriptionTool extends LarkMessageEventSubscriptionService {

        private Consumer<LarkMessageEvent> consumer;

        StubSubscriptionTool() {
            super(null);
        }

        @Override
        public LarkEventSubscriptionStatus startMessageSubscription(
                String consumerId,
                Consumer<LarkMessageEvent> messageConsumer
        ) {
            this.consumer = messageConsumer;
            return new LarkEventSubscriptionStatus(true, "running", null, null);
        }

        private void emit(LarkMessageEvent event) {
            consumer.accept(event);
        }
    }

    private static final class StubReplyTool extends LarkMessageReplyTool {

        private String messageId;
        private String text;
        private String openId;
        private String privateText;
        private int replyCount;
        private int privateSendCount;

        StubReplyTool() {
            super(null);
        }

        @Override
        public void replyText(String messageId, String text) {
            this.messageId = messageId;
            this.text = text;
            this.replyCount++;
        }

        @Override
        public void replyText(String messageId, String text, String idempotencyKey) {
            replyText(messageId, text);
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text) {
            this.openId = openId;
            this.privateText = text;
            this.privateSendCount++;
            return new LarkBotMessageResult("om_bot", "oc_p2p", "1773491924411");
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text, String idempotencyKey) {
            return sendPrivateText(openId, text);
        }
    }

    private static final class ThrowingReplyTool extends LarkMessageReplyTool {

        private int privateSendCount;

        ThrowingReplyTool() {
            super(null);
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text) {
            this.privateSendCount++;
            throw new IllegalStateException("receipt failed");
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text, String idempotencyKey) {
            return sendPrivateText(openId, text);
        }
    }

    private static final class RecordingStreamService extends LarkIMMessageStreamService {

        private LarkMessageEvent sourceEvent;
        private String text;

        RecordingStreamService() {
            super(null, null);
        }

        @Override
        void publishBotReply(LarkMessageEvent sourceEvent, String text) {
            this.sourceEvent = sourceEvent;
            this.text = text;
        }
    }

    private static final class RecordingDispatcher implements LarkInboundMessageDispatcher {

        private final List<LarkInboundMessage> messages = new java.util.ArrayList<>();
        private PlanTaskSession nextSession = PlanTaskSession.builder()
                .taskId("task-default")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();

        @Override
        public PlanTaskSession dispatch(LarkInboundMessage message) {
            messages.add(message);
            return nextSession;
        }
    }

    private static final class RecordingRetryService extends LarkOutboundMessageRetryService {

        private String privateOpenId;
        private String privateText;

        RecordingRetryService() {
            super(new StubReplyTool(), 1L, 1L, 1);
        }

        @Override
        public void enqueuePrivateText(String openId, String text, String idempotencyKey) {
            this.privateOpenId = openId;
            this.privateText = text;
        }
    }
}
