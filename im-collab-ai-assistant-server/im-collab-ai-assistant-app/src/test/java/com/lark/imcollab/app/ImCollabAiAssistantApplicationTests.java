package com.lark.imcollab.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=test-key",
        "imcollab.store.redisson.enabled=false",
        "imcollab.gateway.im.listener.auto-start-enabled=false",
        "imcollab.gateway.auth.jwt-secret=test-secret-with-enough-length"
})
class ImCollabAiAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
