package com.lark.imcollab.planner.exception;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;

public class VersionConflictException extends AiAssistantException {
    public VersionConflictException(String message) {
        super(BusinessCode.VERSION_CONFLICT, message);
    }
}
