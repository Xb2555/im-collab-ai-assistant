package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMMessageStreamService;
import com.lark.imcollab.gateway.im.service.LarkIMUnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkIMMessageStreamControllerTests {

    @Test
    void shouldExposeSseStreamEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMMessageStreamController(new StubStreamService()))
                .setControllerAdvice(new LarkIMChatExceptionHandler())
                .build();

        mockMvc.perform(get("/api/im/chats/oc_123/messages/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        GetMapping mapping = LarkIMMessageStreamController.class
                .getMethod("streamMessages", String.class, String.class)
                .getAnnotation(GetMapping.class);
        assertThat(mapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void shouldMapUnauthorizedAndInvalidRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMMessageStreamController(new ErrorStreamService()))
                .setControllerAdvice(new LarkIMChatExceptionHandler())
                .build();

        mockMvc.perform(get("/api/im/chats/oc_123/messages/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(get("/api/im/chats/invalid/messages/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private static class StubStreamService extends LarkIMMessageStreamService {

        StubStreamService() {
            super(null, null);
        }

        @Override
        public SseEmitter subscribe(String authorization, String chatId) {
            return new SseEmitter();
        }
    }

    private static final class ErrorStreamService extends StubStreamService {

        @Override
        public SseEmitter subscribe(String authorization, String chatId) {
            if (authorization == null || authorization.isBlank()) {
                throw new LarkIMUnauthorizedException("Unauthorized");
            }
            if ("invalid".equals(chatId)) {
                throw new IllegalArgumentException("chatId must be provided");
            }
            return new SseEmitter();
        }
    }
}
