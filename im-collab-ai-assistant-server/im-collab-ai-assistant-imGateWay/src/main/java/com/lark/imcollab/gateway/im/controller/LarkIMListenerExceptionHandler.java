package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.dto.LarkIMListenerFailedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LarkIMListenerController.class)
public class LarkIMListenerExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public LarkIMListenerFailedResponse invalidRequest(IllegalArgumentException exception) {
        return failed("invalid_request", exception, false);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(IllegalStateException.class)
    public LarkIMListenerFailedResponse listenerError(IllegalStateException exception) {
        return failed("lark_listener_error", exception, false);
    }

    private LarkIMListenerFailedResponse failed(String errorType, RuntimeException exception, boolean retryable) {
        return new LarkIMListenerFailedResponse(
                "im_listener_failed",
                errorType,
                readableMessage(exception),
                retryable
        );
    }

    private String readableMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
