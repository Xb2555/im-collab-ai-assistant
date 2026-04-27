package com.lark.imcollab.skills.lark.event;

import java.util.function.Consumer;

public interface LarkMessageEventConnectionFactory {

    LarkMessageEventConnection start(Consumer<LarkMessageEvent> messageConsumer);
}
