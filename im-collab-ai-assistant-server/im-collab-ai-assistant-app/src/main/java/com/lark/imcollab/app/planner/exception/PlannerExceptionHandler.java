package com.lark.imcollab.app.planner.exception;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.planner.exception.RetryNotAllowedException;
import com.lark.imcollab.planner.exception.VersionConflictException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(1)
@RestControllerAdvice
@Hidden
public class PlannerExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PlannerExceptionHandler.class);

    @ExceptionHandler(VersionConflictException.class)
    public BaseResponse<?> versionConflict(VersionConflictException e) {
        log.warn("Version conflict: {}", e.getMessage());
        return ResultUtils.error(BusinessCode.VERSION_CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RetryNotAllowedException.class)
    public BaseResponse<?> retryNotAllowed(RetryNotAllowedException e) {
        log.warn("Retry not allowed: {}", e.getMessage());
        return ResultUtils.error(BusinessCode.OPERATION_ERROR, e.getMessage());
    }
}
