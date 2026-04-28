package com.lark.imcollab;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=test-key",
        "imcollab.store.redisson.enabled=false",
        "imcollab.gateway.im.listener.auto-start-enabled=false",
        "imcollab.gateway.auth.jwt-secret=test-secret-with-enough-length"
}, classes = GatewayModuleTestApplication.class)
class ImCollabAiAssistantGatewayApplicationTests {

    @MockBean
    private PlannerPlanFacade plannerPlanFacade;

    @Test
    void contextLoads() {
    }
}
