package com.lark.imcollab.common.exception;

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

    public AiAssistantException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public AiAssistantException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
