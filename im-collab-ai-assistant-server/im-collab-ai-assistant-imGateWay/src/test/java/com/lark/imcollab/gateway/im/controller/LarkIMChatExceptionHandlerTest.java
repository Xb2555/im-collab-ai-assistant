package com.lark.imcollab.gateway.im.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMChatExceptionHandlerTest {

    @Test
    void asyncRequestTimeoutReturnsNoBodyForSseStreams() {
        LarkIMChatExceptionHandler handler = new LarkIMChatExceptionHandler();

        var response = handler.asyncRequestTimeout();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
