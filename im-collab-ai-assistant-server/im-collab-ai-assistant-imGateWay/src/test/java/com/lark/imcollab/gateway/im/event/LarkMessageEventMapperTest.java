package com.lark.imcollab.gateway.im.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageEventMapperTest {

    private final LarkMessageEventMapper mapper = new LarkMessageEventMapper(new ObjectMapper());

    @Test
    void keepsRawContentWhileParsingReadableText() {
        String rawContent = "{\"text\":\"这个是文档链接：https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph\"}";
        EventMessage message = new EventMessage();
        message.setMessageId("om_1");
        message.setChatId("oc_1");
        message.setChatType("p2p");
        message.setMessageType("text");
        message.setContent(rawContent);

        UserId senderId = new UserId();
        senderId.setOpenId("ou_1");
        EventSender sender = new EventSender();
        sender.setSenderId(senderId);

        P2MessageReceiveV1Data event = new P2MessageReceiveV1Data();
        event.setMessage(message);
        event.setSender(sender);

        Header header = new Header();
        header.setEventType("im.message.receive_v1");
        header.setEventId("event-1");

        P2MessageReceiveV1 root = new P2MessageReceiveV1();
        root.setHeader(header);
        root.setEvent(event);

        Optional<LarkMessageEvent> result = mapper.fromSdkEvent(root);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("这个是文档链接：https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph");
        assertThat(result.orElseThrow().rawContent()).isEqualTo(rawContent);
    }
}
