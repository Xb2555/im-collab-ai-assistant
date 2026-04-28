package com.lark.imcollab.skills.lark.im;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LarkMessageReplyToolTests {

    @Test
    void shouldDelegateReplyToBotClientWithIdempotencyKey() {
        LarkBotMessageClient messageClient = mock(LarkBotMessageClient.class);
        LarkMessageReplyTool tool = new LarkMessageReplyTool(messageClient);

        tool.replyText("om_1", "task received", "idem-1");

        verify(messageClient).replyText("om_1", "task received", "idem-1");
    }

    @Test
    void shouldDelegatePrivateSendToBotClientWithIdempotencyKey() {
        LarkBotMessageClient messageClient = mock(LarkBotMessageClient.class);
        LarkMessageReplyTool tool = new LarkMessageReplyTool(messageClient);

        tool.sendPrivateText("ou_1", "plan ready", "idem-2");

        verify(messageClient).sendTextToOpenId("ou_1", "plan ready", "idem-2");
    }
}
