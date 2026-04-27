package com.lark.imcollab.skills.lark.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageEventMapperTests {

    private final LarkMessageEventMapper mapper = new LarkMessageEventMapper(new ObjectMapper());

    @Test
    void shouldMapSdkP2MessageReceiveEvent() {
        P2MessageReceiveV1 root = new P2MessageReceiveV1();
        Header header = new Header();
        header.setEventId("evt-4");
        header.setEventType("im.message.receive_v1");
        header.setCreateTime("1773491924412");
        root.setHeader(header);
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(EventMessage.newBuilder()
                .messageId("om_4")
                .chatId("oc_group")
                .chatType("group")
                .messageType("text")
                .content("{\"text\":\"生成复盘\"}")
                .createTime("1773491924413")
                .mentions(new MentionEvent[]{MentionEvent.newBuilder().name("机器人").build()})
                .build());
        data.setSender(EventSender.newBuilder()
                .senderId(UserId.newBuilder().openId("ou_4").build())
                .senderType("user")
                .build());
        root.setEvent(data);

        Optional<LarkMessageEvent> event = mapper.fromSdkEvent(root);

        assertThat(event).isPresent();
        assertThat(event.get().eventId()).isEqualTo("evt-4");
        assertThat(event.get().messageId()).isEqualTo("om_4");
        assertThat(event.get().content()).isEqualTo("生成复盘");
        assertThat(event.get().senderOpenId()).isEqualTo("ou_4");
    }
}
