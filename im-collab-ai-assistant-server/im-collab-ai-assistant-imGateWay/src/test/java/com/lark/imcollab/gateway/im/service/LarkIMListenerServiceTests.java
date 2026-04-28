package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
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

    private static final String RECEIPT_TEXT = "任务已收到，正在处理。\n请稍等，我会先分析并继续回复你。\n";

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
                "p2p",
                "text",
                "生成周报",
                "ou_1",
                "1773491924409",
                true
        ));

        assertThat(status.running()).isTrue();
        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).inputSource()).isEqualTo(InputSourceEnum.LARK_PRIVATE_CHAT);
        assertThat(dispatcher.messages.get(0).content()).isEqualTo("生成周报");
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
                streamService
        );
        service.start();

        subscriptionTool.emit(new LarkMessageEvent(
                "evt-2",
                "om_2",
                "oc_group",
                "group",
                "text",
                "生成方案",
                "ou_2",
                "1773491924410",
                true
        ));

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).inputSource()).isEqualTo(InputSourceEnum.LARK_GROUP);
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
                "group",
                "text",
                "普通群消息",
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
                "group",
                "text",
                "帮我生成周报",
                "ou_5",
                "1773491924413",
                true
        ));
        subscriptionTool.emit(new LarkMessageEvent(
                "evt-p2p",
                "om_p2p",
                "oc_p2p",
                "p2p",
                "text",
                "帮我生成周报",
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
                "p2p",
                "text",
                "你好",
                "ou_3",
                "1773491924411",
                true
        ));

        assertThat(status.running()).isTrue();
        assertThat(replyTool.openId).isEqualTo("ou_3");
        assertThat(replyTool.privateSendCount).isEqualTo(1);
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
        public LarkBotMessageResult sendPrivateText(String openId, String text) {
            this.openId = openId;
            this.privateText = text;
            this.privateSendCount++;
            return new LarkBotMessageResult("om_bot", "oc_p2p", "1773491924411");
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

        @Override
        public void dispatch(LarkInboundMessage message) {
            messages.add(message);
        }
    }
}
