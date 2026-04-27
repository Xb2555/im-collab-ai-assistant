package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStartRequest;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

        mockMvc.perform(post("/api/im/listener/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileName":"profile-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value("profile-123"))
                .andExpect(jsonPath("$.running").value(true));

        mockMvc.perform(get("/api/im/listener/status")
                        .param("profileName", "profile-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value("profile-123"))
                .andExpect(jsonPath("$.running").value(true));

        mockMvc.perform(post("/api/im/listener/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileName":"profile-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value("profile-123"))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void shouldMapInvalidRequestToUsefulFailedResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMListenerController(new InvalidRequestListenerService()))
                .setControllerAdvice(new LarkIMListenerExceptionHandler())
                .build();

        mockMvc.perform(post("/api/im/listener/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.event").value("im_listener_failed"))
                .andExpect(jsonPath("$.errorType").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("profileName must be provided"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static final class StubListenerService extends LarkIMListenerService {

        StubListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse start(LarkIMListenerStartRequest request) {
            return new LarkIMListenerStatusResponse(request.profileName(), true, "running", null, null);
        }

        @Override
        public LarkIMListenerStatusResponse stop(LarkIMListenerStartRequest request) {
            return new LarkIMListenerStatusResponse(request.profileName(), false, "stopped", null, null);
        }

        @Override
        public LarkIMListenerStatusResponse status(String profileName) {
            return new LarkIMListenerStatusResponse(profileName, true, "running", null, null);
        }
    }

    private static final class InvalidRequestListenerService extends LarkIMListenerService {

        InvalidRequestListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse start(LarkIMListenerStartRequest request) {
            throw new IllegalArgumentException("profileName must be provided");
        }
    }
}
