package com.lark.imcollab.planner.exception;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;

public class SupervisorException extends AiAssistantException {
    public SupervisorException(String message) {
        super(BusinessCode.VERSION_CONFLICT, message);
    }
}
