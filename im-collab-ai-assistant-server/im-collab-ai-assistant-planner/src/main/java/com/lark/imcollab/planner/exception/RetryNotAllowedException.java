package com.lark.imcollab.planner.exception;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;

public class RetryNotAllowedException extends AiAssistantException {

    public RetryNotAllowedException(String message) {
        super(BusinessCode.OPERATION_ERROR, message);
    }
}
