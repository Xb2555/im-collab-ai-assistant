package com.lark.imcollab.app.planner.exception;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.planner.exception.VersionConflictException;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(1)
@RestControllerAdvice
@Hidden
public class PlannerExceptionHandler {

    @ExceptionHandler(VersionConflictException.class)
    public BaseResponse<?> versionConflict(VersionConflictException e) {
        log.warn("Version conflict: {}", e.getMessage());
        return ResultUtils.error(BusinessCode.VERSION_CONFLICT, e.getMessage());
    }
}
