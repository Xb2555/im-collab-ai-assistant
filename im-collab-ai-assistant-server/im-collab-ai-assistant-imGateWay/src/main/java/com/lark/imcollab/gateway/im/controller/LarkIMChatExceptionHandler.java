package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.common.exception.ErrorCode;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.gateway.im.client.LarkOpenApiException;
import com.lark.imcollab.gateway.im.service.LarkIMUnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LarkIMChatController.class)
public class LarkIMChatExceptionHandler {

    @ExceptionHandler(LarkIMUnauthorizedException.class)
    public ResponseEntity<BaseResponse<?>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponse<?>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ResultUtils.error(ErrorCode.PARAMS_ERROR, message(exception)));
    }

    @ExceptionHandler(LarkOpenApiException.class)
    public BaseResponse<?> larkOpenApiError(LarkOpenApiException exception) {
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, message(exception));
    }

    @ExceptionHandler(IllegalStateException.class)
    public BaseResponse<?> operationError(IllegalStateException exception) {
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, message(exception));
    }

    private String message(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Lark IM request failed";
        }
        return message;
    }
}
