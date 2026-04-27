package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.skills.lark.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.skills.lark.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.event.LarkMessageEventSubscriptionTool;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMListenerServiceTests {

    @Test
    void shouldStartListenerAndDispatchIncomingMessageWithReceiptReply() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, replyTool, dispatcher);

        LarkIMListenerStatusResponse status = service.start(new LarkIMListenerStartRequest("profile-123"));
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

        assertThat(status.profileName()).isEqualTo("profile-123");
        assertThat(status.running()).isTrue();
        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.get(0).inputSource()).isEqualTo(InputSourceEnum.LARK_PRIVATE_CHAT);
        assertThat(dispatcher.messages.get(0).content()).isEqualTo("生成周报");
        assertThat(replyTool.profileName).isEqualTo("profile-123");
        assertThat(replyTool.messageId).isEqualTo("om_1");
        assertThat(replyTool.text).isEqualTo("任务已收到，正在处理");
    }

    @Test
    void shouldMapGroupMessageToGroupInputSource() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        LarkIMListenerService service = new LarkIMListenerService(subscriptionTool, new StubReplyTool(), dispatcher);
        service.start(new LarkIMListenerStartRequest("profile-123"));

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
    }

    @Test
    void shouldStartDefaultListenerWithoutExplicitProfile() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        StubReplyTool replyTool = new StubReplyTool();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                replyTool,
                new RecordingDispatcher()
        );

        LarkIMListenerStatusResponse status = service.startDefault(null);
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

        assertThat(status.profileName()).isEqualTo("default");
        assertThat(subscriptionTool.profileName).isNull();
        assertThat(replyTool.profileName).isNull();
        assertThat(replyTool.messageId).isEqualTo("om_3");
    }

    @Test
    void shouldStartDefaultListenerWithConfiguredProfile() {
        StubSubscriptionTool subscriptionTool = new StubSubscriptionTool();
        LarkIMListenerService service = new LarkIMListenerService(
                subscriptionTool,
                new StubReplyTool(),
                new RecordingDispatcher()
        );

        LarkIMListenerStatusResponse status = service.startDefault("imcollab-demo-app");

        assertThat(status.profileName()).isEqualTo("imcollab-demo-app");
        assertThat(subscriptionTool.profileName).isEqualTo("imcollab-demo-app");
    }

    private static final class StubSubscriptionTool extends LarkMessageEventSubscriptionTool {

        private Consumer<LarkMessageEvent> consumer;
        private String profileName;

        StubSubscriptionTool() {
            super(null);
        }

        @Override
        public LarkEventSubscriptionStatus startMessageSubscription(
                String profileName,
                Consumer<LarkMessageEvent> messageConsumer
        ) {
            this.profileName = profileName;
            this.consumer = messageConsumer;
            return new LarkEventSubscriptionStatus(profileName == null ? "default" : profileName, true, "running",
                    null, null);
        }

        private void emit(LarkMessageEvent event) {
            consumer.accept(event);
        }
    }

    private static final class StubReplyTool extends LarkMessageReplyTool {

        private String profileName;
        private String messageId;
        private String text;

        StubReplyTool() {
            super(null);
        }

        @Override
        public void replyText(String profileName, String messageId, String text) {
            this.profileName = profileName;
            this.messageId = messageId;
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
