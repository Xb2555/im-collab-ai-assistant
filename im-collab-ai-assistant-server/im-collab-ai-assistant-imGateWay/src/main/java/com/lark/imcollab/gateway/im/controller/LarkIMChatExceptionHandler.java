package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.gateway.im.client.LarkOpenApiException;
import com.lark.imcollab.gateway.im.service.LarkIMUnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice(assignableTypes = {
        LarkIMChatController.class,
        LarkIMMessageStreamController.class
})
@Slf4j
public class LarkIMChatExceptionHandler {

    @ExceptionHandler(LarkIMUnauthorizedException.class)
    public ResponseEntity<BaseResponse<?>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ResultUtils.error(BusinessCode.NOT_LOGIN_ERROR));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> asyncRequestTimeout() {
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> clientConnectionClosed(IOException exception) throws IOException {
        if (isClientConnectionClosed(exception)) {
            log.info("IM stream closed by client: message='{}'", exception.getMessage());
            return ResponseEntity.noContent().build();
        }
        throw exception;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponse<?>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ResultUtils.error(BusinessCode.PARAMS_ERROR, message(exception)));
    }

    @ExceptionHandler(LarkOpenApiException.class)
    public BaseResponse<?> larkOpenApiError(LarkOpenApiException exception) {
        return ResultUtils.error(BusinessCode.OPERATION_ERROR, message(exception));
    }

    @ExceptionHandler(IllegalStateException.class)
    public BaseResponse<?> operationError(IllegalStateException exception) {
        return ResultUtils.error(BusinessCode.OPERATION_ERROR, message(exception));
    }

    private String message(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Lark IM request failed";
        }
        return message;
    }

    private boolean isClientConnectionClosed(IOException exception) {
        if (exception == null) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("broken pipe")
                || normalized.contains("connection reset by peer")
                || normalized.contains("an established connection was aborted")
                || normalized.contains("你的主机中的软件中止了一个已建立的连接");
    }
}
