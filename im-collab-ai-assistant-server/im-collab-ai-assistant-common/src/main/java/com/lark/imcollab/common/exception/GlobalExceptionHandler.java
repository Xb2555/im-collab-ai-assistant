package com.lark.imcollab.common.exception;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
@Hidden
public class GlobalExceptionHandler {

    @ExceptionHandler(AiAssistantException.class)
    public BaseResponse<?> aiAssistantExceptionHandler(AiAssistantException e) {
        log.error("AiAssistantException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(BusinessCode.SYSTEM_ERROR, "系统错误");
    }
}