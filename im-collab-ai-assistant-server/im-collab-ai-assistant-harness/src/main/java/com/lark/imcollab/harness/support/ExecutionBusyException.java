package com.lark.imcollab.harness.support;

public class ExecutionBusyException extends RuntimeException {

    public ExecutionBusyException(String message) {
        super(message);
    }

    public ExecutionBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
