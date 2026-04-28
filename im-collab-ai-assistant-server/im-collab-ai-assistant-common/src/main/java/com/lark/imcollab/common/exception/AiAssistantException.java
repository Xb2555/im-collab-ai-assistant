package com.lark.imcollab.common.exception;

import com.lark.imcollab.common.model.enums.BusinessCode;
import lombok.Getter;

@Getter
public class AiAssistantException extends RuntimeException{

    /**
     * 错误码
     */
    private final int code;

    public AiAssistantException(int code, String message) {
        super(message);
        this.code = code;
    }

    public AiAssistantException(BusinessCode businessCode) {
        super(businessCode.getMessage());
        this.code = businessCode.getCode();
    }

    public AiAssistantException(BusinessCode businessCode, String message) {
        super(message);
        this.code = businessCode.getCode();
    }
}
