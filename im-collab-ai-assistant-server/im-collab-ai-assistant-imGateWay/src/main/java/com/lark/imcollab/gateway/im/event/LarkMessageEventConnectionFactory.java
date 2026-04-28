package com.lark.imcollab.gateway.im.event;

import java.util.function.Consumer;

public interface LarkMessageEventConnectionFactory {

    LarkMessageEventConnection start(Consumer<LarkMessageEvent> messageConsumer);
}
