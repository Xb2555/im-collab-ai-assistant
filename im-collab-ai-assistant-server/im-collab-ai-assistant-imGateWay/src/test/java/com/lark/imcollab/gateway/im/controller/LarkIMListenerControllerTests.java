package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkIMListenerControllerTests {

    @Test
    void shouldExposeStartStopAndStatusEndpoints() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMListenerController(new StubListenerService()))
                .build();

        mockMvc.perform(post("/api/im/listener/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));

        mockMvc.perform(get("/api/im/listener/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));

        mockMvc.perform(post("/api/im/listener/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
    }

    private static final class StubListenerService extends LarkIMListenerService {

        StubListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse start() {
            return new LarkIMListenerStatusResponse(true, "running", null, null);
        }

        @Override
        public LarkIMListenerStatusResponse stop() {
            return new LarkIMListenerStatusResponse(false, "stopped", null, null);
        }

        @Override
        public LarkIMListenerStatusResponse status() {
            return new LarkIMListenerStatusResponse(true, "running", null, null);
        }
    }
}
