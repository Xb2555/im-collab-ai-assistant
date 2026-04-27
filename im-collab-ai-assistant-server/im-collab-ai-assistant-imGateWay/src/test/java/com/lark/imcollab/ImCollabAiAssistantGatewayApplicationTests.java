package com.lark.imcollab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "imcollab.gateway.im.listener.auto-start-enabled=false",
        "imcollab.gateway.auth.jwt-secret=test-secret-with-enough-length"
})
class ImCollabAiAssistantGatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
